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
import scala.util.control.NoStackTrace

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
    StateT.pure[F, S, Unit](())

  private def shortCircuit[A](s: IdFootprint): F[A] =
    Async[F].raiseError(StaticAnalysisShortCircuitException(s))

  // The one analysis arm for a keyed WRITE (set/modify/delete of a map key):
  // materialize the key, register its existential id as a write. The key thunk
  // throwing means this write's id was never recorded. Unlike the read paths,
  // the handler does NOT short-circuit: analysis carries on and the resulting
  // footprint silently omits a write, never reaching TxnRuntime.commit's
  // handleErrorWith. Flag it here or the omission is invisible. (An undeclared
  // WRITE is not benign: it invalidates a correctly-declared peer's reads —
  // specs/commit/CommitH3Writer.cfg.)
  private def analyzeKeyedWrite[K, V](
    key: F[K],
    txnVarMap: TxnVarMap[F, K, V]
  ): IdFootprintStore[Unit] =
    StateT[F, IdFootprint, Unit] { s =>
      key
        .flatMap { materializedKey =>
          for {
            eRId   <- txnVarMap.getRuntimeId(materializedKey)
            result <- Async[F].delay(s.addWriteId(eRId)).map((_, ()))
          } yield result
        }
        .handleErrorWith { _ =>
          Async[F].delay((s.markUnderApproximated, ()))
        }
    }

  private[stm] case class StaticAnalysisShortCircuitException(
    idFootprint: IdFootprint
  ) extends RuntimeException
      with NoStackTrace

  // The walker reached a terminal erratum — a waitFor retry or an abort — and
  // STOPPED. Unlike StaticAnalysisShortCircuitException above, the carried
  // footprint is COMPLETE for this attempt, not partial: everything past the
  // erratum is unreachable in the run pass too (the run terminates at the same
  // node when the passes agree, and the commit-time coverage gate refines when
  // they diverge). The erratum kind travels along because TxnHandleError's
  // analysis arm must mirror the runtime's split: errors are recoverable and
  // the walk resumes past the handler, but a retry is not absorbable and must
  // stop the whole analysis. NoStackTrace: this is raised on every park
  // attempt's analysis pass, and the trace is noise.
  private[stm] case class StaticAnalysisErratumStopException(
    idFootprint: IdFootprint,
    erratum: TxnErratum
  ) extends RuntimeException
      with NoStackTrace

  // @nowarn on BOTH FunctionK.apply methods below, and not for exhaustivity.
  // What the annotation suppresses is the free-monad interpreter's unavoidable
  // Unit casts. A FunctionK must produce a V for every op; where a walk ends
  // on a Unit-valued path — the errata arms and the analysis walker's
  // TxnHandleError recovery — those arms hand back ().asInstanceOf[V] (the
  // setter arms need no cast: their pattern match refines V = Unit), and
  // Scala 2.13 flags every one as a "dubious usage of asInstanceOf with unit
  // value". CI compiles both Scala versions, with warnings fatal.
  //
  // BOTH walkers stop at a terminal erratum, each in its own way. txnLogCompiler
  // enumerates the errata — TxnRetry schedules a retry (which throws), TxnError
  // raises. staticAnalysisCompiler raises StaticAnalysisErratumStopException,
  // and the footprint it carries is COMPLETE: the run pass terminates at the
  // same node, so nothing past it can be touched this attempt. The walker USED
  // to walk past errata instead, on the argument that over-approximation only
  // costs concurrency — true, but walking past is also what made post-waitFor
  // nodes analysable, and a throw there (the ordinary read-your-own-write
  // fallback shape) marked the footprint UNDER-approximated. A blocked
  // transaction with an under-approximated footprint is incompatible with
  // everything: it cannot park quietly, is woken by every commit, and wakes
  // every other parked transaction when it resubmits. Stopping at the erratum
  // makes that flag unmanufacturable on the park path — the parking footprint
  // is exactly the pre-waitFor read set, which is the predicate's dependency
  // set by the waitFor contract — and it stops thunks positioned after a
  // terminal erratum from running in a pass whose run-side never reaches them.
  // Divergence between the passes (the predicate flipping between analysis and
  // run) is caught by the commit-time coverage gate, which refines from the
  // actual log and re-runs — the same road every data-dependent divergence
  // takes. That includes every wake whose continuation writes: wakes fire from
  // pre-publish submission sweeps (H1), so no analysis timed anywhere before
  // the woken run can see the satisfied predicate — the refinement lap is the
  // structural price of the stop, bounded at one per wake.
  private[stm] def staticAnalysisCompiler: FunctionK[TxnOrErr, IdFootprintStore] =
    new FunctionK[TxnOrErr, IdFootprintStore] {

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
                    .handleErrorWith(_ => shortCircuit(s))
                }
              case TxnPure(value) =>
                StateT.pure[F, IdFootprint, V](value)
              case TxnGetVar(txnVar) =>
                StateT[F, IdFootprint, V] { s =>
                  txnVar.get.map(v => (s.addReadId(txnVar.runtimeId), v))
                }
              case adt: TxnGetVarMap[_, _] =>
                StateT[F, IdFootprint, V] { s =>
                  adt.txnVarMap.get.map(v => (s.addReadId(adt.txnVarMap.runtimeId), v))
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
                        eRId <- adt.txnVarMap.getRuntimeId(materializedKey)
                        // A statically-safe re-assertion, not a runtime coercion:
                        // TxnGetVarMapValue[K, V] extends TxnAdt[Option[V]], so this
                        // arm's V IS Option[v] — but the [_, _] wildcard pattern
                        // erases that equality, and 2.13's GADT inference cannot
                        // recover it from named type variables either (tried; it
                        // infers a bounded existential instead). The cast is what
                        // keeps this arm compiling on both Scala versions.
                        valueAsV <- Async[F].delay(value.asInstanceOf[V])
                        result <-
                          Async[F].delay(s.addReadId(eRId)).map((_, valueAsV))
                      } yield result
                    }
                    .handleErrorWith(_ => shortCircuit(s))
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
                analyzeKeyedWrite(adt.key, adt.txnVarMap)
              case adt: TxnModifyVarMapValue[_, _] =>
                analyzeKeyedWrite(adt.key, adt.txnVarMap)
              case adt: TxnDeleteVarMapValue[_, _] =>
                analyzeKeyedWrite(adt.key, adt.txnVarMap)
              // The error-swallowing handler in this walker that neither
              // short-circuits nor flags — read it against the three write-arm
              // handlers above, which call the flag mandatory.
              //
              // On an inner ERROR this returns `s`, discarding everything the
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
              //
              // A RETRY-origin stop is the one thing this arm must NOT swallow,
              // mirroring txnLogCompiler's handler arm, which re-raises
              // TxnRetryException past handlers (a retry is not absorbable).
              // Swallowing it here would drop the block's pre-waitFor reads
              // from the declared footprint AND hand the post-handler
              // continuation a Unit it may force — a throw there would mark the
              // footprint under-approximated, recreating the very storm the
              // erratum stop exists to prevent. Re-raised, the stop's carried
              // footprint was built with the in-block state, so the eventual
              // park footprint includes the block's reads exactly.
              case adt: TxnHandleError[_] =>
                StateT[F, IdFootprint, V] { s =>
                  adt.fa
                    .flatMap { materializedF =>
                      materializedF
                        .foldMap(staticAnalysisCompiler)
                        .run(s)
                    }
                    .handleErrorWith {
                      case e @ StaticAnalysisErratumStopException(_, TxnRetry) =>
                        Async[F].raiseError(e)
                      case _ =>
                        Async[F].delay((s, ().asInstanceOf[V]))
                    }
                }
              case unhandled =>
                StateT.liftF[F, IdFootprint, V](
                  Async[F].raiseError(new MatchError(unhandled))
                )
            }
          case Left(erratum) =>
            // A terminal erratum: the walk STOPS, carrying the accumulated
            // footprint as COMPLETE. See the header comment; the run pass
            // terminates at this same node when the passes agree, and the
            // coverage gate refines when they diverge.
            StateT[F, IdFootprint, V] { s =>
              Async[F].raiseError(StaticAnalysisErratumStopException(s, erratum))
            }
        }
    }

  private[stm] lazy val txnLogCompiler: FunctionK[TxnOrErr, TxnLogStore] =
    new FunctionK[TxnOrErr, TxnLogStore] {

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
                  s.getVarMap(adt.txnVarMap)
                }
              case adt: TxnGetVarMapValue[_, _] =>
                StateT[F, TxnLog, V] { s =>
                  s.getVarMapValue(adt.key, adt.txnVarMap)
                }
              case adt: TxnSetVarMap[_, _] =>
                StateT[F, TxnLog, V] { s =>
                  s.setVarMap(adt.newMap, adt.txnVarMap)
                    .map((_, ()))
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
              case unhandled =>
                StateT.liftF[F, TxnLog, V](
                  Async[F].raiseError(new MatchError(unhandled))
                )
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
            }
        }
    }
}
