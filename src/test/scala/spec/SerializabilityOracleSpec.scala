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
  *   - Whole-map reads are EXCLUDED from the generated suite: combined with concurrent new-key inserts they express the
  *     H5 relation gap (structure reads are footprint-compatible with new-key entry writes and never commit-validated —
  *     see DocumentsReadGapH5 in specs/common/FootprintLemmas.tla), and the dedicated probe below owns that idiom.
  *   - waitUntil/retry is out of scope until the Phase 2 spec work lands (park/wake is unmodelled and carries its own
  *     hypothesis, H1).
  *   - Transactions here have statically known access sets, so the static-analysis fallback (H4's enabling condition)
  *     stays dormant.
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

  // The timeout turns a deadlock-class regression (the #51/#52 defect
  // family this oracle exists to catch) into a red test with the workload
  // in the failure message, instead of a hung CI job.
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
  // H5 pinned reproduction (EXPECTED ANOMALY — flips red when fixed)
  // -------------------------------------------------------------------

  "H5 phantom write skew: whole-map read + new-key insert" - {
    /* Two transactions each read the whole map and insert a distinct new
     * key. Their footprints are judged compatible (a parent-structure READ
     * is never tested against a child-entry WRITE — DocumentsReadGapH5 in
     * specs/common/FootprintLemmas.tla), the structure read is never
     * commit-validated, and so both can observe the EMPTY map yet both
     * commit — an outcome no serial order produces. Measured on first
     * probe: 1958/2000 reps anomalous on a 12-core host, i.e. the phantom
     * is the DEFAULT outcome under contention, and the static-analysis
     * walker does not close the gap (the Phase 3 pre-step of
     * docs/plans/formal-specs.md §6 H5, answered empirically).
     *
     * This test PINS the defect the way check_expected.sh pins TLC
     * counterexamples: it asserts the anomaly still reproduces. IF THIS
     * GOES RED the relation (or structure-read validation) was fixed —
     * update DocumentsReadGapH5, the verdict table in specs/README.md,
     * the H5 row in the plan, and rewrite this test to assert
     * serializability of the idiom.
     */
    "reproduces (pinned defect — see specs/README.md verdict table)" in {
      val t1ops = List(ReadWholeMap, UpsertKey("a", 1))
      val t2ops = List(ReadWholeMap, UpsertKey("b", 2))
      val init  = ModelState(Vector.fill(NumVars)(0), Map.empty)

      val maxReps = 1000
      val anomaly = (1 to maxReps).exists { _ =>
        val (finalState, observed) = runWorkload(init, List(t1ops, t2ops))
        !isSerializable(init, List(t1ops, t2ops), observed, finalState)
      }
      withClue(
        s"no phantom observed in $maxReps reps — if the footprint relation " +
          "or structure-read validation was fixed, flip this pin (see comment)"
      ) {
        anomaly shouldBe true
      }
    }
  }
}
