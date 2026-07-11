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

/** H3 — the static-analysis fallback is a SOUNDNESS boundary, and it is reachable from ordinary code.
  *
  * The TLA+ model (specs/commit/CommitH3.cfg, CommitH3Writer.cfg) shows that a transaction scheduled on an
  * under-approximated footprint breaks serializability in both directions: it can read what a peer overwrites, and its
  * own undeclared writes can invalidate a correctly-declared peer's reads. Neither is caught at commit — read-only log
  * entries are never validated (isDirty = pure(false)) and hold no lock (lock = None), so the scheduler's footprint
  * conflict-avoidance is the ONLY defence, and an under-approximated footprint switches it off.
  *
  * This suite answers the question the model cannot: is that path REACHABLE without contrivance?
  *
  * It is. staticAnalysisCompiler executes real READS but never applies WRITES — TxnSetVarMapValue merely records the
  * id. So a transaction that writes a key and reads it back sees None during analysis while the real run sees Some(v)
  * from its own log. A partial continuation on that value (`.get`, a pattern match, a `.head`) therefore throws during
  * analysis and not at runtime, TxnRuntime.commit catches it, and everything after the throw point goes UNDECLARED.
  *
  * Read-your-own-write followed by a partial operation is ordinary STM code.
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
    * Byte-for-byte `skewTxn`, including the read-your-own-write on the scratch map, except that the continuation is
    * TOTAL (`getOrElse` rather than `.get`). Nothing throws, so static analysis runs to completion and declares the
    * whole footprint — `r x, w y, w scratch[k]` against `r y, w x, w scratch[k]` — whereupon raw-id overlap on x and y
    * makes the pair incompatible and the scheduler serializes it.
    *
    * This is what pins the THROW as the cause. Control 1 alone cannot: it removes the prelude as well as the fallback,
    * so it leaves open that the scratch map is somehow responsible — and that is not an idle worry, because both skew
    * transactions insert a fresh key into the same map and therefore resolve that map's structural commitLock through
    * the very fallback H2 is about. Hold the prelude fixed, remove only the throw, and the skew vanishes.
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
      "this pair differs from the pinned defect below by ONE character — getOrElse instead of get — " +
        "so if it skews, the cause is NOT the static-analysis throw and the H3 diagnosis is wrong: "
    ) {
      skews shouldBe 0
    }
  }

  // -------------------------------------------------------------------
  // H3 regression (was a pinned defect until 2026-07-11)
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
     * handler still calls markUnderApproximated (TxnRuntime.commit has two, and
     * TxnCompilerContext has three that swallow silently).
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
}
