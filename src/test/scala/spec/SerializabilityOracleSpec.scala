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
import org.scalacheck.Gen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import bengal.stm.STM
import bengal.stm.model._
import bengal.stm.syntax.all._

/** Serializability oracle (specs/README.md, "Keeping Specs and Code in Sync").
  *
  * Generated concurrent workloads run against the real STM; the observed outcome (every transaction's reads plus the
  * final state) must be reproducible by SOME serial order of the same transactions applied to a naive sequential
  * reference model. This is the semantic complement of the TLA+ specs: TLC checks the protocol design, this checks the
  * shipped code.
  *
  * Scope notes, deliberate:
  *   - Point operations only in the generated suite (var read/write/modify, map key read/upsert). All of these carry
  *     footprint-visible ids, so the scheduler serializes conflicting transactions and outcomes are expected
  *     serializable on the shipped code. For absent-key reads that visibility exists ONLY because the static-analysis
  *     walker registers the key's existential runtime id (the commit-time log records no entry and validates nothing
  *     for an absent read) — if static analysis were ever made lazy or optional, this suite would go red.
  *   - Whole-map reads stay out of the generated suite: the historically defective idiom (whole-map read + new-key
  *     insert — the H5 phantom, fixed by the relation's third conjunct, DocumentsParentReadChildWriteCaught in
  *     specs/common/FootprintLemmas.tla) is covered deterministically by the dedicated regression test below; folding
  *     ReadWholeMap into the generators is a candidate expansion now that the idiom serializes.
  *   - `waitFor`/retry stays out of the GENERATED suite: a parked transaction has no outcome to compare against a
  *     serial order until something wakes it, and a generator cannot be relied on to produce the waker. The park/wake
  *     machinery is covered behaviourally by `soak/RetrySoakSpec` and `spec/AbsentKeyParkSpec`, and by the model
  *     (`specs/scheduler/SchedulerRetry.cfg`, a CI pin). The one deterministic park/wake case that belongs here — a
  *     reader woken through a fresh incarnation — is below.
  *   - Transactions here have statically known access sets, so the static-analysis fallback stays dormant. Post-H3-fix
  *     that is a statement about COST, not safety: an under-approximated footprint is incompatible with everything, so
  *     it never overlaps a peer and can never go dirty. The only divergence left that can open a commit-time window is
  *     the DATA-DEPENDENT kind — which is exactly what the dirty-path test below uses to reach it.
  */
class SerializabilityOracleSpec extends AnyFreeSpec with ScalaCheckPropertyChecks with Matchers {

  // -------------------------------------------------------------------
  // Workload model
  // -------------------------------------------------------------------

  private val NumVars = 3
  private val MapKeys = Vector("k1", "k2", "k3")

  sealed private trait Op
  private case class ReadVar(i: Int) extends Op
  private case class WriteVar(i: Int, value: Int) extends Op
  private case class AddToVar(i: Int, delta: Int) extends Op
  private case class ReadKey(k: String) extends Op
  private case class UpsertKey(k: String, v: Int) extends Op
  private case object ReadWholeMap extends Op

  sealed private trait Obs
  private case class VarObs(v: Int) extends Obs
  private case class KeyObs(v: Option[Int]) extends Obs
  private case class MapObs(m: Map[String, Int]) extends Obs

  private case class ModelState(vars: Vector[Int], map: Map[String, Int])

  // Sequential reference semantics: one transaction, applied atomically.
  // Reads within a transaction see the transaction's own earlier writes,
  // matching the STM log's read-your-writes behaviour.
  private def applyTxn(state: ModelState, ops: List[Op]): (ModelState, List[Obs]) =
    ops.foldLeft((state, List.empty[Obs])) { case ((s, obs), op) =>
      op match {
        case ReadVar(i) => (s, obs :+ VarObs(s.vars(i)))
        case WriteVar(i, v) => (s.copy(vars = s.vars.updated(i, v)), obs)
        case AddToVar(i, d) => (s.copy(vars = s.vars.updated(i, s.vars(i) + d)), obs)
        case ReadKey(k) => (s, obs :+ KeyObs(s.map.get(k)))
        case UpsertKey(k, v) => (s.copy(map = s.map + (k -> v)), obs)
        case ReadWholeMap => (s, obs :+ MapObs(s.map))
      }
    }

