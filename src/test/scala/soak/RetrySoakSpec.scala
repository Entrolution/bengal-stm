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
package soak

import java.util.concurrent.atomic.AtomicIntegerArray

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

/** The retry / park / wake machinery, under load.
  *
  * THIS PATH HAD NO RANDOMIZED COVERAGE AT ALL. `SerializabilityOracleSpec` excludes `waitFor` workloads by design, and
  * so does `SerializabilitySoakSpec` — every generated transaction there runs to completion. Yet this is where H1 lived
  * (a lost wakeup that hung the caller forever) and where the spurious self-wake spin lived (a parked transaction woken
  * by its own submission, re-running and re-parking without bound). Both were found by the model, and neither had a
  * behavioural test that would have caught a regression.
  *
  * A BOUNDED BUFFER is the right shape for this: it parks in BOTH directions. Producers park when it is full,
  * consumers park when it is empty, and with a small capacity and many of each, almost every transaction parks at least
  * once. That drives the whole machine — `TxnResultRetry`, `submitTxnForRetry`'s two park-time checks, the retry map,
  * `checkRetryQueue`'s wake sweeps, and the fresh incarnation each wake builds.
  *
  * THREE THINGS ARE CHECKED, and they are different kinds of thing:
  *
  *   1. LIVENESS. Every transaction completes. A lost wakeup does not corrupt anything — it HANGS — so the failure mode
  *      is a timeout, not an assertion. This is the H1 class.
  *   2. CORRECTNESS. Every item produced is consumed exactly once. A lost update or a double-take would break the
  *      multiset equality, and no amount of parking should be able to.
  *   3. BOUNDEDNESS. How many times does each transaction body actually run? Parking is EXPECTED here, so unlike the
  *      serializability soak the floor is not 2 — a producer legitimately re-runs each time it is woken. What must not
  *      happen is unbounded churn, which is what the spurious-wake spin was. The distribution is reported and the
  *      maximum is bounded, so a spin is a red test rather than a slow one.
  *
  * ===========================================================================
  * WHAT THIS SUITE CANNOT DO, MEASURED RATHER THAN ASSUMED
  * ===========================================================================
  * Both retry defects were reverted in turn and the soak re-run. NEITHER IS CAUGHT:
  *
  *   H1 (lost wakeup)                reverted -> soak stays GREEN
  *   spurious self-wake spin         reverted -> soak stays GREEN
  *
  * That is stated here rather than papered over, and it is worth understanding, because it is the strongest evidence in
  * this project for why the model was worth building.
  *
  * H1 needs the conflictor's ENTIRE submit-and-sweep to complete inside the window between the parker leaving
  * `activeTransactions` (registerCompletion) and the parker taking the retry semaphore — about two microseconds of
  * `triggerUnsub.start` plus a `hasDownstream` read. Miss it early and the conflictor's submit scan SEES the parker
  * still active, subscribes to it, and `hasDownstream` then makes the retry dispatch resubmit instead of park: no park,
  * no bug. Miss it late and the conflictor's sweep blocks on the retry semaphore the parker is holding, finds it
  * parked, and wakes it: the rescue path, working as designed. A running program essentially never lands between those
  * two, and no amount of repetition or delay-sweeping made it. (In PRODUCTION it is reachable — a GC pause or a
  * preemption widens that gap to milliseconds — which is exactly why it is a real bug and not a curiosity.)
  *
  * The self-wake spin is the same story: it needs every sweep to be repeatedly delayed past a re-park, which
  * adversarial scheduling produces and a real scheduler does not.
  *
  * So: TLC found both, and behavioural testing cannot. What this suite DOES give is regression cover for the machinery
  * as a whole — wakes fire, nothing is lost or double-taken, and churn stays bounded — which would catch a gross
  * breakage. It is not a substitute for `specs/scheduler/SchedulerRetry.cfg` and its negative controls, and it must not
  * be read as one.
  */
class RetrySoakSpec extends AnyFreeSpec with Matchers {

  private val Capacity    = 4
  private val Producers   = 12
  private val PerProducer = 6
  // One consuming transaction per produced item, so the run only ends when every
  // single one has been taken. If a single wakeup is lost, it never ends.
  private val Total = Producers * PerProducer

