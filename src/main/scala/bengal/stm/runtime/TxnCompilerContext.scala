/*
 * Copyright 2023 Greg von Nessi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.entrolution
package bengal.stm.runtime

import bengal.stm.model.TxnErratum._
import bengal.stm.model._
import bengal.stm.model.runtime._

import cats.arrow.FunctionK
import cats.data.StateT
import cats.effect.kernel.Async
import cats.syntax.all._

import scala.annotation.nowarn

private[stm] trait TxnCompilerContext[F[_]] {
  this: AsyncImplicits[F] with TxnLogContext[F] with TxnAdtContext[F] =>

  private[stm] type IdFootprintStore[T] = StateT[F, IdFootprint, T]
  private[stm] type TxnLogStore[T]      = StateT[F, TxnLog, T]

  private def noOp[S]: StateT[F, S, Unit] =
    StateT[F, S, Unit](s => Async[F].delay((s, ())))

  private[stm] case class StaticAnalysisShortCircuitException(
    idFootprint: IdFootprint
  ) extends RuntimeException

  // @nowarn on FunctionK.apply: The compiler cannot verify exhaustive pattern matching
  // on TxnOrErr[V] (= Either[TxnErratum, TxnAdt[F, V]]) due to type erasure. The match
  // is exhaustive in practice — all TxnAdt and TxnErratum cases are covered, with a
  // fallback wildcard for safety.
  private[stm] def staticAnalysisCompiler: FunctionK[TxnOrErr, IdFootprintStore] =
    new (FunctionK[TxnOrErr, IdFootprintStore]) {

      @nowarn
      def apply[V](fa: TxnOrErr[V]): IdFootprintStore[V] =
        fa match {
          case Right(entry) =>
            entry match {
              case TxnUnit =>
                noOp[IdFootprint]
              case TxnDelay(thunk) =>
                StateT[F, IdFootprint, V] { s =>
                  thunk
                    .map { materializedValue =>
                      (s, materializedValue)
                    }
                    .handleErrorWith { _ =>
                      Async[F].raiseError(StaticAnalysisShortCircuitException(s))
                    }
                }
              case TxnPure(value) =>
                StateT[F, IdFootprint, V](s => Async[F].pure((s, value)))
              case TxnGetVar(txnVar) =>
                StateT[F, IdFootprint, V] { s =>
                  for {
                    rId    <- Async[F].delay(txnVar.runtimeId)
                    v      <- txnVar.get
                    result <- Async[F].delay(s.addReadId(rId))
                  } yield (result, v)
                }
              case adt: TxnGetVarMap[_, _] =>
                StateT[F, IdFootprint, V] { s =>
                  for {
                    rId    <- Async[F].delay(adt.txnVarMap.runtimeId)
                    v      <- adt.txnVarMap.get
                    result <- Async[F].delay(s.addReadId(rId))
                  } yield (result, v)
                }
              case adt: TxnGetVarMapValue[_, _] =>
                StateT[F, IdFootprint, V] { s =>
                  adt.key
                    .flatMap { materializedKey =>
                      for {
                        oTxnVar <-
                          adt.txnVarMap.getTxnVar(materializedKey)
                        value <-
                          oTxnVar
                            .map(_.get.map(Some(_)))
                            .getOrElse(Async[F].pure(None))
                        eRId     <- adt.txnVarMap.getRuntimeId(materializedKey)
                        valueAsV <- Async[F].delay(value.asInstanceOf[V])
                        result <-
                          Async[F].delay(s.addReadId(eRId)).map((_, valueAsV))
                      } yield result
                    }
                    .handleErrorWith { _ =>
                      Async[F].raiseError(StaticAnalysisShortCircuitException(s))
                    }
                }
              case adt: TxnSetVar[_] =>
                StateT[F, IdFootprint, V] { s =>
                  Async[F].delay(
                    (s.addWriteId(adt.txnVar.runtimeId), ())
                  )
                }
              case adt: TxnSetVarMap[_, _] =>
                StateT[F, IdFootprint, V] { s =>
                  Async[F].delay(
                    (s.addWriteId(adt.txnVarMap.runtimeId), ())
                  )
                }
              case adt: TxnSetVarMapValue[_, _] =>
                StateT[F, IdFootprint, Unit] { s =>
                  adt.key
                    .flatMap { materializedKey =>
                      for {
                        eRId   <- adt.txnVarMap.getRuntimeId(materializedKey)
                        result <- Async[F].delay(s.addWriteId(eRId)).map((_, ()))
                      } yield result
                    }
                    .handleErrorWith { _ =>
                      Async[F].delay((s, ()))
                    }
                }
              case adt: TxnModifyVarMapValue[_, _] =>
                StateT[F, IdFootprint, Unit] { s =>
                  adt.key
                    .flatMap { materializedKey =>
                      for {
                        eRId <- adt.txnVarMap.getRuntimeId(materializedKey)
                        result <- Async[F]
                                    .delay(s.addWriteId(eRId))
                                    .map((_, ()))
                      } yield result
                    }
                    .handleErrorWith { _ =>
                      Async[F].delay((s, ()))
                    }
                }
              case adt: TxnDeleteVarMapValue[_, _] =>
                StateT[F, IdFootprint, Unit] { s =>
                  adt.key
                    .flatMap { materializedKey =>
                      for {
                        eRId   <- adt.txnVarMap.getRuntimeId(materializedKey)
                        result <- Async[F].delay(s.addWriteId(eRId)).map((_, ()))
                      } yield result
                    }
                    .handleErrorWith { _ =>
                      Async[F].delay((s, ()))
                    }
                }
              case adt: TxnHandleError[_] =>
                StateT[F, IdFootprint, V] { s =>
                  adt.fa
                    .flatMap { materializedF =>
                      materializedF
                        .foldMap(staticAnalysisCompiler)
                        .run(s)
                    }
                    .handleErrorWith { _ =>
                      Async[F].delay((s, ().asInstanceOf[V]))
                    }
                }
              case _ =>
                noOp[IdFootprint].map(_.asInstanceOf[V])
            }
          case _ =>
            noOp[IdFootprint].map(_.asInstanceOf[V])
        }
    }

  private[stm] lazy val txnLogCompiler: FunctionK[TxnOrErr, TxnLogStore] =
    new (FunctionK[TxnOrErr, TxnLogStore]) {

      @nowarn
      def apply[V](fa: TxnOrErr[V]): TxnLogStore[V] =
        fa match {
          case Right(entry) =>
            entry match {
              case TxnUnit =>
                noOp[TxnLog]
              case TxnDelay(thunk) =>
                StateT[F, TxnLog, V] { s =>
                  s.delay(thunk)
                }
              case TxnPure(value) =>
                StateT[F, TxnLog, V] { s =>
                  s.pure(value)
                }
              case TxnGetVar(txnVar) =>
                StateT[F, TxnLog, V] { s =>
                  s.getVar(txnVar)
                }
              case adt: TxnSetVar[_] =>
                StateT[F, TxnLog, V] { s =>
                  s.setVar(adt.newValue, adt.txnVar)
                    .map((_, ()))
                }
              case adt: TxnGetVarMap[_, _] =>
                StateT[F, TxnLog, V] { s =>
                  s.getVarMap(adt.txnVarMap).map { stateAndValue =>
                    (stateAndValue._1, stateAndValue._2)
                  }
                }
              case adt: TxnGetVarMapValue[_, _] =>
                StateT[F, TxnLog, V] { s =>
                  s.getVarMapValue(adt.key, adt.txnVarMap).map { stateAndValue =>
                    (stateAndValue._1, stateAndValue._2)
                  }
                }
              case adt: TxnSetVarMap[_, _] =>
                StateT[F, TxnLog, V] { s =>
                  for {
                    newState <-
                      s.setVarMap(adt.newMap, adt.txnVarMap)
                  } yield (newState, ())
                }
              case adt: TxnSetVarMapValue[_, _] =>
                StateT[F, TxnLog, V] { s =>
                  s.setVarMapValue(adt.key, adt.newValue, adt.txnVarMap)
                    .map((_, ()))
                }
              case adt: TxnModifyVarMapValue[_, _] =>
                StateT[F, TxnLog, V] { s =>
                  s.modifyVarMapValue(adt.key, adt.f, adt.txnVarMap)
                    .map((_, ()))
                }
              case adt: TxnDeleteVarMapValue[_, _] =>
                StateT[F, TxnLog, V] { s =>
                  s.deleteVarMapValue(adt.key, adt.txnVarMap)
                    .map((_, ()))
                }
              case adt: TxnHandleError[_] =>
                StateT[F, TxnLog, V] { s =>
                  (for {
                    materializedF <- adt.fa
                    originalResult <-
                      materializedF.foldMap(txnLogCompiler).run(s)
                    finalResult <- originalResult._1 match {
                                     case TxnLogError(ex) =>
                                       adt
                                         .f(ex)
                                         .flatMap {
                                           _.foldMap(txnLogCompiler)
                                             .run(s)
                                         }
                                     case _ =>
                                       Async[F].pure(originalResult)
                                   }
                  } yield (finalResult._1, finalResult._2)).handleErrorWith { ex =>
                    s.raiseError(ex).map((_, ().asInstanceOf[V]))
                  }
                }
              case _ =>
                noOp[TxnLog].map(_.asInstanceOf[V])
            }
          case Left(erratum) =>
            erratum match {
              case TxnRetry =>
                StateT[F, TxnLog, V](
                  _.scheduleRetry.map((_, ().asInstanceOf[V]))
                )
              case TxnError(ex) =>
                StateT[F, TxnLog, V](
                  _.raiseError(ex).map((_, ().asInstanceOf[V]))
                )
              case _ =>
                noOp[TxnLog].map(_.asInstanceOf[V])
            }
        }
    }
}
