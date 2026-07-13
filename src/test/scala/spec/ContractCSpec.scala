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
package spec

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.implicits._
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import bengal.stm.STM
import bengal.stm.model._
import bengal.stm.syntax.all._

/** CONTRACT C, CHECKED AGAINST THE RUNNING CODE.
  *
  * SPEC: ContractC — no two transactions whose DECLARED footprints are incompatible are ever in their execute windows
  * at the same time.
  *
  * The whole library rests on this. Reads take no locks and are never validated at commit time, so the scheduler's
  * conflict-avoidance is the ONLY thing standing between a transaction and a stale read. `specs/scheduler/Scheduler.tla`
  * checks Contract C exhaustively and `specs/commit/CommitProtocol.tla` ASSUMES it — but an assume-guarantee split is
  * only as good as the guarantee, and until now nothing tied that guarantee to the Scala. The model could hold while the
  * code drifted, which is a sentence this project has had to write about six separate defects.
  *
  * IT ALSO CARRIES A LOAD IT DID NOT USED TO. The commit-time dirty check was removed once `CoverageSubsumesDirty`
  * proved it redundant — and that proof rests on Contract C. So this is now the executable half of the argument that
  * removal was safe: if the scheduler ever stops upholding Contract C, this suite goes red rather than a write silently
  * going missing.
  *
  * ===========================================================================
  * WHY THE METER IS SHAPED LIKE THIS
  * ===========================================================================
  * A transaction body runs TWICE per attempt — once in the static-analysis pass that computes the footprint, once for
  * real — and `delay` thunks are executed in BOTH. Analysis passes run BEFORE submission, outside `activeTransactions`,
  * so they legitimately overlap each other. A naive counter in the body would therefore report overlap that Contract C
  * says nothing about, and the suite would fail for the wrong reason.
  *
  * The discriminator is READ-YOUR-OWN-WRITE. The analysis pass does not apply writes, so reading back a value the
  * transaction itself just wrote yields the PRE-TRANSACTION value there, and the written value in the real run. The
  * marker is per-transaction, so it adds no conflict of its own.
  */
class ContractCSpec extends AnyFreeSpec with Matchers {

  private val Reps  = 12
  private val Peers = 8

  /** How long each transaction holds its execute window open. It SUSPENDS for this, rather than spinning — see the
    * comment at the sleep below. Milliseconds, because the point is to make the window wide enough to observe.
    */
  private val Window = 5.millis

  /** Records the maximum number of transaction bodies observed in flight at once. */
  final private class Meter {
    private val inFlight = new AtomicInteger(0)
    private val peak     = new AtomicInteger(0)

    def enter(): Unit = {
      val n = inFlight.incrementAndGet()
      peak.updateAndGet(m => math.max(m, n))
      ()
    }
    def exit(): Unit = { inFlight.decrementAndGet(); () }
    def max: Int     = peak.get()
  }

  /** Runs `Peers` transactions concurrently and reports the peak number of REAL runs in flight together.
    *
    * @param conflicting
    *   when true every peer reads and writes the SAME var, so all footprints are pairwise incompatible and Contract C
    *   must keep them apart. When false each peer touches its OWN var, so they are pairwise compatible and the
    *   scheduler is free to overlap them.
    */
  private def peakOverlap(conflicting: Boolean): Int = {
    val meter = new Meter

    STM
      .runtime[IO]
      .flatMap { implicit stm =>
        for {
          shared   <- TxnVar.of[IO, Int](0)
          privates <- (0 until Peers).toList.traverse(_ => TxnVar.of[IO, Int](0))
          markers  <- (0 until Peers).toList.traverse(_ => TxnVar.of[IO, Int](0))
          _ <- (0 until Peers).toList.parTraverse { i =>
                 val target = if (conflicting) shared else privates(i)
                 val marker = markers(i)
                 (for {
                   // The analysis pass records this write but does not apply it...
                   _ <- marker.set(1)
                   // ...so `seen` is 0 in analysis and 1 in the real run. Per-peer, so it
                   // conflicts with nobody and does not perturb what we are measuring.
                   seen <- marker.get
                   _    <- STM[IO].delay(if (seen == 1) meter.enter())
                   v    <- target.get
                   _    <- target.set(v + 1)
                   // HOLD THE EXECUTE WINDOW OPEN, AND DO IT BY SUSPENDING RATHER THAN SPINNING.
                   //
                   // This started as a busy spin, and a busy spin measures the wrong thing. It never
                   // yields, so two bodies can only be caught in flight together if the OS genuinely
                   // runs them on two cores at the same instant — which on CI's 2-vCPU runner it did
                   // not, so the anti-vacuity arm reported peaks of 1,1,1,... and the Contract C
                   // assertion below went VACUOUSLY TRUE. It would have passed against a completely
                   // broken scheduler. (The arm caught that, which is what it is for. Widening the
                   // spin did not help: the problem was never the width.)
                   //
                   // CONTRACT C IS A PROPERTY OF THE SCHEDULER, NOT OF THE THREAD POOL. Two
                   // transactions are in their execute windows together whether or not the OS happens
                   // to be running them on two cores. A suspending sleep makes exactly that
                   // observable: the fiber yields, a second one enters its window, and the meter sees
                   // both — on any number of cores, including one.
                   //
                   // `fromF` runs in BOTH passes, so the sleep is guarded the same way the meter is:
                   // during static analysis `seen` is 0 and this is IO.unit.
                   _ <- STM[IO].fromF(if (seen == 1) IO.sleep(Window) else IO.unit)
                   _ <- STM[IO].delay(if (seen == 1) meter.exit())
                 } yield ()).commit
               }
        } yield ()
      }
      .timeout(60.seconds)
      .unsafeRunSync()

    meter.max
  }

  "Contract C" - {

    /* THE ANTI-VACUITY CONTROL, AND IT COMES FIRST DELIBERATELY.
     *
     * "No two conflicting transactions overlapped" is trivially true of a runtime that never
     * overlaps anything, or of a meter that cannot see overlap. So before asserting that
     * conflicting transactions are kept apart, prove that the same meter DOES see COMPATIBLE
     * ones running together. If this arm ever fails, the assertion below means nothing.
     */
    "the meter can see concurrency at all: compatible transactions DO overlap" in {
      val peaks = (1 to Reps).map(_ => peakOverlap(conflicting = false))
      withClue(
        s"peers on DISJOINT vars never ran together (peaks: ${peaks.mkString(",")}) on a machine with " +
          s"${Runtime.getRuntime.availableProcessors} processors. Either the scheduler " +
          "serialized transactions it had no reason to, or this meter cannot observe overlap -- and if it cannot, " +
          "the Contract C assertion below is vacuous: "
      ) {
        peaks.max should be > 1
      }
    }

    "footprint-incompatible transactions are NEVER in their execute windows together" in {
      val peaks = (1 to Reps).map(_ => peakOverlap(conflicting = true))
      withClue(
        s"CONTRACT C IS BROKEN. ${Peers} transactions all read-modify-write the same var, so their declared " +
          s"footprints are pairwise incompatible and the scheduler must never run two at once -- but it did " +
          s"(peaks: ${peaks.mkString(",")}). Reads take no locks and are never commit-validated, so this is not a " +
          "throughput bug, it is a correctness one: the only thing standing between a transaction and a stale read " +
          "has stopped standing there. It also invalidates the removal of the commit-time dirty check, which was " +
          "proved redundant ON THE STRENGTH of Contract C (CoverageSubsumesDirty): "
      ) {
        peaks.max shouldBe 1
      }
    }
  }
}
