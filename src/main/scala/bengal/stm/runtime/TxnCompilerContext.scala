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

import scala.annotation.nowarn

import cats.arrow.FunctionK
import cats.data.StateT
import cats.effect.kernel.Async
import cats.syntax.all._

import bengal.stm.model.TxnErratum._
import bengal.stm.model._
import bengal.stm.model.runtime._

private[stm] trait TxnCompilerContext[F[_]] {
  this: AsyncImplicits[F] with TxnLogContext[F] with TxnAdtContext[F] =>

  private[stm] type IdFootprintStore[T] = StateT[F, IdFootprint, T]
  private[stm] type TxnLogStore[T]      = StateT[F, TxnLog, T]

  private def noOp[S]: StateT[F, S, Unit] =
    StateT[F, S, Unit](s => Async[F].delay((s, ())))

  private[stm] case class StaticAnalysisShortCircuitException(
    idFootprint: IdFootprint
  ) extends RuntimeException

  // @nowarn on BOTH FunctionK.apply methods below, and not for exhaustivity:
  // each match ends in `case _ =>`, so it is exhaustive by construction and no
  // compiler ever asks. What the annotation suppresses is the free-monad
  // interpreter's unavoidable Unit casts. A FunctionK must produce a V for every
  // op, including the ops whose result genuinely is Unit — the setters, the
  // no-ops, the errata — so those arms hand back ().asInstanceOf[V], and Scala
  // 2.13 flags every one as a "dubious usage of asInstanceOf with unit value".
  // Scala 3 instead flags the opposite: the trailing `case _ =>` in
  // txnLogCompiler's erratum match is UNREACHABLE, TxnRetry and TxnError having
  // already exhausted TxnErratum. CI compiles both, with warnings fatal.
  //
  // The two walkers do not cover the same ground, which matters more than the
  // annotation does. txnLogCompiler enumerates the errata — TxnRetry schedules a
  // retry, TxnError raises. staticAnalysisCompiler matches NO erratum: its
  // `case _ =>` swallows the whole Left side, so the walk carries on past a
  // retry or an abort that will terminate the real run. That is safe, because a
  // footprint declares what a transaction MAY touch: walking past a
  // short-circuit can only OVER-approximate, and over-approximation costs
  // concurrency rather than soundness (`covers` still holds; the scheduler just
  // serializes more than it needs to). Under-approximation is the unsound
  // direction — H3, and the reason TxnRuntime.commit flags it. Where the
  // continuation cannot cope with the Unit it gets handed back, the walk throws
  // instead, and that same handler flags the footprint.
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
                    // The key thunk threw, so this write's id was never
                    // recorded. Unlike the read paths, this handler does NOT
                    // short-circuit: analysis carries on and the resulting
                    // footprint silently omits a write, never reaching
                    // TxnRuntime.commit's handleErrorWith. Flag it here or the
                    // omission is invisible. (An undeclared WRITE is not benign:
                    // it invalidates a correctly-declared peer's reads —
                    // specs/commit/CommitH3Writer.cfg.)
                    .handleErrorWith { _ =>
                      Async[F].delay((s.markUnderApproximated, ()))
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
                    // The key thunk threw, so this write's id was never
                    // recorded. Unlike the read paths, this handler does NOT
                    // short-circuit: analysis carries on and the resulting
                    // footprint silently omits a write, never reaching
                    // TxnRuntime.commit's handleErrorWith. Flag it here or the
                    // omission is invisible. (An undeclared WRITE is not benign:
                    // it invalidates a correctly-declared peer's reads —
                    // specs/commit/CommitH3Writer.cfg.)
                    .handleErrorWith { _ =>
                      Async[F].delay((s.markUnderApproximated, ()))
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
                    // The key thunk threw, so this write's id was never
                    // recorded. Unlike the read paths, this handler does NOT
                    // short-circuit: analysis carries on and the resulting
                    // footprint silently omits a write, never reaching
                    // TxnRuntime.commit's handleErrorWith. Flag it here or the
                    // omission is invisible. (An undeclared WRITE is not benign:
                    // it invalidates a correctly-declared peer's reads —
                    // specs/commit/CommitH3Writer.cfg.)
                    .handleErrorWith { _ =>
                      Async[F].delay((s.markUnderApproximated, ()))
                    }
                }
              // The FOURTH error-swallowing handler in this walker, and the only
              // one that neither short-circuits nor flags — so read it against
              // the three above, which call the flag mandatory.
              //
              // On an inner failure this returns `s`, discarding everything the
              // inner analysis accumulated, a
              // StaticAnalysisShortCircuitException's partial footprint
              // included. And adt.f — the RECOVERY branch — is never analysed at
              // all, though txnLogCompiler folds it at run time. So a recovery
              // branch's reads and writes reach the real log while being absent
              // from the declared footprint, and nothing marks the footprint
              // under-approximated.
              //
              // NOT a soundness hole, because H6 catches it downstream:
              // coversActualFootprint compares the declared footprint against the
              // actual log under the commit locks and BEFORE the publish, so an
              // error-recovering transaction that touched undeclared state
              // refines and re-runs instead of publishing. The undeclared write
              // never lands and the undeclared read never reaches a caller. What
              // it costs is a mandatory re-run on every such transaction.
              //
              // Flagging it here would be worse, not better: an under-approximated
              // footprint is incompatible with everything and never refines (see
              // IdFootprint.isCompatibleWith), so the transaction would be
              // serialized against all peers for good rather than re-running once
              // with an accurate footprint.
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
              // Recovery is EXCEPTION-based: errors short-circuit the fold as a
              // raised TxnErrorException (TxnLogContext.raiseError and
              // TxnLogValid.delay's handler), so the guarded block's failure
              // arrives here as a raised F, never as a threaded TxnLogError
              // state. Recovery runs against the PRE-BLOCK state `s` — every
              // write the failed block staged is rolled back by construction.
              //
              // The last arm catches raw throwables too (a `map` function
              // throwing mid-glue, a by-name Txn constructor throwing): the
              // IMMEDIATE handler recovers them, where the state-threaded
              // design routed them past its own recovery dispatch and only a
              // second, enclosing handler could catch them. A retry is the one
              // signal deliberately NOT absorbable — a blocked transaction
              // stays blocked (re-raised, as before).
              //
              // An error raised by the RECOVERY branch itself is not re-caught
              // here — it propagates to the next enclosing handler, or to the
              // caller. That matches the state-threaded behaviour.
              case adt: TxnHandleError[_] =>
                StateT[F, TxnLog, V] { s =>
                  (for {
                    materializedF <- adt.fa
                    originalResult <-
                      materializedF.foldMap(txnLogCompiler).run(s)
                  } yield originalResult).handleErrorWith {
                    case ex: TxnRetryException =>
                      Async[F].raiseError(ex)
                    case TxnErrorException(originalEx) =>
                      adt.f(originalEx).flatMap(_.foldMap(txnLogCompiler).run(s))
                    case ex =>
                      adt.f(ex).flatMap(_.foldMap(txnLogCompiler).run(s))
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
