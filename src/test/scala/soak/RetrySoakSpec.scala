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

import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.{ AtomicInteger, AtomicIntegerArray }

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.implicits._
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
  * THREE THINGS ARE CHECKED, and they are different kinds of thing. Read the qualifications: two of the three are GROSS
  * failure detectors, and the section below says what they cannot see.
  *
  *   1. LIVENESS. Every transaction completes. A lost wakeup does not corrupt anything — it HANGS — so the failure mode
  *      is a timeout, not an assertion. This catches a wake path that is BROKEN (nothing sweeps, nothing fires, the
  *      predicate is never re-checked). It does NOT catch H1, whose window a running program essentially never lands in.
  *   2. CORRECTNESS. Every item produced is consumed exactly once. A lost update or a double-take would break the
  *      multiset equality, and no amount of parking should be able to. This one is a real assertion and it holds
  *      unconditionally.
  *   3. BOUNDEDNESS. How many times does each transaction body actually run? Parking is EXPECTED here, so unlike the
  *      serializability soak the floor is not 2 — a producer legitimately re-runs each time it is woken. What must not
  *      happen is unbounded churn. The distribution is reported and the maximum is bounded, so a RUNAWAY spin is a red
  *      test rather than a slow one — but the spurious self-wake spin itself needs adversarial scheduling to diverge,
  *      and under a real scheduler it does not.
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
class RetrySoakSpec extends AnyFreeSpec with Matchers with StmSuite {

  private val Capacity    = 4
  private val Producers   = 12
  private val PerProducer = 6
  // One consuming transaction per produced item, so the run only ends when every
  // single one has been taken. If a single wakeup is lost, it never ends.
  private val Total = Producers * PerProducer

  /** This exists to catch a SPIN — unbounded churn — not to pin a tight number. */
  private val MaxBodyRuns = 400

  private case class Outcome(consumed: List[Long], execs: Vector[Int])

  private def runRound(round: Int, maxBufSeen: AtomicInteger): Outcome = {
    // One slot per TRANSACTION (not per producer/consumer): each producer runs
    // PerProducer of them and each consumer several, so counting by id would
    // conflate them and the mean would be meaningless.
    val execs = new AtomicIntegerArray(2 * Total + 1)

    val consumed = withRuntimeSync(90.seconds) { implicit stm =>
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
            // Sampled BEFORE the waitFor, so it fires on every body
            // execution (both passes, and every wake lap) — a thunk placed
            // after the waitFor runs in NEITHER pass while parked. max is
            // pass-invariant and the delay adds no footprint entry, so the
            // parking behaviour under test is unperturbed. Every sample is
            // a committed buffer size as seen at the park-decision point.
            _ <- STM[IO].delay {
                   maxBufSeen.accumulateAndGet(b.size, Math.max(_, _))
                   ()
                 }
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

    Outcome(consumed, (0 until 2 * Total).map(execs.get).toVector)
  }

  "producer/consumer under a bounded buffer" - {

    "every transaction completes, and every item is consumed exactly once" in {
      val rounds         = 8
      val allExecs       = Vector.newBuilder[Int]
      val maxBufSeen     = new AtomicInteger(0)
      var producerReRuns = 0

      (1 to rounds).foreach { round =>
        // A lost wakeup or a lock cycle hangs rather than fails; the timeout
        // fires inside runRound and would otherwise surface as a bare
        // TimeoutException with no reproduction key. The round index IS that
        // key here (the item tags fold it in), so attach it.
        val out =
          try runRound(round, maxBufSeen)
          catch {
            case _: TimeoutException =>
              fail(
                s"round $round timed out — a lost wakeup or a lock cycle; replay with this round index"
              )
          }
        allExecs ++= out.execs
        // Producer transactions occupy ids 0 until Total; floor is 2 (one
        // analysis pass + one run), so > 2 means the producer went around
        // again — a wake lap, or the refinement lap that follows an analysis
        // pass stopped at a full buffer. Every no-wake path to > 2 begins
        // with that full-buffer stop, so either way the full direction was
        // exercised.
        producerReRuns += out.execs.take(Total).count(_ > 2)

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

      info(f"body executions across $rounds rounds: mean $mean%.1f, max $max")
      info(
        s"max buffer size observed by producers: ${maxBufSeen.get} (capacity $Capacity); " +
          s"producer re-runs: $producerReRuns"
      )

      // The capacity contract, observed from inside the producer bodies: all
      // buffer transactions read AND write buf, so fully-declared ones are
      // pairwise incompatible and Contract C serializes them — and a lap
      // whose analysis stopped at the waitFor (declaring read-only) is caught
      // by the commit-time coverage gate, refined, and re-run serialized.
      // Either way a committing producer saw size < Capacity at its
      // serialization point, so no committed size can exceed Capacity. A
      // neutered capacity waitFor lets producers append unconditionally, and
      // this reads past Capacity.
      withClue(
        s"a producer body observed a buffer of ${maxBufSeen.get} items (capacity $Capacity) — the " +
          "producer-side waitFor is not actually bounding the buffer: "
      ) {
        maxBufSeen.get should be <= Capacity
      }

      // The suite's premise is a buffer that parks in BOTH directions, and
      // the consumer/empty direction is implicitly protected (head/tail throw
      // on empty). This is the producer/full direction's evidence: 12
      // producers pushing 72 items through 4 slots park with near-certainty
      // in aggregate, so zero re-runs across all rounds means the park direction
      // was never exercised at all. Aggregated across rounds so one round's
      // scheduling luck cannot flake it.
      withClue(
        "no producer ever re-ran — the full-buffer park direction was never exercised, so this " +
          "suite is not testing producer wakeups: "
      ) {
        producerReRuns should be > 0
      }

      withClue(
        s"a transaction body ran $max times. Parking accounts for a lot of that, but not this much — suspect a " +
          "spurious-wake spin (a parked transaction woken by something that cannot satisfy its predicate, " +
          "re-running and re-parking without bound): "
      ) {
        max should be <= MaxBodyRuns
      }

      // Mean is the moderate-regression tripwire the max bound cannot be
      // (MaxBodyRuns is a spin detector). Observed baseline: mean ~11.3 with
      // floor 2 (analysis + run; legitimate parking re-runs push it up). The
      // bound allows ~4x the observed excess-over-floor (9.3 -> 38) before
      // going red — machine headroom in the HistorySpec tradition, while a
      // wake-storm regression that multiplies re-execution still trips it.
      withClue(f"mean body executions $mean%.1f — re-execution has grown well past the observed baseline: ") {
        mean should be <= 40.0
      }
    }
  }
}