  // The real transaction, built from the same ops via the public API.
  private def toTxn(
    ops: List[Op],
    vars: Vector[TxnVar[IO, Int]],
    map: TxnVarMap[IO, String, Int]
  )(implicit stm: STM[IO]): Txn[List[Obs]] =
    ops.foldLeft(STM[IO].pure(List.empty[Obs])) { (acc, op) =>
      acc.flatMap { obs =>
        op match {
          case ReadVar(i) => vars(i).get.map(v => obs :+ VarObs(v))
          case WriteVar(i, v) => vars(i).set(v).map(_ => obs)
          case AddToVar(i, d) => vars(i).modify(_ + d).map(_ => obs)
          case ReadKey(k) => map.get(k).map(v => obs :+ KeyObs(v))
          case UpsertKey(k, v) => map.set(k, v).map(_ => obs)
          case ReadWholeMap => map.get.map(m => obs :+ MapObs(m))
        }
      }
    }

  // A deadlock-class regression HANGS rather than failing, so the timeout is
  // what turns it into a red test — with the workload in the failure message —
  // instead of a CI job that runs out its 60 minutes and reports nothing.
  private def runWorkload(
    init: ModelState,
    workload: List[List[Op]]
  ): (ModelState, List[List[Obs]]) =
    STM
      .runtime[IO]
      .flatMap { implicit stm =>
        for {
          vars     <- init.vars.traverse(TxnVar.of[IO, Int])
          map      <- TxnVarMap.of[IO, String, Int](init.map)
          results  <- workload.parTraverse(ops => toTxn(ops, vars, map).commit)
          finalVs  <- vars.traverse(_.get.commit)
          finalMap <- map.get.commit
        } yield (ModelState(finalVs, finalMap), results)
      }
      .timeout(30.seconds)
      .unsafeRunSync()

  // Serializability: some permutation of the transactions, applied
  // sequentially to the reference model, reproduces every transaction's
  // observations and the final state. (Real-time ordering constraints are
  // not checked — this is serializability, not strict serializability.)
  private def isSerializable(
    init: ModelState,
    workload: List[List[Op]],
    observed: List[List[Obs]],
    finalState: ModelState
  ): Boolean =
    workload.zipWithIndex.permutations.exists { perm =>
      val (endState, resultsByIdx) =
        perm.foldLeft((init, Map.empty[Int, List[Obs]])) { case ((s, acc), (ops, idx)) =>
          val (s2, obs) = applyTxn(s, ops)
          (s2, acc + (idx -> obs))
        }
      endState == finalState &&
      observed.zipWithIndex.forall { case (obs, idx) => resultsByIdx(idx) == obs }
    }

  private def explain(
    init: ModelState,
    workload: List[List[Op]],
    observed: List[List[Obs]],
    finalState: ModelState
  ): String =
    s"""|no serial order reproduces the observed outcome
        |  init:     $init
        |  workload: ${workload.mkString(" || ")}
        |  observed: ${observed.mkString(" | ")}
        |  final:    $finalState""".stripMargin

  // -------------------------------------------------------------------
  // Generators (green suite: point operations only — see scope notes)
  // -------------------------------------------------------------------

  private val genVarIdx: Gen[Int] = Gen.choose(0, NumVars - 1)
  private val genKey: Gen[String] = Gen.oneOf(MapKeys)
  private val genValue: Gen[Int]  = Gen.choose(-5, 5)

  private val genPointOp: Gen[Op] = Gen.frequency(
    3 -> genVarIdx.map(ReadVar.apply),
    2 -> Gen.zip(genVarIdx, genValue).map { case (i, v) => WriteVar(i, v) },
    3 -> Gen.zip(genVarIdx, genValue).map { case (i, d) => AddToVar(i, d) },
    2 -> genKey.map(ReadKey.apply),
    2 -> Gen.zip(genKey, genValue).map { case (k, v) => UpsertKey(k, v) }
  )

