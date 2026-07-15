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
import java.util.concurrent.atomic.AtomicIntegerArray

import scala.concurrent.duration._
import scala.util.Random

import cats.effect.IO
import cats.effect.implicits._
import cats.syntax.all._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import bengal.stm.STM
import bengal.stm.model._
import bengal.stm.syntax.all._
import soak.History._

/** The serializability soak: many concurrent transactions, the FULL operation surface, checked by cycle detection.
  *
  * WHAT THIS EXISTS TO COVER. `SerializabilityOracleSpec` checks outcomes against every serial permutation, which is
  * exact but O(n!) — it generates two to four transactions and only point operations. Every defect this project found
  * (H1–H6) lived outside that window. This suite trades exactness for reach: cycle detection in the dependency graph
  * (see `History`) is O(V+E), so the workload can be hundreds of transactions deep and carry the operations the
  * generator omits.
  *
  * THE OPERATIONS, AND THE DEFECT EACH ONE REACHES:
  *
  *   - read-only reads — the anti-dependency edges that make anomalies visible at all. A read-modify-write puts its
  *     read into the footprint, so the scheduler serializes it; EVERY bug found here was an UNPROTECTED read, which is
  *     exactly why a generator whose every operation reads what it writes could never find one.
  *   - whole-map reads (H5) — the phantom idiom, excluded from the generated oracle suite.
  *   - new-key inserts (H5, H2) — every map key starts absent, so every first append takes the map-lock fallback.
  *   - transactions spanning TWO maps (H2) — the lock-ordering path.
  *   - data-dependent keys (H6) — a key computed from a value read before the transaction was scheduled.
  *   - under-declared transactions (H3) — a read-your-own-write with a partial continuation, which throws in the
  *     static-analysis pass and nowhere else.
  *
  * NOT COVERED, deliberately: deletes and whole-map WRITES. Both destroy a key's list, and the append-only history is
  * what makes the write order recoverable for free. They need their own instrument.
  *
  * ===========================================================================
  * WHAT THIS SUITE ACTUALLY DETECTS — measured, not assumed
  * ===========================================================================
  * A soak that has never been watched go red is a rubber stamp. Each fix was reverted in turn and the soak re-run:
  *
  *   H5 (phantom)           reverted -> CAUGHT, correctly classified G2
  *   H3 (under-declaration) reverted -> CAUGHT, correctly classified G2
  *   H6 (data-dependent)    reverted -> ***NOT CAUGHT***
  *
  * THE H6 GAP IS REAL, AND IS STATED HERE RATHER THAN ENGINEERED AWAY. Disabling the H6 coverage check leaves this
  * suite GREEN. It does not cover H6, and nobody reading a green run should believe otherwise. `DataDependentFootprintSpec`
  * covers H6 deterministically; this suite does not.
  *
  * Why it escapes is worth understanding. The DIVERGENCE is rampant — the meter below reports that around 80% of
  * data-dependent operations name a different entry in the analysis pass than the run actually touches. But a
  * divergence is not yet an ANOMALY. To become one it needs a three-way coincidence: the reader's run-time keys must
  * land on exactly the entries some concurrent transaction is writing; the two must be compatible on everything ELSE
  * they touch (with several operations each they usually are not, so the scheduler serializes them and the chance is
  * gone); and the reader's two reads must straddle that transaction's publish, which is a `parTraverse` and so is
  * tearable but brief. Random search does not assemble that. The H6 probe had to ENGINEER every one of those
  * conditions — which, in hindsight, was the clue.
  *
  * So H6 is a real defect but a rare one to hit by chance, and its fix is insurance rather than a fire.
  *
  * INSTRUMENTATION. Each transaction counts how many times its body actually EXECUTES — once in the static-analysis
  * pass plus once per admitted run — so a clean, uncontended transaction is 2. Anything above that is re-execution:
  * dirty retries, H6 coverage aborts, spurious wakeups. This matters because a transaction body re-runs in full, so any
  * effect a user embeds in it re-runs too, and the H6 fix ADDED a reason to abort. The numbers are reported on every
  * run and bounded, so a livelock shows up as a red test rather than as a slow one.
  */
