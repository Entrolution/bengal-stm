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
  * benchmark compiles unchanged against an older library. These are the flags the published table was measured with:
  *
  * {{{
  *   sbt 'benchmarks/Jmh/run -f1 -wi 8 -i 12 .*StmThroughputBench.*'   # after
  *   git checkout <old-rev> -- src/main/scala && sbt clean
  *   sbt 'benchmarks/Jmh/run -f1 -wi 8 -i 12 .*StmThroughputBench.*'   # before
  *   git checkout HEAD -- src/main/scala
  * }}}
  *
  * ===========================================================================
  * DO NOT RUN THESE ON A LAPTOP -- thermal throttling swung IDENTICAL runs by 2.3x and inverted two conclusions here.
  * Read benchmarks/README.md before trusting any number this produces.
  * ===========================================================================
  * Run A -> B -> A, and check that the two A runs AGREE before looking at the result at all.
  *
  * `@OperationsPerInvocation(32)` makes JMH report per-TRANSACTION rather than per-batch throughput on the six batched
  * benchmarks, so all seven are directly comparable -- `uncontendedCommit` runs one transaction per invocation and
  * needs no annotation. The `32` is a literal and `Batch` is a separate `final val`: they must track each other.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class StmThroughputBench {

  private final val Batch = 32

  private val Keys    = (0 until 64).map(i => s"k$i").toVector
  private val counter = new AtomicInteger(0)

  // NOT implicit at class level: it would collide with the local implicit that
  // `setup` needs while it is still building this very field. Each benchmark
  // brings it into scope for itself.
  private var stmRef: STM[IO] = _
  private var vars: Vector[TxnVar[IO, Int]]        = _
  private var keySrc: TxnVar[IO, Int]              = _
  private var map: TxnVarMap[IO, String, Int]      = _
  private var scratch: TxnVarMap[IO, String, Int]  = _

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
        } yield (s, vs.toVector, ks, m, sc)
      }
      .unsafeRunSync()

    stmRef = prepared._1
    vars = prepared._2
    keySrc = prepared._3
    map = prepared._4
    scratch = prepared._5
    counter.set(0)
  }

  /** THE BASELINE, and where the H6 fix's cost lives. One transaction, no contention, no conflict: pure per-commit
    * overhead. The H6 coverage check adds a fold over the log entries to exactly this path.
    */
  @Benchmark
  def uncontendedCommit(): Int = {
    implicit val s: STM[IO] = stmRef
    (for {
      v <- vars(0).get
      _ <- vars(0).set(v + 1)
    } yield v).commit.unsafeRunSync()
  }

  /** Maximum concurrency: every transaction touches a DIFFERENT var, so all footprints are compatible and nothing
    * serializes. Measures the scheduler's admission path at full width.
    */
  @Benchmark
  @OperationsPerInvocation(32)
  def disjointConcurrent(): Int = {
    implicit val s: STM[IO] = stmRef
    (0 until Batch).toList
      .parTraverse { i =>
        (for {
          v <- vars(i).get
          _ <- vars(i).set(v + 1)
        } yield v).commit
      }
      .map(_.sum)
      .unsafeRunSync()
  }

  /** Full conflict: every transaction touches the SAME var, so the scheduler serializes all of them. Measures the
    * dependency-graph and hand-off path rather than raw commit cost.
    */
  @Benchmark
  @OperationsPerInvocation(32)
  def contendedConcurrent(): Int = {
    implicit val s: STM[IO] = stmRef
    (0 until Batch).toList
      .parTraverse { _ =>
        (for {
          v <- vars(0).get
          _ <- vars(0).set(v + 1)
        } yield v).commit
      }
      .map(_.sum)
      .unsafeRunSync()
  }

  /** Concurrent map writes on DISTINCT keys — compatible footprints, so they run together and contend on the map's
    * structural commitLock via the new-key fallback. This is the path H2's fix rewrote: `withLock` now resolves each
    * lock's owner id and sorts on that.
    */
  @Benchmark
  @OperationsPerInvocation(32)
  def mapWriteConcurrent(): Int = {
    implicit val s: STM[IO] = stmRef
    val base = counter.getAndAdd(Batch)
    (0 until Batch).toList
      .parTraverse { i =>
        val k = Keys((base + i) % Keys.size)
        (for {
          ov <- map.get(k)
          _  <- map.set(k, ov.getOrElse(0) + 1)
        } yield ov.getOrElse(0)).commit
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
  @OperationsPerInvocation(32)
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
    val base = counter.getAndAdd(Batch)
    (0 until Batch).toList
      .parTraverse { i =>
        val sk = s"s${base + i}"
        (for {
          _  <- scratch.set(sk, 1)
          ov <- scratch.get(sk)
          _  <- STM[IO].delay(ov.get) // throws during analysis, and nowhere else
          v  <- vars(i % Batch).get
          _  <- vars(i % Batch).set(v + 1)
        } yield v).commit
      }
      .map(_.sum)
      .unsafeRunSync()
  }
}
