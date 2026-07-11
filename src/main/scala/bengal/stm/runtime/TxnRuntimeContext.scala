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

import scala.collection.concurrent.TrieMap
import scala.collection.mutable.{ Map => MutableMap }

import cats.effect.implicits._
import cats.effect.kernel.Async
import cats.effect.std.Semaphore
import cats.effect.{ Deferred, Ref }
import cats.syntax.all._

import bengal.stm.model.runtime._
import bengal.stm.model._

private[stm] trait TxnRuntimeContext[F[_]] {
  this: AsyncImplicits[F] with TxnCompilerContext[F] with TxnLogContext[F] with TxnAdtContext[F] =>

  private[stm] val txnIdGen: Ref[F, TxnId]
  private[stm] val txnVarIdGen: Ref[F, TxnVarId]

  sealed private[stm] trait TxnResult
  private[stm] case class TxnResultSuccess[V](result: V) extends TxnResult

  private[stm] case object TxnResultRetry extends TxnResult

  private[stm] case class TxnResultLogDirty(idFootprintRefinement: IdFootprint) extends TxnResult

  private[stm] case class TxnResultFailure(ex: Throwable) extends TxnResult

  private[stm] object TxnResultFailure {

    private[stm] def apply(logFailure: TxnLogError): TxnResultFailure =
      TxnResultFailure(logFailure.ex)
  }

  private[stm] case class TxnScheduler(
    graphBuilderSemaphore: Semaphore[F],
    activeTransactions: MutableMap[TxnId, AnalysedTxn[_]],
    retrySemaphore: Semaphore[F],
    retryMap: MutableMap[IdFootprint, F[Unit]]
  ) {
    override val toString: String = "TxnScheduler"

    def checkRetryQueue(idFootprint: IdFootprint): F[Unit] =
      retrySemaphore.permit.use { _ =>
        for {
          triggeredFootprints <-
            retryMap.keys.toList
              .parTraverse { waitingFootprint =>
                Async[F].ifM(
                  Async[F].delay(
                    !idFootprint.isCompatibleWith(waitingFootprint)
                  )
                )(
                  retryMap(waitingFootprint) >> Async[F].pure(
                    Option(waitingFootprint)
                  ),
                  Async[F].pure(None.asInstanceOf[Option[IdFootprint]])
                )
              }
              .map(_.flatten)
          _ <- triggeredFootprints.traverse(footprint => Async[F].delay(retryMap.remove(footprint)))
        } yield ()
      }

    def submitTxnForRetry(analysedTxn: AnalysedTxn[_]): F[Unit] =
      retrySemaphore.permit.use { _ =>
        for {
          footprint <- Async[F].delay(analysedTxn.idFootprint)
          execSpec  <- Async[F].delay(retryMap.get(footprint))
          // The wake action builds a fresh incarnation AT WAKE TIME so a
          // parked transaction never re-enters the scheduler with stale
          // bookkeeping from its previous run.
          wake = analysedTxn
                   .freshIncarnation(analysedTxn.idFootprint)
                   .flatMap(submitTxn)
                   .start
                   .void
          _ <- Async[F].delay(execSpec match {
                 case Some(spec) =>
                   retryMap.update(footprint, spec >> wake)
                 case None =>
                   retryMap.addOne(footprint -> wake)
               })
        } yield ()
      }

    def submitTxnForImmediateRetry(analysedTxn: AnalysedTxn[_]): F[Unit] =
      for {
        _ <- analysedTxn.resetDependencyTally
        _ <- graphBuilderSemaphore.permit.use { _ =>
               for {
                 testAndLink <- activeTransactions.values.toList.parTraverse { aTxn =>
                                  (for {
                                    status <- aTxn.executionStatus.get
                                    _ <- status match {
                                           case Running =>
                                             Async[F].ifM(
                                               Async[F].delay(
                                                 analysedTxn.idFootprint
                                                   .isCompatibleWith(
                                                     aTxn.idFootprint
                                                   )
                                               )
                                             )(
                                               Async[F].unit,
                                               aTxn.subscribeDownstreamDependency(
                                                 analysedTxn
                                               )
                                             )
                                           case Scheduled =>
                                             Async[F].ifM(
                                               Async[F].delay(
                                                 analysedTxn.idFootprint
                                                   .isCompatibleWith(
                                                     aTxn.idFootprint
                                                   )
                                               )
                                             )(
                                               Async[F].unit,
                                               analysedTxn.subscribeDownstreamDependency(
                                                 aTxn
                                               )
                                             )
                                           case _ =>
                                             Async[F].unit
                                         }
                                  } yield ()).start
                                }
                 _ <- analysedTxn.executionStatus.set(Scheduled)
                 _ <-
                   Async[F].delay(
                     activeTransactions.addOne(analysedTxn.id -> analysedTxn)
                   )
                 _ <- testAndLink.parTraverse(_.joinWithNever)
                 _ <- analysedTxn.checkExecutionReadiness
               } yield ()
             }
        _ <- checkRetryQueue(analysedTxn.idFootprint).start
      } yield ()

    def submitTxn(analysedTxn: AnalysedTxn[_]): F[Unit] =
      for {
        _ <- analysedTxn.resetDependencyTally
        _ <- graphBuilderSemaphore.permit.use { _ =>
               for {
                 // SPEC: ContractC — this scan is the mechanism meant to keep
                 // footprint-incompatible txns out of overlapping execute
                 // windows (Scheduler.tla checks the guarantee itself).
                 testAndLink <- activeTransactions.values.toList.parTraverse { aTxn =>
                                  Async[F]
                                    .ifM(
                                      Async[F].delay(
                                        analysedTxn.idFootprint.isCompatibleWith(
                                          aTxn.idFootprint
                                        )
                                      )
                                    )(Async[F].unit, aTxn.subscribeDownstreamDependency(analysedTxn))
                                    .start
                                }
                 _ <- analysedTxn.executionStatus.set(Scheduled)
                 _ <-
                   Async[F].delay(
                     activeTransactions.addOne(analysedTxn.id -> analysedTxn)
                   )
                 _ <- testAndLink.parTraverse(_.joinWithNever)
                 _ <- analysedTxn.checkExecutionReadiness
               } yield ()
             }
        _ <- checkRetryQueue(analysedTxn.idFootprint).start
      } yield ()

    def registerCompletion(analysedTxn: AnalysedTxn[_]): F[Unit] =
      for {
        _ <- graphBuilderSemaphore.permit.use { _ =>
               Async[F].delay(activeTransactions.remove(analysedTxn.id))
             }
        _ <- analysedTxn.triggerUnsub.start
      } yield ()

    // SPEC: NoExecOnCompleted — the admission gate: an execute fiber may
    // proceed only if, atomically under the graph semaphore, this
    // incarnation is still Scheduled AND its dependency tally is zero.
    // Stale or duplicate spawns lose the compare-and-set and exit without
    // side effects, which is what makes spurious wakeups harmless and the
    // resubmission path's reversed edges sound: a Scheduled transaction
    // whose tally was raised under the semaphore cannot slip into
    // execution, because admission is serialized against the scan that
    // raised it. (Losing is never a lost execution — whoever holds the
    // last dependency edge spawns a fresh execute when its cascade drains
    // the tally to zero.)
    def admitForExecution(analysedTxn: AnalysedTxn[_]): F[Boolean] =
      graphBuilderSemaphore.permit.use { _ =>
        Async[F].ifM(analysedTxn.dependencyTally.get.map(_ == 0))(
          analysedTxn.executionStatus.modify {
            case Scheduled => (Running, true)
            case other => (other, false)
          },
          Async[F].pure(false)
        )
      }
  }

  private[stm] object TxnScheduler {

    private[stm] def apply(
      graphBuilderSemaphore: Semaphore[F],
      retrySemaphore: Semaphore[F]
    ): TxnScheduler =
      TxnScheduler(
        activeTransactions    = TrieMap(),
        graphBuilderSemaphore = graphBuilderSemaphore,
        retrySemaphore        = retrySemaphore,
        retryMap              = TrieMap()
      )

  }

  private[stm] case class AnalysedTxn[V](
    id: TxnId,
    txn: Txn[V],
    idFootprint: IdFootprint,
    completionSignal: Deferred[F, Either[Throwable, V]],
    dependencyTally: Ref[F, Int],
    unsubSpecs: MutableMap[TxnId, F[Unit]],
    executionStatus: Ref[F, ExecutionStatus],
    hasDownstream: Ref[F, Boolean],
    cascadeFired: Ref[F, Boolean],
    scheduler: TxnScheduler
  ) {

    // Every resubmission gets FRESH protocol bookkeeping — tally, unsub
    // edges, status, cascade gate — sharing only the transaction, its id,
    // and the caller's completion signal. In-flight unsubscribe cascades
    // from the previous incarnation then drain refs nothing reads any
    // more, instead of corrupting the new incarnation's dependency
    // arithmetic (the H4 double-execution family; see the H4 narrative in
    // specs/README.md).
    private[stm] def freshIncarnation(newFootprint: IdFootprint): F[AnalysedTxn[V]] =
      for {
        newTally   <- Ref.of[F, Int](0)
        newHasDown <- Ref.of[F, Boolean](false)
        newStatus  <- Ref.of[F, ExecutionStatus](NotScheduled)
        newCascade <- Ref.of[F, Boolean](false)
      } yield this.copy(
        idFootprint     = newFootprint,
        dependencyTally = newTally,
        unsubSpecs      = TrieMap(),
        executionStatus = newStatus,
        hasDownstream   = newHasDown,
        cascadeFired    = newCascade
      )

    private[stm] val resetDependencyTally: F[Unit] =
      dependencyTally.set(0) >> hasDownstream.set(false)

    // SPEC: NoDoubleExec — execute fibers are spawned here (readiness) and in
    // unsubscribeUpstreamDependency (tally zero-test); spawning is deliberately
    // unguarded and duplicates are harmless because admitForExecution admits
    // at most one fiber per incarnation into the commit window.
    private[stm] val checkExecutionReadiness: F[Unit] =
      Async[F].ifM(dependencyTally.get.map(_ == 0))(
        execute(scheduler).start.void,
        Async[F].unit
      )

    // SPEC: TallyNonNegative — the getAndUpdate decrement below is the only
    // tally sink. Every incarnation has its own tally and its own unsub
    // edges (freshIncarnation), and the cascade drains them exactly once
    // (cascadeFired), so each decrement pairs with exactly one increment
    // and the tally can never go negative.
    private val unsubscribeUpstreamDependency: F[Unit] =
      Async[F].ifM(dependencyTally.getAndUpdate(_ - 1).map(_ == 1))(
        execute(scheduler).start.void,
        Async[F].unit
      )

    private val subscribeUpstreamDependency: F[Unit] =
      dependencyTally.update(_ + 1)

    private[stm] def subscribeDownstreamDependency(
      txn: AnalysedTxn[_]
    ): F[Unit] =
      Async[F].ifM(Async[F].delay(unsubSpecs.keys.toSet.contains(txn.id)))(
        Async[F].unit,
        for {
          _ <- txn.subscribeUpstreamDependency
          _ <-
            Async[F]
              .delay(
                unsubSpecs.addOne(
                  txn.id -> txn.unsubscribeUpstreamDependency
                )
              )
          _ <- hasDownstream.set(true)
        } yield ()
      )

    // The cascade fires exactly once per incarnation: the error-recovery
    // path in execute can call registerCompletion twice, and a second
    // drain of the same edges would double-decrement downstream tallies.
    private[stm] val triggerUnsub: F[Unit] =
      Async[F].ifM(cascadeFired.getAndSet(true))(
        Async[F].unit,
        Async[F].ifM(Async[F].delay(unsubSpecs.nonEmpty))(
          for {
            _ <- unsubSpecs.values.toList.parTraverse(unsubSpec => unsubSpec)
            _ <- Async[F].delay(unsubSpecs.clear())
          } yield (),
          Async[F].unit
        )
      )

    private[stm] def getTxnLogResult: F[(TxnLog, Option[V])] =
      txn
        .foldMap[TxnLogStore](txnLogCompiler)
        .run(TxnLogValid.empty)
        .map { res =>
          (res._1, Option(res._2))
        }
        .handleErrorWith {
          case TxnRetryException(log) =>
            Async[F].delay((TxnLogRetry(log), None))
          case ex =>
            Async[F].delay((TxnLogError(ex), None))
        }

    // SPEC: NoDoublePublish — log.commit below publishes the write set; a
    // second concurrent execute fiber for the same txn re-runs the whole
    // pipeline and publishes again (Scheduler.tla checks once-per-incarnation).
    private val commit: F[TxnResult] =
      for {
        logResult <- getTxnLogResult
        (log, logValue) = logResult
        result <- log match {
                    case TxnLogValid(_) =>
                      Async[F]
                        .uncancelable { _ =>
                          log.withLock {
                            Async[F].ifM[Option[V]](log.isDirty)(
                              Async[F].delay(None),
                              log.commit.as(logValue)
                            )
                          }
                        }
                        .flatMap { s =>
                          s.map(v =>
                            Async[F]
                              .delay(TxnResultSuccess(v).asInstanceOf[TxnResult])
                          ).getOrElse(
                            log.idFootprint
                              .map(footprint =>
                                TxnResultLogDirty(footprint)
                                  .asInstanceOf[TxnResult]
                              )
                          )
                        }
                    case retry @ TxnLogRetry(_) =>
                      Async[F].uncancelable { _ =>
                        retry.validLog.withLock {
                          Async[F].ifM[TxnResult](log.isDirty)(
                            retry.validLog.idFootprint.map(footprint =>
                              TxnResultLogDirty(footprint)
                                .asInstanceOf[TxnResult]
                            ),
                            Async[F].delay(
                              TxnResultRetry.asInstanceOf[TxnResult]
                            )
                          )
                        }
                      }
                    case err @ TxnLogError(_) =>
                      Async[F].delay(TxnResultFailure(err))
                  }
      } yield result

    private[stm] def execute(
      ex: TxnScheduler
    ): F[Unit] =
      Async[F].ifM(ex.admitForExecution(this))(
        Async[F].uncancelable { poll =>
          (for {
            result <- poll(commit)
            _      <- ex.registerCompletion(this)
            // SPEC: CompletionAtMostOnce — completes here, in the error
            // handler below, and in the submit wrapper (all Deferred
            // first-wins); the admission gate guarantees at most one
            // execute window per incarnation, so the success/failure
            // completion fires at most once organically.
            _ <- result match {
                   case TxnResultSuccess(result) =>
                     completionSignal
                       .complete(
                         Right[Throwable, V](result.asInstanceOf[V])
                       )
                       .void
                   case TxnResultRetry =>
                     Async[F].ifM(hasDownstream.get)(
                       freshIncarnation(idFootprint).flatMap(ex.submitTxn),
                       ex.submitTxnForRetry(this)
                     )
                   case TxnResultLogDirty(idFootprintRefinement) =>
                     freshIncarnation(idFootprintRefinement.getValidated)
                       .flatMap(ex.submitTxnForImmediateRetry)
                   case TxnResultFailure(err) =>
                     completionSignal
                       .complete(Left[Throwable, V](err))
                       .void
                 }
          } yield ()).handleErrorWith { unexpectedErr =>
            ex.registerCompletion(this).attempt >>
            completionSignal.complete(Left[Throwable, V](unexpectedErr)).void
          }
        },
        // Lost the admission gate: a duplicate or stale spawn for an
        // incarnation that is already running, already completed, or has
        // live dependencies again. Exit without side effects — whoever
        // holds the last dependency edge respawns on drain.
        Async[F].unit
      )
  }

  private[stm] trait TxnRuntime {

    private[stm] val scheduler: TxnScheduler

    private[stm] def commit[V](txn: Txn[V]): F[V] =
      for {
        staticAnalysisResult <-
          txn
            .foldMap[IdFootprintStore](staticAnalysisCompiler)
            .run(IdFootprint.empty)
            .map { res =>
              (res._1, Option(res._2))
            }
            // Sometimes the static analysis will unavoidably throw
            // due to impossible casts being attempted. In this case, we
            // leverage the footprint gathered to the point in the free recursion
            // up to where the error is generated. In the worst case,
            // we fall back to blindly optimistic scheduling (for the
            // first transaction attempt)
            .handleErrorWith {
              case StaticAnalysisShortCircuitException(idFootprint) =>
                Async[F].delay((idFootprint, None))
              case _ =>
                Async[F].pure((IdFootprint.empty, None))
            }
        completionSignal <- Deferred[F, Either[Throwable, V]]
        dependencyTally  <- Ref[F].of(0)
        hasDownstream    <- Ref[F].of(false)
        executionStatus  <- Ref[F].of(NotScheduled.asInstanceOf[ExecutionStatus])
        cascadeFired     <- Ref[F].of(false)
        id               <- txnIdGen.getAndUpdate(_ + 1)
        analysedTxn <-
          Async[F].delay(
            AnalysedTxn(
              id               = id,
              txn              = txn,
              idFootprint      = staticAnalysisResult._1.getValidated,
              completionSignal = completionSignal,
              dependencyTally  = dependencyTally,
              unsubSpecs       = TrieMap(),
              executionStatus  = executionStatus,
              hasDownstream    = hasDownstream,
              cascadeFired     = cascadeFired,
              scheduler        = scheduler
            )
          )
        _ <- (scheduler
               .submitTxn(analysedTxn)
               .handleErrorWith { err =>
                 completionSignal.complete(Left[Throwable, V](err)).void
               })
               .start
        completion <- completionSignal.get
        result <- completion match {
                    case Right(res) => Async[F].pure(res)
                    case Left(ex) => Async[F].raiseError(ex)
                  }
      } yield result
  }
}