private object DivergenceMeter {
  private val seen = new java.util.concurrent.ConcurrentHashMap[(Int, Int, Int), java.util.Set[String]]()

  /** Called from inside the transaction body, so it fires once in the static-analysis pass and once per admitted run.
    * If the passes computed DIFFERENT keys from the same source value, the declared footprint named an entry the
    * transaction did not touch — H6's divergence, measured rather than assumed.
    *
    * The ROUND is part of the key. Every round rebuilds txnIds 1..N and opIdx 0..k with a different seed, so without it
    * the same slot accumulates keys across rounds and cross-round seed churn counts as "divergence" — the meter then
    * inflates with round count while its denominator stays capped at one round's worth of slots. Round-scoped keys make
    * each bucket exactly one execution instance's observations (analysis + every admitted lap).
    */
  def observe(round: Int, txn: Int, op: Int, key: String): Unit = {
    seen
      .computeIfAbsent((round, txn, op), _ => java.util.concurrent.ConcurrentHashMap.newKeySet[String]())
      .add(key)
    ()
  }

  def diverged: Int = {
    var n = 0
    seen.forEach((_, ks) => if (ks.size() > 1) n += 1)
    n
  }

  def observed: Int = seen.size()
}

class SerializabilitySoakSpec extends AnyFreeSpec with Matchers with StmSuite {

  private val NumVars = 6
  private val NumMaps = 2
  private val KeyPool = Vector("k0", "k1", "k2", "k3", "k4", "k5")

  /** Long mode: `-Dbengal.soak=long`. The default is sized to run in the normal test matrix. */
  private val longMode = sys.props.get("bengal.soak").contains("long")

  private val rounds     = if (longMode) 40 else 12
  private val txnsPerRun = if (longMode) 400 else 80
  private val opsPerTxn  = 6

  // ---------------------------------------------------------------------------
  // Workload
  // ---------------------------------------------------------------------------

  sealed private trait Op
  private case class ReadVar(i: Int) extends Op
  private case class AppendVar(i: Int) extends Op
  private case class ReadKey(m: Int, k: String) extends Op
  private case class AppendKey(m: Int, k: String) extends Op
  private case class ReadWholeMap(m: Int) extends Op
  private case class DataDepAppend(src: Int, m: Int) extends Op
  private case class DataDepRead(src: Int, m: Int) extends Op
  private case class Transfer(m: Int, i: Int) extends Op
  private case object UnderDeclare extends Op

  private def genOps(rnd: Random): List[Op] =
    List.fill(opsPerTxn) {
      rnd.nextInt(100) match {
        // The mix is load-bearing and was tuned against the negative controls,
        // not by taste: DILUTING ReadWholeMap silently stopped the suite from
        // catching a reverted H5, which is exactly the coverage loss the
        // controls exist to catch. Change these weights and re-run the controls.
        case n if n < 16 => ReadVar(rnd.nextInt(NumVars))
        case n if n < 30 => AppendVar(rnd.nextInt(NumVars))
        case n if n < 40 => ReadKey(rnd.nextInt(NumMaps), KeyPool(rnd.nextInt(KeyPool.size)))
        case n if n < 58 => AppendKey(rnd.nextInt(NumMaps), KeyPool(rnd.nextInt(KeyPool.size)))
        case n if n < 74 => ReadWholeMap(rnd.nextInt(NumMaps)) // the H5 detector
        case n if n < 80 => DataDepAppend(rnd.nextInt(NumVars), rnd.nextInt(NumMaps))
        case n if n < 88 => DataDepRead(rnd.nextInt(NumVars), rnd.nextInt(NumMaps))
        case n if n < 96 => Transfer(rnd.nextInt(NumMaps), rnd.nextInt(KeyPool.size))
        case _ => UnderDeclare
      }
    }

  // ---------------------------------------------------------------------------
  // Building the transaction, and recording what it observed
  // ---------------------------------------------------------------------------

