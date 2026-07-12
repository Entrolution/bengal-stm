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
package bengal.stm.model.runtime

import org.scalacheck.Gen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/** The footprint algebra, quantified. `IdFootprintSpec` pins hand-built cases; this reaches the shapes nobody thinks to
  * write down.
  *
  * `specs/common/FootprintLemmas.tla` checks the same laws EXHAUSTIVELY over a four-id universe, which is stronger than
  * anything a generator can offer. The point of mirroring them here is that the TLA+ lemmas are about a TRANSCRIPTION
  * of the relation, and this is about the relation itself — the two go out of sync silently, and a law that holds in
  * the model and not in the code is precisely the failure the model cannot see. Each property below names the lemma it
  * mirrors.
  *
  * THE GENERATOR SETS `isUnderApproximated`, and that is not a detail. It did not, once, and two properties in this
  * file asserted universals the H3 fix had already falsified — `empty` is NOT compatible with any footprint, only with
  * any COMPLETE one — while contradicting `IdFootprintSpec`'s hand-built cases and passing anyway, because the
  * generator could not build the counterexample. A generator that cannot reach a flag is a generator that quietly
  * restricts every universal in the file to the flag's default.
  */
class IdFootprintPropertySpec extends AnyFreeSpec with ScalaCheckPropertyChecks with Matchers {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 200)

  private val genRawId: Gen[Int] = Gen.choose(0, 20)

  private val genTxnVarRuntimeId: Gen[TxnVarRuntimeId] = for {
    value     <- genRawId
    hasParent <- Gen.oneOf(true, false)
    parentVal <- genRawId
  } yield
    // Two levels only, matching the hierarchy the relation ASSUMES: parents are
    // never themselves parented. Every parent check in IdFootprint is one-hop.
    if (hasParent) TxnVarRuntimeId(value, Some(TxnVarRuntimeId(parentVal)))
    else TxnVarRuntimeId(value)

  private val genIdSet: Gen[Set[TxnVarRuntimeId]] =
    Gen.choose(0, 5).flatMap(n => Gen.listOfN(n, genTxnVarRuntimeId).map(_.toSet))

  /** A footprint whose access set the static walker fully determined. This is what the runtime holds everywhere EXCEPT
    * on the fallback path — and it is what an ACTUAL footprint always is, since that one is merged out of real log
    * entries and no entry can be under-approximated.
    */
  private val genCompleteFootprint: Gen[IdFootprint] = for {
    reads  <- genIdSet
    writes <- genIdSet
  } yield IdFootprint(reads, writes)

  /** The fallback: the walker threw, so what was gathered is a SUBSET of the truth. Not a small footprint — an unknown
    * one.
    */
  private val genUnderApproximatedFootprint: Gen[IdFootprint] = for {
    reads  <- genIdSet
    writes <- genIdSet
  } yield IdFootprint(reads, writes, isUnderApproximated = true)

  /** Both kinds, weighted towards the common one. Every unqualified property in this file quantifies over THIS. */
  private val genIdFootprint: Gen[IdFootprint] =
    Gen.frequency(3 -> genCompleteFootprint, 1 -> genUnderApproximatedFootprint)

  private val genReadOnlyFootprint: Gen[IdFootprint] =
    genIdSet.map(reads => IdFootprint(reads, Set.empty))

  /** A SMALL, CLOSED universe of ids, shaped like the real ones: two map structures, two entries under each, and two
    * plain vars that parent nothing. `FootprintLemmas.tla` is exhaustive over a universe this size, which is what makes
    * its lemmas proofs rather than samples.
    *
    * The size is the point, and it is what the covers properties below are drawn from. Over the WIDE id range a random
    * `actual` is essentially never covered by a random `declared`, so a soundness property quantified over wide draws
    * would be vacuously true on nearly every case — and, far worse, would stay green against a `covers` MUTATED to be
    * too permissive, because it would never generate the pairs the mutation newly accepts. A small universe collides
    * constantly, so coverage arises by luck, and a permissive `covers` is caught by the pairs it starts admitting.
    */
  private val Universe: Vector[TxnVarRuntimeId] = {
    val s1 = TxnVarRuntimeId(10)
    val s2 = TxnVarRuntimeId(11)
    Vector(
      s1,
      s2,
      TxnVarRuntimeId(0, parent = Some(s1)),
      TxnVarRuntimeId(1, parent = Some(s1)),
      TxnVarRuntimeId(2, parent = Some(s2)),
      TxnVarRuntimeId(3, parent = Some(s2)),
      TxnVarRuntimeId(20),
      TxnVarRuntimeId(21)
    )
  }

  /** SPARSE, and it has to be. Taking each id with even odds gives footprints holding half the universe, and then
    * nothing covers anything and nothing is compatible with anything — the implications below would all hold, and hold
    * VACUOUSLY. Small footprints over a small universe is what makes coverage and compatibility arise often enough to
    * mean something; the counters keep that honest.
    */
  private val genUniverseIdSet: Gen[Set[TxnVarRuntimeId]] =
    Gen.choose(0, 2).flatMap(n => Gen.pick(n, Universe).map(_.toSet))

  private val genUniverseFootprint: Gen[IdFootprint] = for {
    reads  <- genUniverseIdSet
    writes <- genUniverseIdSet
  } yield IdFootprint(reads, writes)

  // ---------------------------------------------------------------------------
  // isCompatibleWith
  // ---------------------------------------------------------------------------

  "isCompatibleWith is symmetric" in {
    forAll(genIdFootprint, genIdFootprint) { (a, b) =>
      a.isCompatibleWith(b) shouldBe b.isCompatibleWith(a)
    }
  }

  "IdFootprint.empty is compatible with any COMPLETE footprint" in {
    forAll(genCompleteFootprint) { fp =>
      IdFootprint.empty.isCompatibleWith(fp) shouldBe true
    }
  }

  "a COMPLETE read-only footprint is self-compatible" in {
    forAll(genReadOnlyFootprint) { fp =>
      fp.isCompatibleWith(fp) shouldBe true
    }
  }

  "overlapping write IDs implies incompatible" in {
    val genOverlappingWrites = for {
      shared  <- genTxnVarRuntimeId
      aReads  <- genIdSet
      aWrites <- genIdSet
      bReads  <- genIdSet
      bWrites <- genIdSet
    } yield (
      IdFootprint(aReads, aWrites + shared),
      IdFootprint(bReads, bWrites + shared)
    )

    forAll(genOverlappingWrites) { case (a, b) =>
      a.isCompatibleWith(b) shouldBe false
    }
  }

  // ---------------------------------------------------------------------------
  // Under-approximation — the H3 fix
  // ---------------------------------------------------------------------------

  /* LemmaUnderApproximatedIncompatibleWithAll. There is no sound way to compare
   * against a set you know to be incomplete: any "compatible" verdict would be
   * an inference from absent evidence. So the relation refuses to give one, and
   * the transaction is serialized against everything and runs alone — the empty
   * footprint and the flagged footprint itself included. The `empty` property
   * above has to be qualified to COMPLETE footprints for exactly this reason.
   */
  "an under-approximated footprint is incompatible with EVERY footprint, itself included" in {
    forAll(genUnderApproximatedFootprint, genIdFootprint) { (unknown, other) =>
      unknown.isCompatibleWith(other) shouldBe false
      other.isCompatibleWith(unknown) shouldBe false
      unknown.isCompatibleWith(unknown) shouldBe false
    }
  }

  /* LemmaValidatedPreservesUnderApproximation. The runtime compares getValidated
   * footprints EVERYWHERE, so a validation that dropped the flag would make the
   * whole H3 fix a no-op.
   */
  "getValidated preserves the under-approximation flag" in {
    forAll(genIdFootprint) { fp =>
      fp.getValidated.isUnderApproximated shouldBe fp.isUnderApproximated
    }
  }

  // ---------------------------------------------------------------------------
  // mergeWith and getValidated
  // ---------------------------------------------------------------------------

  "mergeWith is commutative" in {
    forAll(genIdFootprint, genIdFootprint) { (a, b) =>
      val ab = a.mergeWith(b)
      val ba = b.mergeWith(a)
      ab.readIds shouldBe ba.readIds
      ab.updatedIds shouldBe ba.updatedIds
      ab.isUnderApproximated shouldBe ba.isUnderApproximated
    }
  }

  "mergeWith is associative" in {
    forAll(genIdFootprint, genIdFootprint, genIdFootprint) { (a, b, c) =>
      val left  = a.mergeWith(b).mergeWith(c)
      val right = a.mergeWith(b.mergeWith(c))
      left.readIds shouldBe right.readIds
      left.updatedIds shouldBe right.updatedIds
      left.isUnderApproximated shouldBe right.isUnderApproximated
    }
  }

  /* LemmaMergePropagatesUnderApproximation. Incompleteness is contagious: a
   * merge is only as trustworthy as its least trustworthy half.
   */
  "mergeWith ORs the under-approximation flag" in {
    forAll(genIdFootprint, genIdFootprint) { (a, b) =>
      a.mergeWith(b).isUnderApproximated shouldBe (a.isUnderApproximated || b.isUnderApproximated)
    }
  }

  "getValidated is idempotent" in {
    forAll(genIdFootprint) { fp =>
      val once  = fp.getValidated
      val twice = once.getValidated
      twice.readIds shouldBe once.readIds
      twice.updatedIds shouldBe once.updatedIds
    }
  }

  "validation never removes write IDs" in {
    forAll(genIdFootprint) { fp =>
      fp.getValidated.updatedIds shouldBe fp.updatedIds
    }
  }

  // ---------------------------------------------------------------------------
  // covers — the H6 fix
  // ---------------------------------------------------------------------------

  /* LemmaCoverageIsSound, and the entire justification for the commit-time check.
   *
   * If the DECLARED footprint covers the ACTUAL one, then every transaction the
   * scheduler judged compatible with the declaration really is compatible with
   * what the transaction went on to touch. Contract C on `declared` therefore
   * implies Contract C on `actual`, and the placement was sound after all — so
   * the run may publish. Fail it and the transaction was scheduled on a footprint
   * that did not describe it, and must abort before publishing.
   *
   * Stated as an IMPLICATION over unconstrained triples rather than over pairs
   * built to be covering, and that is the load-bearing choice. A generator that
   * CONSTRUCTS a covered `actual` can only construct it by applying the coverage
   * rule — so it never produces the pairs a too-permissive `covers` would newly
   * accept, and the property stays green against exactly the mutation it exists to
   * catch. Drawing freely from a small universe and filtering by `covers` itself
   * has no such blind spot.
   *
   * The counter is the anti-vacuity guard: an implication whose antecedent is
   * never satisfied is a rubber stamp, and this antecedent is a conjunction of two
   * conditions.
   *
   * Every footprint is drawn COMPLETE, which is not a convenience: `actual` is
   * merged out of real log entries, and no log entry can be under-approximated.
   */
  "coverage is sound — a peer compatible with the DECLARED footprint is compatible with the ACTUAL one" in {
    var nonVacuous = 0

    forAll(genUniverseFootprint, genUniverseFootprint, genUniverseFootprint) { (declared, actual, peer) =>
      val holds = declared.covers(actual) && declared.isCompatibleWith(peer)
      if (holds) nonVacuous += 1
      (!holds || actual.isCompatibleWith(peer)) shouldBe true
    }

    withClue("no draw satisfied `declared covers actual AND declared is compatible with peer` — vacuously green: ") {
      nonVacuous should be > 0
    }
  }

  /* LemmaCoverageReflexive. A transaction whose access set the walker fully
   * determined declares exactly what it touches, so it never trips the check.
   * This is what bounds the H6 fix's cost to the data-dependent path — without
   * it, every transaction in the library would pay a refine-and-re-run.
   */
  "a footprint covers itself, so a statically accurate access set never trips the commit-time check" in {
    forAll(genIdFootprint) { fp =>
      fp.covers(fp) shouldBe true
    }
  }

  "every footprint covers the empty one" in {
    forAll(genIdFootprint) { fp =>
      fp.covers(IdFootprint.empty) shouldBe true
    }
  }

  /* DocumentsParentReadCoversChildRead — the asymmetry that stops `covers` being
   * a subset test. A whole-map read expands, in the log, into a read-only entry
   * for EVERY existing key, so the actual footprint properly contains ids the
   * walker never named; a subset test would abort every whole-map read in the
   * library. Those ids are genuinely covered, because a parent read conflicts
   * with any child write (the relation's third conjunct, the H5 fix). Reading a
   * map does not, however, announce that you will WRITE a key in it.
   */
  "a parent READ covers a child READ but not a child WRITE" in {
    forAll(genRawId, genRawId) { (p, c) =>
      val parent = TxnVarRuntimeId(p)
      val child  = TxnVarRuntimeId(c, parent = Some(parent))

      val readsParent = IdFootprint(readIds = Set(parent), updatedIds = Set.empty)
      readsParent.covers(IdFootprint(readIds = Set(child), updatedIds = Set.empty)) shouldBe true
      readsParent.covers(IdFootprint(readIds = Set.empty, updatedIds = Set(child))) shouldBe false
    }
  }

  "a parent WRITE covers child reads and writes alike" in {
    forAll(genRawId, genRawId) { (p, c) =>
      val parent = TxnVarRuntimeId(p)
      val child  = TxnVarRuntimeId(c, parent = Some(parent))

      val writesParent = IdFootprint(readIds = Set.empty, updatedIds = Set(parent))
      writesParent.covers(IdFootprint(readIds = Set(child), updatedIds = Set.empty)) shouldBe true
      writesParent.covers(IdFootprint(readIds = Set.empty, updatedIds = Set(child))) shouldBe true
    }
  }

  /* This is H6 itself, quantified: the walker named one entry and the run touched
   * another. An undeclared write is covered by NOTHING — not by a declaration of
   * some other id, and not by a declaration of a SIBLING under the same parent.
   */
  "a write to an id the declaration neither wrote nor parents is NOT covered" in {
    forAll(genCompleteFootprint, genTxnVarRuntimeId) { (declared, id) =>
      val named    = declared.updateRawIds.contains(id.value)
      val parented = id.parent.exists(p => declared.updateRawIds.contains(p.value))

      whenever(!named && !parented) {
        declared.covers(IdFootprint(readIds = Set.empty, updatedIds = Set(id))) shouldBe false
      }
    }
  }

  /* The declaration is accumulated by merging, so widening it must never lose
   * coverage of something already covered. Same implication form, same reason.
   */
  "declaring MORE never breaks coverage" in {
    var nonVacuous = 0

    forAll(genUniverseFootprint, genUniverseFootprint, genUniverseFootprint) { (declared, actual, extra) =>
      val covered = declared.covers(actual)
      if (covered) nonVacuous += 1
      (!covered || declared.mergeWith(extra).covers(actual)) shouldBe true
    }

    withClue("no draw was covered to begin with — vacuously green: ") {
      nonVacuous should be > 0
    }
  }
}
