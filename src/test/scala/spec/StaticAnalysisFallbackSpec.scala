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

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }

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

/** H3 — the static-analysis fallback is a SOUNDNESS boundary, it is reachable from ordinary code, and the fix must keep
  * being reached.
  *
  * WHAT THE FALLBACK IS. `staticAnalysisCompiler` executes real READS but never applies WRITES — `TxnSetVarMapValue`
  * merely records the id. So a transaction that writes a key and reads it back sees `None` during analysis while the
  * real run sees `Some(v)` from its own log. A partial continuation on that value (`.get`, a pattern match, a `.head`)
  * therefore throws during analysis and NOWHERE ELSE, `TxnRuntime.commit` catches it, and everything after the throw
  * point goes UNDECLARED. Read-your-own-write followed by a partial operation is ordinary STM code.
  *
  * WHY THAT IS A SOUNDNESS PROBLEM AND NOT A PRECISION ONE. Read-only log entries are never validated
  * (`isDirty = pure(false)`) and hold no lock (`lock = None`), so the scheduler's footprint conflict-avoidance is the
  * ONLY defence against a stale read. An under-approximated footprint used to switch it off — and the TLA+ model
  * (specs/commit/CommitH3.cfg, CommitH3Writer.cfg) shows it breaks serializability in both directions: such a
  * transaction can read what a peer overwrites, and its own undeclared writes can invalidate a correctly-declared
  * peer's reads.
  *
  * THE FIX flags the footprint (`IdFootprint.isUnderApproximated`) and the compatibility relation makes it incompatible
  * with EVERYTHING, itself included. The transaction is therefore serialized against all others and RUNS ALONE, which
  * is what makes its unvalidated reads trivially safe: nothing can change under it.
  *
  * ===========================================================================
  * WHAT THIS SUITE HAS TO ASSERT NOW, AND WHY IT IS NOT THE OBVIOUS THING
  * ===========================================================================
  * Post-fix every arm here would assert `skews shouldBe 0`, and three identical greens do not discriminate. A suite in
  * that state cannot tell "the fallback is working" from "the shape stopped reaching the fallback at all" — and if the
  * library ever stopped throwing on read-your-own-write, this suite, `soak/SerializabilitySoakSpec`'s `UnderDeclare` op
  * and the `underDeclaredConcurrent` benchmark would ALL go silently inert together, since all three are built on this
  * one shape.
  *
  * So the last test asserts the shape still ARRIVES: an under-declaring transaction RUNS ALONE. That is the observable
  * consequence of being incompatible with everything, and it is measurable without reaching into `private[stm]` — run
  * one against peers on disjoint vars and watch whether their execute windows ever overlap. It goes red if the fix is
  * reverted AND it goes red if the shape stops throwing, which is exactly the discrimination the skew counts lost.
  */
class StaticAnalysisFallbackSpec extends AnyFreeSpec with Matchers {

  /** The classic write-skew shape — read one var, write the other — behind a read-your-own-write prelude whose
    * continuation is partial. The prelude is what collapses the declared footprint; the skew is what the collapse then
    * permits.
    */
  private def skewTxn(
    readVar: TxnVar[IO, Int],
    writeVar: TxnVar[IO, Int],
    scratch: TxnVarMap[IO, String, Int],
    key: String
  )(implicit stm: STM[IO]): Txn[Int] =
    for {
      _  <- scratch.set(key, 1) // static analysis records the id but does NOT apply the write
      ov <- scratch.get(key) // static analysis: None (key absent). Real run: Some(1), from the log
      _  <- STM[IO].delay(ov.get) // static analysis: NoSuchElementException -> short-circuit
      v  <- readVar.get // never analysed -> UNDECLARED READ
      _  <- writeVar.set(v + 1) // never analysed -> undeclared write
    } yield v

  /** Control 1 — the bare skew shape, no prelude at all. The scheduler declares `r x, w y` against `r y, w x`, finds
    * raw-id overlap, and serializes the pair. Establishes that the skew shape ITSELF is handled correctly.
    */
  private def declarableSkewTxn(
    readVar: TxnVar[IO, Int],
    writeVar: TxnVar[IO, Int]
  )(implicit stm: STM[IO]): Txn[Int] =
    for {
      v <- readVar.get
      _ <- writeVar.set(v + 1)
    } yield v

