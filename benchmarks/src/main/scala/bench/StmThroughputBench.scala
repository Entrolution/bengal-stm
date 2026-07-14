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
package bench

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.IO
import cats.effect.implicits._
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import org.openjdk.jmh.annotations._

import bengal.stm.STM
import bengal.stm.model._
import bengal.stm.syntax.all._

/** What did the correctness fixes cost?
  *
  * H1–H6 changed live scheduler and commit semantics, and three of the fixes carry an obvious price that nobody had
  * measured:
  *
  *   - H3 flags an under-approximated footprint as incompatible with EVERYTHING, so a transaction whose static analysis
  *     threw is now serialized against every other transaction in the system. A throughput cliff on that path, by
  *     design. The question is how steep.
  *   - H5 makes a whole-map READ conflict with any child-entry WRITE. Correct — that was the phantom — but it means
  *     whole-map readers no longer run alongside inserters at all.
  *   - H6 computes the log's footprint on EVERY commit (it used to be forced only on the dirty path) and compares it
  *     against the declared one, aborting and re-running on divergence. A fold over the log entries added to the happy
  *     path, plus new re-executions.
  *
  * These exist to answer that, and they are only meaningful as a COMPARISON. An absolute ops/sec here says very little;
  * the RATIO between two revisions is the whole point. Every fix in the workstream was `private[stm]`, so the same
  * benchmark compiles unchanged against an older library. The annotation defaults below ARE the published protocol,
  * so the recipe needs no flags:
  *
  * {{{
  *   sbt 'benchmarks/Jmh/run .*StmThroughputBench.*'   # after
  *   git checkout <old-rev> -- src/main/scala && sbt clean
  *   sbt 'benchmarks/Jmh/run .*StmThroughputBench.*'   # before
  *   git checkout HEAD -- src/main/scala
  * }}}
  *
  * ===========================================================================
  * DO NOT RUN THESE ON A LAPTOP -- thermal throttling swung IDENTICAL runs by 2.3x and inverted two conclusions here.
  * Read benchmarks/README.md before trusting any number this produces.
  * ===========================================================================
  * Run A -> B -> A, and check that the two A runs AGREE before looking at the result at all.
  *
  * `@OperationsPerInvocation(32)` makes JMH report per-TRANSACTION rather than per-batch throughput on the batched
  * benchmarks, so all of them are directly comparable -- `uncontendedCommit` runs one transaction per invocation and
  * needs no annotation, and ratios against it carry the fan-out caveat in benchmarks/README.md. The `32` is a
  * literal and `Batch` is a separate `final val`: they must track each other.
  * ONE exception: `dataDependentKey` declares 33, because its keySrc bump is a 33rd real commit inside the timed
  * region (it must stay concurrent with the readers -- it is what creates the divergence being measured).
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
// ONE JMH DRIVER THREAD, and the annotation is ADVISORY: `-t N` on the CLI
// silently overrides it and falsifies every scenario label. The key windows
// that make "disjoint" disjoint (and the inserts inserts) are carved by ONE
// driver's counter.getAndAdd; N drivers' windows overlap modulo the keyspace
// and quietly add cross-thread conflicts the labels deny.
@Threads(1)
// THE DEFAULTS ARE THE PUBLISHED PROTOCOL, so a bare `Jmh/run` reproduces the table.
// They did not use to be: the class said -f1 -wi 3 -i 5 while the README documented
// something else, so anyone following the docs measured a different thing from anyone
// following the code.
//
// FIVE FORKS, not one. A single fork measures one JVM's JIT and allocation luck, and
// that is the dominant source of run-to-run variance here -- at -f1 the two halves of an
// A -> B -> A control disagreed by up to 6.5%, which is larger than most of the effects
// being measured. Forks average that out; iterations do not.
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(5)
class StmThroughputBench {

  private final val Batch = 32

  private val Keys    = (0 until 64).map(i => s"k$i").toVector
  private val counter = new AtomicInteger(0)

  // Every var-writing benchmark adds its known per-invocation write count
  // here (one atomic add per invocation, noise against 32 commits), and the
  // teardown cross-checks the vars' sum against it: a lost update must fail
  // the run, not report as a healthy throughput number.
  private val varWrites = new AtomicInteger(0)

  // The second window of the keyspace, pre-seeded into the insert-workload
  // maps so that invocation 0's removes have something to remove; from then
  // on each invocation inserts the window its predecessor removed.
  private val WindowB: Map[String, Int] =
    (Batch until 2 * Batch).map(i => Keys(i) -> 0).toMap

  // NOT implicit at class level: it would collide with the local implicit that
  // `setup` needs while it is still building this very field. Each benchmark
  // brings it into scope for itself.
  private var stmRef: STM[IO] = _
  private var vars: Vector[TxnVar[IO, Int]]        = _
  private var keySrc: TxnVar[IO, Int]              = _
  private var map: TxnVarMap[IO, String, Int]      = _
  private var scratch: TxnVarMap[IO, String, Int]  = _
  private var insMap: TxnVarMap[IO, String, Int]   = _
  private var mapA: TxnVarMap[IO, String, Int]     = _
  private var mapB: TxnVarMap[IO, String, Int]     = _

  // Unjoined scheduler fibers (wake sweeps, unsub cascades) from a finished
  // invocation can still be draining when the timer stops and the next
  // invocation begins — the runtime exposes no quiescence hook to await
  // them. Pure CPU tail, bounded, believed below the documented noise
  // floor; listed in benchmarks/README.md for verification at the next
  // dedicated-box session.
  @Setup(Level.Iteration)
  def setup(): Unit = {
    val prepared = STM
      .runtime[IO]
      .flatMap { implicit s =>
        for {
          vs <- (0 until Batch).toList.traverse(_ => TxnVar.of[IO, Int](0))
          ks <- TxnVar.of[IO, Int](0)
          m  <- TxnVarMap.of[IO, String, Int](Map.empty)
          sc <- TxnVarMap.of[IO, String, Int](Map.empty)
          im <- TxnVarMap.of[IO, String, Int](WindowB)
          ma <- TxnVarMap.of[IO, String, Int](WindowB)
          mb <- TxnVarMap.of[IO, String, Int](WindowB)
        } yield (s, vs.toVector, ks, m, sc, im, ma, mb)
      }
      .unsafeRunSync()

    stmRef = prepared._1
    vars = prepared._2
    keySrc = prepared._3
    map = prepared._4
    scratch = prepared._5
    insMap = prepared._6
    mapA = prepared._7
    mapB = prepared._8
    counter.set(0)
    varWrites.set(0)
  }

  // Sanity assertions at iteration boundaries, off the timed path. This file
  // measures what the correctness fixes COST, and without these it was
  // structurally insensitive to the one defect class it exists around: a
  // fast-but-wrong revision published a legitimate-looking number. One
  // teardown serves every benchmark (shared @State), so each check is
  // universal — a fixture the benchmark never touched passes trivially.
  @TearDown(Level.Iteration)
  def verifyState(): Unit = {
    implicit val s: STM[IO] = stmRef
    val (varSum, keyCounts, scratchSize) = (for {
      vs <- vars.toList.traverse(_.get.commit)
      m  <- map.get.commit
      im <- insMap.get.commit
      ma <- mapA.get.commit
      mb <- mapB.get.commit
      sc <- scratch.get.commit
    } yield (vs.sum, List(m.size, im.size, ma.size, mb.size), sc.size)).unsafeRunSync()

    if (varSum != varWrites.get())
      throw new IllegalStateException(
        s"var-write accounting broken: vars sum to $varSum but ${varWrites.get()} writes committed — lost update?"
      )
    if (keyCounts.exists(_ > Keys.size))
      throw new IllegalStateException(
        s"a map outgrew the keyspace (sizes $keyCounts, bound ${Keys.size}) — a bounded workload is leaking keys"
      )
    if (scratchSize != 0)
      throw new IllegalStateException(
        s"scratch holds $scratchSize keys — underDeclaredConcurrent's same-transaction remove stopped collapsing"
      )
  }

  /** THE BASELINE, and where the H6 fix's cost lives. One transaction, no contention, no conflict: pure per-commit
    * overhead. The H6 coverage check adds a fold over the log entries to exactly this path.
    */
  @Benchmark
  def uncontendedCommit(): Int = {
    implicit val s: STM[IO] = stmRef
    val out = (for {
      v <- vars(0).get
      _ <- vars(0).set(v + 1)
    } yield v).commit.unsafeRunSync()
    val _ = varWrites.addAndGet(1)
    out
  }

  /** Maximum concurrency: every transaction touches a DIFFERENT var, so all footprints are compatible and nothing
    * serializes. Measures the scheduler's admission path at full width.
    */
  @Benchmark
  @OperationsPerInvocation(32)
  def disjointConcurrent(): Int = {
    implicit val s: STM[IO] = stmRef
    val out = (0 until Batch).toList
      .parTraverse { i =>
        (for {
          v <- vars(i).get
          _ <- vars(i).set(v + 1)
        } yield v).commit
      }
      .map(_.sum)
      .unsafeRunSync()
    val _ = varWrites.addAndGet(Batch)
    out
  }

  /** Full conflict: every transaction touches the SAME var, so the scheduler serializes all of them. Measures the
    * dependency-graph and hand-off path rather than raw commit cost.
    */
  @Benchmark
  @OperationsPerInvocation(32)
  def contendedConcurrent(): Int = {
    implicit val s: STM[IO] = stmRef
    val out = (0 until Batch).toList
      .parTraverse { _ =>
        (for {
          v <- vars(0).get
          _ <- vars(0).set(v + 1)
        } yield v).commit
      }
      .map(_.sum)
      .unsafeRunSync()
    val _ = varWrites.addAndGet(Batch)
    out
  }

  /** Concurrent map INSERTS on distinct keys, steady-state, each transaction carrying the multi-owner write set the
    * H2 fix sorts. Window alternation keeps every `set` a REAL insert for the whole iteration: the transaction removes
    * the key its counterpart inserted in the PREVIOUS invocation (setup seeds that window, so invocation 0's removes
    * land), then inserts its own — the write set is therefore {the removed entry's own commitLock, the map's
    * structural commitLock for the insert}: two distinct owners, so `withLock`'s owner-id sort executes in every
    * transaction rather than degenerating to a single lock. The map stays bounded at the keyspace. (The previous shape
    * reset the map empty and windowed keys modulo the pool, so from invocation 2 onward every `set` was an UPDATE on
    * the key's own lock — ~99% of the measured commits at the published protocol exercised a path this benchmark's label
    * denied.)
    */
  @Benchmark
  @OperationsPerInvocation(32)
  def mapWriteConcurrent(): Int = {
    implicit val s: STM[IO] = stmRef
    val base = counter.getAndAdd(Batch)
    (0 until Batch).toList
      .parTraverse { i =>
        val kIns = Keys((base + i) % Keys.size)
        val kDel = Keys((base + i + Batch) % Keys.size)
        (for {
          _ <- insMap.remove(kDel)
          _ <- insMap.set(kIns, i)
        } yield i).commit
      }
      .map(_.sum)
      .unsafeRunSync()
  }

  /** THE H2 TOPOLOGY, cross-map: every transaction inserts a fresh key into TWO maps (with the same remove-previous-
    * window trick per map keeping the inserts real), so its sorted acquisition set holds FOUR owners — both maps'
    * structural locks and both removed entries' own locks. Two transactions inserting fresh keys into two maps is the
    * shape whose acquisition order the H2 fix makes globally total (specs/commit/CommitH2.cfg); before it, this
    * deadlocked. First measured with the harness rework on the dedicated box — see the crossMapInsert row in
    * benchmarks/README.md (no before-column: the pre-H2 library deadlocked here, as above).
    */
  @Benchmark
  @OperationsPerInvocation(32)
  def crossMapInsert(): Int = {
    implicit val s: STM[IO] = stmRef
    val base = counter.getAndAdd(Batch)
    (0 until Batch).toList
      .parTraverse { i =>
        val kIns = Keys((base + i) % Keys.size)
        val kDel = Keys((base + i + Batch) % Keys.size)
        (for {
          _ <- mapA.remove(kDel)
          _ <- mapA.set(kIns, i)
          _ <- mapB.remove(kDel)
          _ <- mapB.set(kIns, i)
        } yield i).commit
      }
      .map(_.sum)
      .unsafeRunSync()
  }

  /** THE H5 FIX'S COST. A whole-map read plus an insert — the phantom idiom. Before the fix these were judged
    * compatible and ran concurrently (which is exactly why they produced phantoms); the relation's third conjunct now
    * makes them conflict, so they serialize. Correct, and not free.
    */
  @Benchmark
  @OperationsPerInvocation(32)
  def wholeMapReadPlusInsert(): Int = {
    implicit val s: STM[IO] = stmRef
    val base = counter.getAndAdd(Batch)
    (0 until Batch).toList
      .parTraverse { i =>
        val k = Keys((base + i) % Keys.size)
        (for {
          m <- map.get
          _ <- map.set(k, m.size)
        } yield m.size).commit
      }
      .map(_.sum)
      .unsafeRunSync()
  }

  /** THE H6 FIX'S COST UNDER DIVERGENCE. The key is computed from a var that a concurrent transaction is bumping, so
    * the static analysis routinely names an entry the run does not touch. Every one of those trips the commit-time
    * coverage check: release, refine, re-run.
    */
  @Benchmark
  // 33, not 32: the keySrc bump below is a 33rd real commit inside the timed
  // region — analysis, scheduling, publish — and it cannot be hoisted out,
  // because running concurrently with the readers is what mutates keySrc
  // under them and creates the divergence this benchmark measures. Crediting
  // it keeps the per-transaction figure comparable with the others.
  @OperationsPerInvocation(33)
  def dataDependentKey(): Int = {
    implicit val s: STM[IO] = stmRef
    val bump = keySrc.modify(_ + 1).commit.as(0)
    val work = (0 until Batch).toList.map { _ =>
      (for {
        n <- keySrc.get
        k = Keys(n % Keys.size)
        ov <- map.get(k)
        _  <- map.set(k, ov.getOrElse(0) + 1)
      } yield n).commit
    }
    (bump :: work).parSequence.map(_.sum).unsafeRunSync()
  }

  /** THE H3 FIX'S CLIFF. A read-your-own-write with a partial continuation throws in the static-analysis pass, so the
    * footprint is under-approximated, flagged, and now incompatible with EVERYTHING — every one of these transactions
    * runs alone. This is the price of soundness on that path, and it is the number to look at hardest.
    */
  @Benchmark
  @OperationsPerInvocation(32)
  def underDeclaredConcurrent(): Int = {
    implicit val s: STM[IO] = stmRef
    val out = (0 until Batch).toList
      .parTraverse { i =>
        val sk = s"s$i"
        (for {
          _  <- scratch.set(sk, 1)
          ov <- scratch.get(sk)
          _  <- STM[IO].delay(ov.get) // throws during analysis, and nowhere else
          v  <- vars(i % Batch).get
          _  <- vars(i % Batch).set(v + 1)
          // The remove is load-bearing and must stay LAST. Committed, it
          // leaves sk absent again, so the NEXT invocation's analysis pass
          // still reads None and ov.get still throws — the flag fires only
          // for keys absent at analysis (the analysis walk itself never gets
          // here: it stops at the ov.get short-circuit — the FLAGGED path,
          // unlike an erratum stop). In the log the entry
          // collapses to read-only (None -> Some -> None), so the commit
          // publishes nothing for sk and scratch stays EMPTY: bounded
          // residency, cliff intact. (The previous shape minted 32 fresh
          // keys per invocation into a map reset only per iteration —
          // unbounded growth whose GC drag biased the published cliff.)
          _  <- scratch.remove(sk)
        } yield v).commit
      }
      .map(_.sum)
      .unsafeRunSync()
    val _ = varWrites.addAndGet(Batch)
    out
  }
}