  private val genTxn: Gen[List[Op]] =
    Gen.choose(1, 4).flatMap(n => Gen.listOfN(n, genPointOp))

  private val genWorkload: Gen[List[List[Op]]] =
    Gen.choose(2, 4).flatMap(n => Gen.listOfN(n, genTxn))

  private val genInit: Gen[ModelState] = for {
    vars    <- Gen.listOfN(NumVars, genValue).map(_.toVector)
    nKeys   <- Gen.choose(0, 2)
    entries <- Gen.listOfN(nKeys, Gen.zip(genKey, genValue))
  } yield ModelState(vars, entries.toMap)

  // -------------------------------------------------------------------
  // Properties
  // -------------------------------------------------------------------

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 80)

  "generated concurrent point-op workloads are serializable" in {
    forAll(genInit, genWorkload) { (init, workload) =>
      val (finalState, observed) = runWorkload(init, workload)
      withClue(explain(init, workload, observed, finalState)) {
        isSerializable(init, workload, observed, finalState) shouldBe true
      }
    }
  }

  "conflicting increments are never lost or doubled" in {
    // A focused canary for double-execution defects (H4 family): pure
    // read-modify-write increments on shared vars must sum exactly.
    // If this ever flakes in CI, treat it as a REAL H4-family finding
    // (a scheduler race outside the under-declared scenario), not noise.
    forAll(Gen.choose(2, 4), Gen.choose(1, 3)) { (nTxns, opsPerTxn) =>
      val workload        = List.fill(nTxns)(List.fill(opsPerTxn)(AddToVar(0, 1): Op))
      val init            = ModelState(Vector.fill(NumVars)(0), Map.empty)
      val (finalState, _) = runWorkload(init, workload)
      finalState.vars(0) shouldBe nTxns * opsPerTxn
    }
  }

  // -------------------------------------------------------------------
  // Dirty-path exerciser: resubmissions preserve exactly-once effects.
  // NOTE: this drives the machinery the H4 fix hardened (measured: ~120
  // TxnResultLogDirty resubmissions per 300 reps) but does NOT reach the
  // H4 defect interleaving itself — it passes against pre-fix code too.
  // The executable H4 regression gate is the TLC expected-clean check
  // (specs/scheduler/Scheduler.cfg in CI).
  // -------------------------------------------------------------------

  "dirty-path resubmissions preserve exactly-once effects" - {
    /* Exercises the dirty/resubmission machinery (freshIncarnation +
     * admitForExecution, the H4 fix) through the public API. A
     * data-dependent map key makes the statically declared footprint
     * diverge from the actual one: tSelect flips the selector var while
     * tKey (which read the selector at static-analysis time) waits on it,
     * so tKey's actual entry write can land on the same key tDirect
     * declared — footprint-compatible per declarations, write-write in
     * reality, which is exactly what produces TxnResultLogDirty and a
     * resubmission. Pre-fix, the resubmission's shared bookkeeping could
     * double-execute transactions (H4, confirmed in TLC); post-fix every
     * effect must apply exactly once and the outcome must be serializable.
     */
    "under contention with data-dependent keys" in {
      val reps = 300
      (1 to reps).foreach { rep =>
        val (finalS, counters, finalMap) =
          STM
            .runtime[IO]
            .flatMap { implicit stm =>
              for {
                sel      <- TxnVar.of[IO, Int](0)
                counters <- List.fill(3)(0).traverse(TxnVar.of[IO, Int])
                map      <- TxnVarMap.of[IO, String, Int](Map.empty[String, Int])
                tSelect = for {
                            _ <- sel.modify(_ + 1)
                            _ <- counters(0).modify(_ + 1)
                          } yield ()
                tKey = for {
                         s <- sel.get
                         _ <- map.set("k" + (s % 2), 10)
                         _ <- counters(1).modify(_ + 1)
                       } yield ()
                tDirect = for {
                            _ <- map.set("k1", 20)
                            _ <- counters(2).modify(_ + 1)
                          } yield ()
                _  <- List(tSelect.commit, tKey.commit, tDirect.commit).parSequence
                s  <- sel.get.commit
                cs <- counters.traverse(_.get.commit)
                m  <- map.get.commit
              } yield (s, cs, m)
            }
            .timeout(30.seconds)
            .unsafeRunSync()

        withClue(s"rep $rep: s=$finalS counters=$counters map=$finalMap") {
          finalS shouldBe 1
          counters shouldBe List(1, 1, 1)
          // tKey wrote k0 (read sel before tSelect) or k1 (after); tDirect
          // wrote k1. Every serializable outcome has k-values from that set
          // and k1 present (tDirect always writes it; tKey may overwrite).
          finalMap.keySet should contain("k1")
          finalMap.keySet.subsetOf(Set("k0", "k1")) shouldBe true
          finalMap.get("k0").foreach(_ shouldBe 10)
          Set(10, 20) should contain(finalMap("k1"))
        }
      }
    }
  }

  // -------------------------------------------------------------------
  // Park/wake smoke test: parked retries wake through fresh incarnations
  // -------------------------------------------------------------------

  "parked retries wake through fresh incarnations" - {
    /* A reader parks on waitFor (predicate false, no downstream); a writer
     * then submits, whose checkRetryQueue wake rebuilds the parked
     * transaction as a freshIncarnation. That wake-time rebuild is
     * load-bearing, not hygiene: cascadeFired is sticky on the parked
     * object, so resubmitting it raw would make its next triggerUnsub a
     * no-op and deadlock any downstream subscriber. THAT is what this rep
     * count is for — the rebuild, not park/wake in general, which is
     * `soak/RetrySoakSpec`'s job and `specs/scheduler/SchedulerRetry.cfg`'s.
     * A lost wakeup hangs, so the timeout is the assertion.
     */
    "reader parked on waitFor completes after the writer commits" in {
      val reps = 50
      (1 to reps).foreach { rep =>
        val result =
          STM
            .runtime[IO]
            .flatMap { implicit stm =>
              for {
                flag <- TxnVar.of[IO, Int](0)
                reader = for {
                           v <- flag.get
                           _ <- STM[IO].waitFor(v > 0)
                         } yield v
                fib <- reader.commit.start
                _   <- IO.sleep(20.millis)
                _   <- flag.set(1).commit
                v   <- fib.joinWithNever
              } yield v
            }
            .timeout(30.seconds)
            .unsafeRunSync()
        withClue(s"rep $rep:")(result shouldBe 1)
      }
    }
  }

  // -------------------------------------------------------------------
  // H5 regression: whole-map read + new-key insert must serialize
  // -------------------------------------------------------------------

  "H5 phantom write skew: whole-map read + new-key insert" - {
    /* Two transactions each read the whole map and insert a distinct new
     * key. Before the H5 fix their footprints were judged compatible (a
     * parent-structure READ was never tested against a child-entry WRITE)
     * and, with structure reads never commit-validated, both observed the
     * EMPTY map yet both committed — an outcome no serial order produces,
     * measured at ~98% of contended reps. The fixed relation's third
     * conjunct (DocumentsParentReadChildWriteCaught in
     * specs/common/FootprintLemmas.tla) makes the pair conflict, so the
     * scheduler serializes them and every rep must now be serializable.
     * This is the behavioural regression test for that fix.
     */
    "is serializable in every rep (regression for the H5 fix)" in {
      val t1ops = List(ReadWholeMap, UpsertKey("a", 1))
      val t2ops = List(ReadWholeMap, UpsertKey("b", 2))
      val init  = ModelState(Vector.fill(NumVars)(0), Map.empty)

      val reps = 500
      (1 to reps).foreach { rep =>
        val (finalState, observed) = runWorkload(init, List(t1ops, t2ops))
        withClue(s"rep $rep: " + explain(init, List(t1ops, t2ops), observed, finalState)) {
          isSerializable(init, List(t1ops, t2ops), observed, finalState) shouldBe true
        }
      }
    }
  }
}