  /** Control 2 — THE ISOLATING ARM, and the one that actually earns the verdict.
    *
    * DO NOT DEDUPLICATE THIS AGAINST `skewTxn`. The near-clone is the instrument. The two bodies differ by ONE
    * CHARACTER — `getOrElse` rather than `get` — and holding everything else byte-for-byte identical is the only thing
    * that pins the THROW as the cause. Factor out the shared prelude and the isolating control is gone.
    *
    * Because the continuation is TOTAL, nothing throws, so static analysis runs to completion and declares the whole
    * footprint — `r x, w y, w scratch[k]` against `r y, w x, w scratch[k]` — whereupon raw-id overlap on x and y makes
    * the pair incompatible and the scheduler serializes it.
    *
    * Control 1 alone cannot pin the throw: it removes the prelude as well as the fallback, so it leaves open that the
    * scratch map is somehow responsible — and that is not an idle worry, because both skew transactions insert a fresh
    * key into the same map and therefore resolve that map's structural commitLock through the very fallback H2 is
    * about. Hold the prelude fixed, remove only the throw, and the skew vanishes.
    */
  private def totalContinuationTxn(
    readVar: TxnVar[IO, Int],
    writeVar: TxnVar[IO, Int],
    scratch: TxnVarMap[IO, String, Int],
    key: String
  )(implicit stm: STM[IO]): Txn[Int] =
    for {
      _  <- scratch.set(key, 1)
      ov <- scratch.get(key)
      _  <- STM[IO].delay(ov.getOrElse(0)) // total: analysis completes, footprint is whole
      v  <- readVar.get
      _  <- writeVar.set(v + 1)
    } yield v

  /** From (x=0, y=0) the only serial outcomes are:
    *   - t1 then t2: observations (0, 1), final (x=2, y=1)
    *   - t2 then t1: observations (1, 0), final (x=1, y=2)
    *
    * Write skew — BOTH transactions observing 0 — leaves (x=1, y=1) and is reproducible by no serial order.
    */
  private def isSkew(obs: List[Int], x: Int, y: Int): Boolean =
    obs == List(0, 0) && x == 1 && y == 1

  private def runPair(
    build: (TxnVar[IO, Int], TxnVar[IO, Int], TxnVarMap[IO, String, Int]) => STM[IO] => List[Txn[Int]]
  ): Boolean =
    STM
      .runtime[IO]
      .flatMap { implicit stm =>
        for {
          x       <- TxnVar.of[IO, Int](0)
          y       <- TxnVar.of[IO, Int](0)
          scratch <- TxnVarMap.of[IO, String, Int](Map.empty)
          obs     <- build(x, y, scratch)(stm).parTraverse(_.commit)
          fx      <- x.get.commit
          fy      <- y.get.commit
        } yield isSkew(obs, fx, fy)
      }
      .timeout(30.seconds)
      .unsafeRunSync()

  // -------------------------------------------------------------------
  // Control (expected green — isolates the fallback as the cause)
  // -------------------------------------------------------------------

  "Control 1 — the bare skew shape, accurately declared, is serialized" in {
    val reps = 200
    val skews = (1 to reps).count { _ =>
      runPair { (x, y, _) => implicit stm =>
        List(declarableSkewTxn(x, y), declarableSkewTxn(y, x))
      }
    }
    withClue(
      "the scheduler failed to serialize a pair whose footprints DECLARE the conflict — " +
        "this is not the H3 fallback, it is a conflict-detection regression: "
    ) {
      skews shouldBe 0
    }
  }

  "Control 2 — same transaction, same scratch-map prelude, but a TOTAL continuation: no skew" in {
    val reps = 200
    val skews = (1 to reps).count { _ =>
      runPair { (x, y, scratch) => implicit stm =>
        List(totalContinuationTxn(x, y, scratch, "a"), totalContinuationTxn(y, x, scratch, "b"))
      }
    }
    withClue(
      "this pair differs from the H3 regression below by ONE character — getOrElse instead of get — " +
        "so if it skews, the cause is NOT the static-analysis throw and the H3 diagnosis is wrong: "
    ) {
      skews shouldBe 0
    }
  }

  // -------------------------------------------------------------------
  // H3 regression
  // -------------------------------------------------------------------

