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

import cats.effect.std.Semaphore
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{ Deferred, IO, Ref }
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import bengal.stm.STM
import bengal.stm.model.runtime._

/** Protocol-level pins for dependency-edge DIRECTION, driven against a standalone scheduler.
  *
  * The two submit paths share one scaffold (`registerAndSweep`) and differ only in how an edge to an incompatible
  * peer is directed. `submitTxn` always points the edge forward (the newcomer waits); the resubmission path reverses
  * it for peers that have not started — the anti-starvation half of the dirty-refinement story, narrated on
  * `submitTxnForImmediateRetry`. These pin the direction itself — whose tally rises, who holds the release closure —
  * which no public API observes.
  *
  * Every transaction here carries the same single-id WRITER footprint: a write-write overlap makes any two of them
  * incompatible, so each scan is forced to form an edge. Peers are seeded straight into `activeTransactions` with a
  * hand-set status and no execute fiber, and the submitted transaction always ends with a non-zero tally, so nothing
  * runs, nothing drains, and the post-submit bookkeeping is stable for the assertions.
  */
class ResubmissionEdgeSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers with StmSuite {

  private val contendedFootprint = IdFootprint.writeOnly(TxnVarRuntimeId(1L)).getValidated

  private def freshScheduler(stm: STM[IO]): IO[stm.TxnScheduler] =
    for {
      graphSem <- Semaphore[IO](1)
      retrySem <- Semaphore[IO](1)
    } yield stm.TxnScheduler(graphSem, retrySem)

  private def analysedTxn(
    stm: STM[IO]
  )(scheduler: stm.TxnScheduler, id: Long, status: ExecutionStatus): IO[stm.AnalysedTxn[Unit]] =
    for {
      signal    <- Deferred[IO, Either[Throwable, Unit]]
      tally     <- Ref[IO].of(0)
      statusRef <- Ref[IO].of(status)
      hasDown   <- Ref[IO].of(false)
      cascade   <- Ref[IO].of(false)
      abandoned <- Ref[IO].of(false)
    } yield stm.AnalysedTxn[Unit](
      id               = id,
      txn              = stm.unit,
      idFootprint      = contendedFootprint,
      completionSignal = signal,
      dependencyTally  = tally,
      unsubSpecs       = TrieMap(),
      executionStatus  = statusRef,
      hasDownstream    = hasDown,
      cascadeFired     = cascade,
      abandoned        = abandoned,
      scheduler        = scheduler
    )

  "resubmission reverses the edge to a Scheduled peer and takes the forward edge to a Running one" in withRuntime {
    implicit stm =>
      for {
        scheduler <- freshScheduler(stm)
        scheduled <- analysedTxn(stm)(scheduler, id = 1L, status = Scheduled)
        running   <- analysedTxn(stm)(scheduler, id = 2L, status = Running)
        refiner   <- analysedTxn(stm)(scheduler, id = 3L, status = NotScheduled)
        _ <- IO {
               scheduler.activeTransactions.addOne(1L -> scheduled)
               scheduler.activeTransactions.addOne(2L -> running)
             }
        _              <- scheduler.submitTxnForImmediateRetry(refiner)
        scheduledTally <- scheduled.dependencyTally.get
        runningTally   <- running.dependencyTally.get
        refinerTally   <- refiner.dependencyTally.get
        refinerHolds   <- IO(refiner.unsubSpecs.keySet.toSet)
        runningHolds   <- IO(running.unsubSpecs.keySet.toSet)
      } yield (scheduledTally, runningTally, refinerTally, refinerHolds, runningHolds)
  }
    .asserting { case (scheduledTally, runningTally, refinerTally, refinerHolds, runningHolds) =>
      // Reversed: the Scheduled peer now waits on the refiner, and the refiner
      // holds its release. Forward: the Running peer is untouchable mid-window,
      // so the refiner waits on IT — which is also what keeps this topology
      // frozen for the reads above (tally 1 spawns no execute fiber).
      scheduledTally shouldBe 1
      runningTally shouldBe 0
      refinerTally shouldBe 1
      refinerHolds shouldBe Set(1L)
      runningHolds shouldBe Set(3L)
    }

  "first submission takes the forward edge regardless of peer status" in withRuntime { implicit stm =>
    for {
      scheduler <- freshScheduler(stm)
      scheduled <- analysedTxn(stm)(scheduler, id = 1L, status = Scheduled)
      running   <- analysedTxn(stm)(scheduler, id = 2L, status = Running)
      newcomer  <- analysedTxn(stm)(scheduler, id = 3L, status = NotScheduled)
      _ <- IO {
             scheduler.activeTransactions.addOne(1L -> scheduled)
             scheduler.activeTransactions.addOne(2L -> running)
           }
      _              <- scheduler.submitTxn(newcomer)
      scheduledTally <- scheduled.dependencyTally.get
      runningTally   <- running.dependencyTally.get
      newcomerTally  <- newcomer.dependencyTally.get
      scheduledHolds <- IO(scheduled.unsubSpecs.keySet.toSet)
      runningHolds   <- IO(running.unsubSpecs.keySet.toSet)
    } yield (scheduledTally, runningTally, newcomerTally, scheduledHolds, runningHolds)
  }
    .asserting { case (scheduledTally, runningTally, newcomerTally, scheduledHolds, runningHolds) =>
      // The contrast pin: same topology as above, but a FIRST submission waits
      // on both peers — Scheduled included. Only the dirty path may jump a
      // queue.
      scheduledTally shouldBe 0
      runningTally shouldBe 0
      newcomerTally shouldBe 2
      scheduledHolds shouldBe Set(3L)
      runningHolds shouldBe Set(3L)
    }
}