  private def toTxn(
    round: Int,
    txnId: TxnId,
    ops: List[Op],
    vars: List[TxnVar[IO, Vector[Tag]]],
    maps: List[TxnVarMap[IO, String, Vector[Tag]]],
    execs: AtomicIntegerArray
  )(implicit stm: STM[IO]): Txn[TxnRecord] = {

    // Runs in the static-analysis pass AND in every admitted log run, which is
    // precisely the count we want: how many times does this body really execute?
    val countExecution: Txn[Unit] =
      STM[IO].delay {
        execs.incrementAndGet(txnId)
        () // incrementAndGet returns the new count; -Werror rejects discarding it
      }

    val empty = TxnRecord(txnId, Map.empty, Map.empty)

    val body = ops.zipWithIndex.foldLeft(STM[IO].pure(empty)) { case (acc, (op, opIdx)) =>
      acc.flatMap { rec =>
        val tag = tagFor(txnId, opIdx)
        op match {

          case ReadVar(i) =>
            vars(i).get.map(v => rec.copy(reads = rec.reads + ((VarKey(i): Key) -> v)))

          case AppendVar(i) =>
            for {
              cur <- vars(i).get
              _   <- vars(i).set(cur :+ tag)
            } yield rec.copy(appends = rec.appends + ((VarKey(i): Key) -> tag))

          case ReadKey(m, k) =>
            maps(m).get(k).map { ov =>
              rec.copy(reads = rec.reads + ((EntryKey(m, k): Key) -> ov.getOrElse(Vector.empty)))
            }

          case AppendKey(m, k) =>
            // Every key starts ABSENT, so the first append to it is a new-key
            // insert — the map-lock fallback, and H2's path.
            for {
              cur <- maps(m).get(k)
              _   <- maps(m).set(k, cur.getOrElse(Vector.empty) :+ tag)
            } yield rec.copy(appends = rec.appends + ((EntryKey(m, k): Key) -> tag))

          case ReadWholeMap(m) =>
            // H5's idiom. Record an observation for EVERY key in the pool,
            // including an EMPTY one for keys that are absent — that empty
            // observation is what makes a phantom visible to the checker.
            maps(m).get.map { mm =>
              val observed: Map[Key, Vector[Tag]] =
                KeyPool.map(k => (EntryKey(m, k): Key) -> mm.getOrElse(k, Vector.empty)).toMap
              rec.copy(reads = rec.reads ++ observed)
            }

          case DataDepAppend(src, m) =>
            // H6's path: the key is computed from a value READ, so the static
            // analysis names whatever key the source held when IT ran — which
            // need not be the key the real run touches.
            //
            // THE PAUSE WIDENS THE WINDOW, and it does not manufacture one. The
            // window H6 lives in is [analysis reads the source, log run reads it
            // again]. TxnRuntime.commit runs the walker BEFORE submitTxn, so that
            // window is real; its WIDTH in production is whatever the scheduler, a
            // dependency wait, a GC pause or thread contention makes it, which is
            // routinely milliseconds. A test that only ever samples the microsecond
            // case under-explores a window the system genuinely has.
            //
            // It does not buy H6 coverage: this suite does not catch H6 with or
            // without the pause (see the headline table). What it buys is the
            // divergence the meter reports, which is what keeps the coverage-abort
            // path — and the re-execution bound below — genuinely exercised.
            for {
              v <- vars(src).get
              _ <- STM[IO].fromF(IO.sleep(2.millis))
              k = KeyPool(v.length % KeyPool.size)
              _   <- STM[IO].delay(DivergenceMeter.observe(round, txnId, opIdx, k))
              cur <- maps(m).get(k)
              _   <- maps(m).set(k, cur.getOrElse(Vector.empty) :+ tag)
            } yield rec.copy(
              reads   = rec.reads + ((VarKey(src): Key) -> v),
              appends = rec.appends + ((EntryKey(m, k): Key) -> tag)
            )

          case DataDepRead(src, m) =>
            // H6 from the READER's side, and this is the half that actually
            // exposes it. A data-dependent WRITE is still protected against
            // whole-map readers, because the wrong key it declares has the SAME
            // PARENT as the right one and the relation's parent rule catches it
            // anyway. What is NOT protected is a data-dependent READ: its declared
            // footprint names the wrong entry, so a writer of the entry it REALLY
            // reads is judged compatible with it and runs concurrently.
            //
            // Hence the ADJACENT PAIR of entries, both data-dependent and both
            // therefore undeclared. One unprotected read is only an rw edge; a
            // cycle needs TWO, straddling another transaction's publish — and that
            // publish is not atomic (log.commit is a parTraverse over the entries).
            // The pause between the reads is the straddle window. That is a
            // G-single cycle, and it is exactly the shape of the H6 probe.
            for {
              v <- vars(src).get
              _ <- STM[IO].fromF(IO.sleep(2.millis))
              i  = v.length % KeyPool.size
              k1 = KeyPool(i)
              k2 = KeyPool((i + 1) % KeyPool.size)
              // ONE representative key: k1 and k2 are adjacent-distinct by
              // construction, so recording both under one instance key would
              // make every DataDepRead read as "diverged" and re-break the
              // meter. k1 alone captures whether the passes disagreed.
              _  <- STM[IO].delay(DivergenceMeter.observe(round, txnId, opIdx, k1))
              c1 <- maps(m).get(k1)
              _  <- STM[IO].fromF(IO.sleep(2.millis))
              c2 <- maps(m).get(k2)
            } yield rec.copy(
              reads = rec.reads
                + ((VarKey(src): Key)     -> v)
                + ((EntryKey(m, k1): Key) -> c1.getOrElse(Vector.empty))
                + ((EntryKey(m, k2): Key) -> c2.getOrElse(Vector.empty))
            )

          case Transfer(m, i) =>
            // Appends to an ADJACENT PAIR of entries in one transaction -- an
            // ordinary "move something from here to there". Accurately declared,
            // so it is correct in itself; it is the counterpart a data-dependent
            // reader can tear.
            //
            // The +500000L keeps the second append a distinct tag attributed to
            // the SAME writer: History.writerOf is tag / TagStride (1,000,000),
            // so seq + offset must stay in [opsPerTxn, TagStride) -- above the
            // sibling tagFor slots, below the next writer's id.
            val k1 = KeyPool(i)
            val k2 = KeyPool((i + 1) % KeyPool.size)
            for {
              c1 <- maps(m).get(k1)
              _  <- maps(m).set(k1, c1.getOrElse(Vector.empty) :+ tag)
              c2 <- maps(m).get(k2)
              _  <- maps(m).set(k2, c2.getOrElse(Vector.empty) :+ (tag + 500000L))
            } yield rec.copy(
              appends = rec.appends
                + ((EntryKey(m, k1): Key) -> tag)
                + ((EntryKey(m, k2): Key) -> (tag + 500000L))
            )

          case UnderDeclare =>
            // H3's path. Write a scratch key, read it back, and apply a PARTIAL
            // continuation to the result. The analysis pass never applied the
            // write, so it reads None and `.get` throws — there, and nowhere
            // else. Everything after this point goes undeclared, which the fix
            // must flag and serialize.
            val scratch = s"scratch-$txnId"
            for {
              _  <- maps(0).set(scratch, Vector(tag))
              ov <- maps(0).get(scratch)
              _  <- STM[IO].delay(ov.get)
            } yield rec.copy(appends = rec.appends + ((EntryKey(0, scratch): Key) -> tag))
        }
      }
    }

    // A key this transaction APPENDED to is ordered by the write chain, and its
    // implicit read would only duplicate those edges. `reads` means read-ONLY.
    countExecution.flatMap(_ => body).map(r => r.copy(reads = r.reads -- r.appends.keySet))
  }