  "H3 regression — read-your-own-write can no longer defeat footprint declaration" - {
    /* Two transactions in the r x / w y and r y / w x skew shape, each prefixed
     * with an ordinary read-your-own-write whose continuation is partial. The
     * prelude throws during static analysis (the analyser never applied the
     * write, so the read-back is None); TxnRuntime.commit falls back to the
     * PARTIAL footprint gathered up to that point, and neither x nor y appears
     * in it.
     *
     * BEFORE THE FIX the scheduler trusted that partial footprint as though it
     * were complete, judged the two transactions compatible, and ran them
     * concurrently. Neither was dirty at commit (their write sets are disjoint)
     * and neither held a read lock, so both published having read what the other
     * overwrote. Measured: 198/200 reps skewed on a 12-core host — under
     * contention the anomaly was the DEFAULT outcome, exactly as for H5 pre-fix.
     *
     * THE FIX flags an under-approximated footprint (IdFootprint
     * .isUnderApproximated) and the relation makes it incompatible with
     * EVERYTHING, so such a transaction is serialized against all others and
     * runs alone. Running alone, nothing can change under it, so its unvalidated
     * reads are trivially safe. Post-fix: 0 skews in 1000 reps.
     *
     * If this ever goes red again, the fallback lost its flag somewhere — check
     * that getValidated still copies it through and that every under-approximating
     * handler still calls markUnderApproximated (analyseFootprint in
     * TxnRuntimeContext has two, and TxnCompilerContext has three that swallow
     * silently).
     */
    "no rep skews" in {
      val maxReps = 1000
      val skews = (1 to maxReps).count { _ =>
        runPair { (x, y, scratch) => implicit stm =>
          List(skewTxn(x, y, scratch, "a"), skewTxn(y, x, scratch, "b"))
        }
      }
      withClue(
        s"write skew reappeared in $skews of $maxReps reps — an under-approximated " +
          "footprint is being trusted again (see the comment above): "
      ) {
        skews shouldBe 0
      }
    }

    /* The fix must not cost correctness on the refinement path. An
     * under-approximated transaction runs alone, so it can never be dirty; but a
     * transaction whose footprint diverges WITHOUT a throw still can, and it must
     * still refine and re-run. Here both transactions really write x while one
     * declares it accurately, so they contend on x's commitLock, the loser goes
     * dirty, refines from the ACTUAL log (which is complete, hence unflagged) and
     * re-runs at full concurrency. Modelled in specs/commit/CommitDirty.cfg.
     */
    "an under-declared transaction still commits its effects exactly once" in {
      val reps = 100
      val results = (1 to reps).map { _ =>
        STM
          .runtime[IO]
          .flatMap { implicit stm =>
            for {
              x       <- TxnVar.of[IO, Int](0)
              y       <- TxnVar.of[IO, Int](0)
              scratch <- TxnVarMap.of[IO, String, Int](Map.empty)
              _ <- List(
                     skewTxn(x, y, scratch, "a").commit,
                     skewTxn(y, x, scratch, "b").commit
                   ).parSequence
              fx <- x.get.commit
              fy <- y.get.commit
            } yield (fx, fy)
          }
          .timeout(30.seconds)
          .unsafeRunSync()
      }
      // Serialized either way round, the outcome is one of the two serial orders
      // — never the skewed (1, 1), and never a double-applied effect.
      withClue(s"observed outcomes: ${results.distinct.mkString(", ")}: ") {
        results.foreach(r => Set((2, 1), (1, 2)) should contain(r))
      }
    }
  }

  // -------------------------------------------------------------------
  // Reachability: the shape still arrives at the fallback
  // -------------------------------------------------------------------

  /** Did the under-declaring transaction and an ordinary peer ever hold overlapping execute windows?
    *
    * Both sides publish their presence and then read the other's. Atomics are sequentially consistent, so if the two
    * windows genuinely overlap then at least one of the two reads must observe the other's flag — whichever entered
    * second. Nothing here is sampled or polled, so there is no window for the measurement itself to miss.
    */
  private class OverlapMeter {
    private val underDeclaredActive = new AtomicBoolean(false)
    private val peersActive         = new AtomicInteger(0)
    private val overlaps            = new AtomicInteger(0)

    def underDeclaredEnter(): Unit = {
      underDeclaredActive.set(true)
      if (peersActive.get() > 0) {
        overlaps.incrementAndGet()
        () // incrementAndGet returns the new count; -Werror rejects discarding it
      }
    }

    def underDeclaredExit(): Unit =
      underDeclaredActive.set(false)