  /** Generous, because parking is expected and a woken transaction legitimately re-runs. This exists to catch a SPIN —
    * unbounded churn — not to pin a tight number.
    */
  private val MaxBodyRuns = 400

  private case class Outcome(consumed: List[Long], execs: Vector[Int])

  private def runRound(round: Int): Outcome = {
    // One slot per TRANSACTION (not per producer/consumer): each producer runs
    // PerProducer of them and each consumer several, so counting by id would
    // conflate them and the mean would be meaningless.
    val execs = new AtomicIntegerArray(2 * Total + 1)

    STM
      .runtime[IO]
      .flatMap { implicit stm =>
        val buffer = TxnVar.of[IO, Vector[Long]](Vector.empty)

        buffer.flatMap { buf =>
          def count(id: Int): Txn[Unit] =
            STM[IO].delay {
              execs.incrementAndGet(id)
              ()
            }

          // Parks while the buffer is FULL. The read of `buf` puts it in the
          // footprint, which is what lets a consumer's commit wake this.
          def produce(id: Int, tag: Long): Txn[Unit] =
            for {
              _ <- count(id)
              b <- buf.get
              _ <- STM[IO].waitFor(b.size < Capacity)
              _ <- buf.set(b :+ tag)
            } yield ()

          // Parks while the buffer is EMPTY.
          def consume(id: Int): Txn[Long] =
            for {
              _ <- count(id)
              b <- buf.get
              _ <- STM[IO].waitFor(b.nonEmpty)
              _ <- buf.set(b.tail)
            } yield b.head

          val producers = (0 until Producers).toList.flatMap { p =>
            (0 until PerProducer).toList.map { k =>
              val txnIdx = (p * PerProducer) + k
              produce(txnIdx, (round.toLong * 100000L) + (p.toLong * 100L) + k.toLong).commit
            }
          }

          // Exactly as many takes as there are items, so the run only ends when
          // every single one has been consumed. If one wakeup is lost, this
          // never finishes.
          val consumers = (0 until Total).toList.map { i =>
            consume(Total + i).commit
          }

          for {
            fibs <- (producers ++ consumers).parTraverse(_.start)
            got  <- fibs.traverse(_.joinWithNever)
          } yield got.collect { case l: Long => l }
        }
      }
      // A LOST WAKEUP HANGS. There is nothing to assert against — the parked
      // transaction simply never runs again — so the timeout IS the assertion.
      .timeout(90.seconds)
      .unsafeRunSync()
      .pipe(consumed => Outcome(consumed, (0 until 2 * Total).map(execs.get).toVector))
  }

  implicit private class PipeOps[A](private val a: A) {
    def pipe[B](f: A => B): B = f(a)
  }

  "producer/consumer under a bounded buffer" - {

    /* A lost wakeup does not corrupt anything, it HANGS, so the timeout is the
     * assertion here rather than any `shouldBe`. Note the honest limit recorded
     * in the class comment: this catches a GROSS wake failure, not H1 itself.
     */
    "every transaction completes, and every item is consumed exactly once" in {
      val rounds   = 8
      val allExecs = Vector.newBuilder[Int]

      (1 to rounds).foreach { round =>
        val out = runRound(round)
        allExecs ++= out.execs

        val expected = (0 until Producers).flatMap { p =>
          (0 until PerProducer).map(k => (round.toLong * 100000L) + (p.toLong * 100L) + k.toLong)
        }.toList

        withClue(s"round $round: the buffer lost or duplicated an item — parking must not affect what commits: ") {
          out.consumed.sorted shouldBe expected.sorted
        }
      }

      val execs = allExecs.result()
      val mean  = execs.sum.toDouble / execs.size
      val max   = execs.max

      // Parking is EXPECTED here, so the floor is not 2: a woken transaction
      // legitimately re-runs. What is being watched for is unbounded churn.
      info(
        f"body executions across $rounds rounds: mean $mean%.1f, max $max " +
          "(parking is expected — a woken transaction re-runs, so this is well above the uncontended floor of 2)"
      )

      withClue(
        s"a transaction body ran $max times. Parking accounts for a lot of that, but not this much — suspect a " +
          "spurious-wake spin (a parked transaction woken by something that cannot satisfy its predicate, " +
          "re-running and re-parking without bound): "
      ) {
        max should be <= MaxBodyRuns
      }
    }
  }
}