  // ---------------------------------------------------------------------------
  // Running one round
  // ---------------------------------------------------------------------------

  private case class RoundResult(
    finalState: Map[Key, Vector[Tag]],
    records: List[TxnRecord],
    executions: Vector[Int]
  )

  private def runRound(round: Int, seed: Long): RoundResult = {
    val rnd      = new Random(seed)
    val workload = List.fill(txnsPerRun)(genOps(rnd))
    val execs    = new AtomicIntegerArray(txnsPerRun + 1)

    // A lost wakeup or a lock cycle would hang rather than fail; the timeout
    // turns either into a TimeoutException, and the soak loop converts THAT
    // into a red test carrying the round and seed. A raw TimeoutException
    // bypasses withClue (ScalaTest only decorates its own exceptions), so
    // it takes an explicit catch, which lives in the loop beside the other
    // keyed assertions.
    withRuntimeSync(if (longMode) 5.minutes else 90.seconds) { implicit stm =>
      for {
        vars <- (0 until NumVars).toList.traverse(_ => TxnVar.of[IO, Vector[Tag]](Vector.empty))
        maps <- (0 until NumMaps).toList.traverse(_ => TxnVarMap.of[IO, String, Vector[Tag]](Map.empty))

        records <- workload.zipWithIndex.parTraverse { case (ops, i) =>
                     toTxn(round, i + 1, ops, vars, maps, execs).commit
                   }

        finalVars <- vars.zipWithIndex.traverse { case (v, i) =>
                       v.get.commit.map(l => (VarKey(i): Key) -> l)
                     }
        finalMaps <- maps.zipWithIndex.traverse { case (m, i) =>
                       m.get.commit.map { mm =>
                         mm.toList.map { case (k, l) => (EntryKey(i, k): Key) -> l }
                       }
                     }
      } yield RoundResult(
        finalState = (finalVars ++ finalMaps.flatten).toMap,
        records    = records,
        executions = (1 to txnsPerRun).map(execs.get).toVector
      )
    }
  }

