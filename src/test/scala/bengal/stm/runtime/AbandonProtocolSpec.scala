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
import scala.concurrent.duration._

import cats.effect.std.Semaphore
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{ Deferred, IO, Ref }
import cats.syntax.all._
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import bengal.stm.STM
import bengal.stm.model.runtime._

/** Protocol-level pins for `TxnScheduler.abandon`, driven against a standalone scheduler.
  *
  * The behavioural cancellation contract lives in `spec/CancellationSpec`; these assert the bookkeeping directly —
  * retry-map residue, active-set residue, and idempotency — which no public API observes.
  */
class AbandonProtocolSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers with StmSuite {

  private def freshScheduler(stm: STM[IO]): IO[stm.TxnScheduler] =
    for {
      graphSem <- Semaphore[IO](1)
      retrySem <- Semaphore[IO](1)
    } yield stm.TxnScheduler(graphSem, retrySem)

  private def analysedTxn(stm: STM[IO])(scheduler: stm.TxnScheduler, id: Long): IO[stm.AnalysedTxn[Unit]] =
    for {
      signal    <- Deferred[IO, Either[Throwable, Unit]]
      tally     <- Ref[IO].of(0)
      status    <- Ref[IO].of(NotScheduled: ExecutionStatus)
      hasDown   <- Ref[IO].of(false)
      cascade   <- Ref[IO].of(false)
      abandoned <- Ref[IO].of(false)
    } yield stm.AnalysedTxn[Unit](
      id               = id,
      txn              = stm.unit,
      idFootprint      = IdFootprint.empty.getValidated,
      completionSignal = signal,
      dependencyTally  = tally,
      unsubSpecs       = TrieMap(),
      executionStatus  = status,
      hasDownstream    = hasDown,
      cascadeFired     = cascade,
      abandoned        = abandoned,
      scheduler        = scheduler
    )

  "abandon removes a parked transaction's retry-map entry" in withRuntime { implicit stm =>
    for {
      scheduler <- freshScheduler(stm)
      aTxn      <- analysedTxn(stm)(scheduler, id = 1L)
      // Park directly: empty scheduler, empty log — no conflict, not stale.
      _           <- scheduler.submitTxnForRetry(aTxn, stm.TxnLogValid.empty)
      parkedFirst <- IO(scheduler.retryMap.contains(1L))
      _           <- scheduler.abandon(aTxn)
      parkedAfter <- IO(scheduler.retryMap.contains(1L))
      flagged     <- aTxn.abandoned.get
    } yield (parkedFirst, parkedAfter, flagged)
  }
    .asserting { case (parkedFirst, parkedAfter, flagged) =>
      parkedFirst shouldBe true
      parkedAfter shouldBe false
      flagged shouldBe true
    }

  "an abandoned transaction can no longer park or register" in withRuntime { implicit stm =>
    for {
      scheduler <- freshScheduler(stm)
      aTxn      <- analysedTxn(stm)(scheduler, id = 2L)
      _         <- scheduler.abandon(aTxn)
      // Park attempt after abandonment: the in-semaphore flag check must skip
      // both the park and the resubmission.
      _      <- scheduler.submitTxnForRetry(aTxn, stm.TxnLogValid.empty)
      parked <- IO(scheduler.retryMap.contains(2L))
      // Registration attempt after abandonment: nothing may land in the
      // active set (an execute spawn would lose the admission gate anyway,
      // but the set itself must stay clean).
      _      <- scheduler.submitTxn(aTxn)
      active <- IO(scheduler.activeTransactions.contains(2L))
    } yield (parked, active)
  }
    .asserting { case (parked, active) =>
      parked shouldBe false
      active shouldBe false
    }

  "abandon deregisters a Scheduled registration" in withRuntime { implicit stm =>
    for {
      scheduler <- freshScheduler(stm)
      aTxn      <- analysedTxn(stm)(scheduler, id = 3L)
      // Register normally. The spawned execute fiber races abandon four ways:
      // it loses the admission gate to abandon's demotion (abandon removed the
      // entry); it takes the abandoned fast-path (it deregisters); it is
      // already mid-window when abandon sweeps (abandon leaves Running alone —
      // the fiber's own epilogue deregisters); or the no-op window completes
      // first (epilogue again). Deregistration is therefore EVENTUAL in two of
      // the four orderings, so the assertion awaits the drain rather than
      // reading the map at one instant. executionStatus is deliberately not
      // asserted (the fast-path deregisters without demoting — shared with the
      // Running carve-out).
      _ <- scheduler.submitTxn(aTxn)
      _ <- scheduler.abandon(aTxn)
      _ <- IO(scheduler.activeTransactions.contains(3L))
             .flatTap(stillActive => IO.sleep(5.millis).whenA(stillActive))
             .iterateWhile(identity)
             .timeout(5.seconds)
    } yield ()
  }
    .asserting(_ shouldBe ())

  "a wake action that fired before abandonment cannot re-register the transaction" in withRuntime { implicit stm =>
    // The exact race the in-semaphore guards exist for: a sweep captures the
    // parked wake closure, abandon then COMPLETES, and only afterwards does
    // the stale closure run its fresh-incarnation submit. The submit's
    // in-graph-semaphore flag check must reject it — a top-of-method check
    // would have already been passed by the closure's construction and the
    // transaction would register, run, and publish arbitrarily later.
    for {
      scheduler <- freshScheduler(stm)
      aTxn      <- analysedTxn(stm)(scheduler, id = 5L)
      _         <- scheduler.submitTxnForRetry(aTxn, stm.TxnLogValid.empty)
      staleWake <- IO(scheduler.retryMap.get(5L).map(_._2))
      _         <- scheduler.abandon(aTxn)
      _         <- staleWake.getOrElse(IO.unit) // fire the pre-abandon wake NOW
      // The wake's submit runs on its own fiber; await quiescence before
      // asserting nothing registered.
      _      <- IO.sleep(100.millis)
      parked <- IO(scheduler.retryMap.contains(5L))
      active <- IO(scheduler.activeTransactions.contains(5L))
    } yield (parked, active)
  }
    .asserting { case (parked, active) =>
      parked shouldBe false
      active shouldBe false
    }

  "abandon is idempotent and safe on a never-submitted transaction" in withRuntime { implicit stm =>
    for {
      scheduler <- freshScheduler(stm)
      aTxn      <- analysedTxn(stm)(scheduler, id = 4L)
      _         <- scheduler.abandon(aTxn)
      _         <- scheduler.abandon(aTxn)
      flagged   <- aTxn.abandoned.get
      parked    <- IO(scheduler.retryMap.contains(4L))
      active    <- IO(scheduler.activeTransactions.contains(4L))
    } yield (flagged, parked, active)
  }
    .asserting { case (flagged, parked, active) =>
      flagged shouldBe true
      parked shouldBe false
      active shouldBe false
    }
}