    def peerEnter(): Unit = {
      peersActive.incrementAndGet()
      if (underDeclaredActive.get()) {
        overlaps.incrementAndGet()
        ()
      }
    }

    def peerExit(): Unit = {
      peersActive.decrementAndGet()
      ()
    }

    def overlapCount: Int = overlaps.get()
  }

  /** Long enough that a scheduler willing to overlap these two would be caught doing it, short enough that 30 reps stay
    * cheap. It is held open inside the transaction BODY, which is the window Contract C is about.
    */
  private val Hold: FiniteDuration = 25.millis

  /** A value no peer's var starts at, so reading it back proves the read came from the transaction's own LOG. */
  private val RanForReal = 7

  /** The H3 shape with no skew attached: write a scratch key, read it back, apply a partial continuation. Its declared
    * footprint is whatever the walker had before the throw — the scratch key, and nothing else.
    *
    * The meter sits AFTER the throw point, which is why it needs no pass discriminator: the analysis pass never reaches
    * it, so every enter/exit it records belongs to a real, scheduled run.
    */
  private def underDeclaredTxn(
    scratch: TxnVarMap[IO, String, Int],
    key: String,
    meter: OverlapMeter
  )(implicit stm: STM[IO]): Txn[Unit] =
    for {
      _  <- scratch.set(key, 1)
      ov <- scratch.get(key)
      _  <- STM[IO].delay(ov.get) // analysis throws here; the real run gets Some(1)
      _  <- STM[IO].delay(meter.underDeclaredEnter())
      _  <- STM[IO].fromF(IO.sleep(Hold))
      _  <- STM[IO].delay(meter.underDeclaredExit())
    } yield ()

  /** An ordinary, ACCURATELY DECLARED transaction on a var of its own. Nothing it touches is named in the
    * under-declared transaction's footprint, so a scheduler that trusted that footprint would happily run the two
    * together — which is the whole point of the peers.
    *
    * The read-back is a pass discriminator, and it is free: static analysis executes real reads but never applies
    * writes, so `seen` is the var's LIVE value (0) in the analysis pass and the transaction's own logged write in the
    * real run. Nothing throws, so the peer's footprint stays complete and unflagged.
    */
  private def peerTxn(
    peerVar: TxnVar[IO, Int],
    meter: OverlapMeter
  )(implicit stm: STM[IO]): Txn[Unit] =
    for {
      _    <- peerVar.set(RanForReal)
      seen <- peerVar.get
      real = seen == RanForReal
      _ <- STM[IO].delay(if (real) meter.peerEnter() else ())
      _ <- STM[IO].fromF(IO.whenA(real)(IO.sleep(Hold)))
      _ <- STM[IO].delay(if (real) meter.peerExit() else ())
    } yield ()

  private def overlapsInOneRun(peers: Int): Int = {
    val meter = new OverlapMeter

    STM
      .runtime[IO]
      .flatMap { implicit stm =>
        for {
          scratch  <- TxnVarMap.of[IO, String, Int](Map.empty)
          peerVars <- (1 to peers).toList.traverse(_ => TxnVar.of[IO, Int](0))
          _ <- (underDeclaredTxn(scratch, "u", meter).commit ::
                 peerVars.map(v => peerTxn(v, meter).commit)).parSequence
        } yield meter.overlapCount
      }
      .timeout(30.seconds)
      .unsafeRunSync()
  }

  "an under-declaring transaction still reaches the fallback, and therefore runs ALONE" in {
    val reps  = 30
    val peers = 6

    val overlaps = (1 to reps).map(_ => overlapsInOneRun(peers)).sum

    withClue(
      s"the under-declaring transaction shared an execute window with a peer in $overlaps case(s) across $reps reps. " +
        "It is supposed to be incompatible with EVERYTHING and therefore to run alone. Two things produce this, and " +
        "they are worth telling apart. Either the H3 fix stopped working — an under-approximated footprint is being " +
        "trusted again — or the SHAPE stopped reaching it: if read-your-own-write no longer throws during static " +
        "analysis, this transaction is now accurately declared, is genuinely compatible with the peers, and every " +
        "test in this suite (plus SerializabilitySoakSpec's UnderDeclare op and the underDeclaredConcurrent " +
        "benchmark) has quietly stopped testing anything. Check which before touching the assertion: "
    ) {
      overlaps shouldBe 0
    }
  }
}