  // ---------------------------------------------------------------------------
  // The soak
  // ---------------------------------------------------------------------------

  "concurrent workloads over the full operation surface are serializable" in {
    val allExecs = Vector.newBuilder[Int]

    (1 to rounds).foreach { round =>
      val seed = 0xbe9a1L * round
      val result =
        try runRound(round, seed)
        catch {
          case _: TimeoutException =>
            fail(
              s"round $round (seed $seed) timed out — a lost wakeup or a lock cycle; replay with this seed"
            )
        }
      allExecs ++= result.executions

      val violations = History.check(result.finalState, result.records)

      withClue(
        s"round $round (seed $seed, $txnsPerRun txns x $opsPerTxn ops) produced a history that no serial order " +
          s"explains:\n${violations.map("  " + _.explain).mkString("\n")}\n"
      ) {
        violations shouldBe empty
      }
    }

    // ---- instrumentation ----
    //
    // A body executes ONCE in the static-analysis pass plus once per admitted
    // run, so 2 is the floor and anything above it is re-execution: a dirty
    // retry, an H6 coverage abort, or a spurious wakeup. This is user-visible —
    // effects embedded in a transaction re-run with it — and the H6 fix added a
    // reason to abort, so it is worth watching rather than assuming.
    val execs = allExecs.result()
    val mean  = execs.sum.toDouble / execs.size
    val max   = execs.max
    val reRun = execs.count(_ > 2)

    info(
      s"data-dependent footprint DIVERGENCE: ${DivergenceMeter.diverged} of ${DivergenceMeter.observed} " +
        "data-dependent operations named a different entry in the static-analysis pass than the run actually " +
        "touched. Every one of those is scheduled on a footprint that does not describe it, and is caught only by " +
        "the H6 commit-time coverage check."
    )
    info(
      f"body executions: mean $mean%.2f (floor is 2: one analysis + one run), max $max, " +
        f"${100.0 * reRun / execs.size}%.1f%% of transactions re-executed at least once " +
        s"[${execs.size} transactions over $rounds rounds]"
    )

    withClue(
      s"a transaction body executed $max times. The floor is 2, so this is a retry storm — most likely a livelock " +
        "on the H6 coverage-abort path (refine, re-run, diverge again) or a spurious-wakeup spin: "
    ) {
      max should be <= 25
    }

    // Mean is the moderate-regression tripwire the max bound cannot be (25 is
    // a storm detector). Observed baseline: mean ~2.32 against the floor of 2
    // — most transactions run exactly analysis + once. The bound allows ~8x
    // the observed excess-over-floor (0.32 -> 2.5) before going red; stated
    // in excess terms because the floor dominates the raw mean, so "4.5"
    // sounds tighter than it is. Machine headroom in the HistorySpec
    // tradition, while a coverage-abort or spurious-wake regression that
    // multiplies re-execution across the population still trips it.
    withClue(f"mean body executions $mean%.2f — population-wide re-execution has grown well past baseline: ") {
      mean should be <= 4.5
    }
  }
}
