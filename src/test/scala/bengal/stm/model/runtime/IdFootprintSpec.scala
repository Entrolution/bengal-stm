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

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class IdFootprintSpec extends AnyFreeSpec with Matchers {

  private val id1 = TxnVarRuntimeId(1)
  private val id2 = TxnVarRuntimeId(2)
  private val id3 = TxnVarRuntimeId(3)

  private val parentId = TxnVarRuntimeId(10)
  private val childId  = TxnVarRuntimeId(20, parent = Some(parentId))

  "Construction" - {
    "empty has no IDs" in {
      IdFootprint.empty.readIds shouldBe empty
      IdFootprint.empty.updatedIds shouldBe empty
    }

    "empty is pre-validated" in {
      val validated = IdFootprint.empty.getValidated
      validated.readIds shouldBe empty
      validated.updatedIds shouldBe empty
      validated.isValidated shouldBe true
    }
  }

  "addReadId" - {
    "adds a single read ID" in {
      val fp = IdFootprint.empty.addReadId(id1)
      fp.readIds shouldBe Set(id1)
      fp.updatedIds shouldBe empty
    }

    "accumulates multiple read IDs" in {
      val fp = IdFootprint.empty.addReadId(id1).addReadId(id2).addReadId(id3)
      fp.readIds shouldBe Set(id1, id2, id3)
    }
  }

  "addWriteId" - {
    "adds a single write ID" in {
      val fp = IdFootprint.empty.addWriteId(id1)
      fp.updatedIds shouldBe Set(id1)
      fp.readIds shouldBe empty
    }

    "accumulates multiple write IDs" in {
      val fp = IdFootprint.empty.addWriteId(id1).addWriteId(id2).addWriteId(id3)
      fp.updatedIds shouldBe Set(id1, id2, id3)
    }
  }

  "mergeWith" - {
    "unions read and write sets" in {
      val a      = IdFootprint(Set(id1), Set(id2))
      val b      = IdFootprint(Set(id3), Set(id1))
      val merged = a.mergeWith(b)
      merged.readIds shouldBe Set(id1, id3)
      merged.updatedIds shouldBe Set(id2, id1)
    }

    "identity with empty" in {
      val fp     = IdFootprint(Set(id1, id2), Set(id3))
      val merged = fp.mergeWith(IdFootprint.empty)
      merged.readIds shouldBe fp.readIds
      merged.updatedIds shouldBe fp.updatedIds
    }
  }

  "getValidated" - {
    "removes reads that overlap writes" in {
      val fp        = IdFootprint(readIds = Set(id1, id2), updatedIds = Set(id1))
      val validated = fp.getValidated
      validated.readIds shouldBe Set(id2)
      validated.updatedIds shouldBe Set(id1)
    }

    "removes reads whose parent is in writes" in {
      val fp        = IdFootprint(readIds = Set(childId), updatedIds = Set(parentId))
      val validated = fp.getValidated
      validated.readIds shouldBe empty
      validated.updatedIds shouldBe Set(parentId)
    }

    "preserves reads whose parent is only a read" in {
      val fp        = IdFootprint(readIds = Set(childId, parentId), updatedIds = Set.empty)
      val validated = fp.getValidated
      validated.readIds shouldBe Set(childId, parentId)
    }

    "leaves clean reads intact" in {
      val fp        = IdFootprint(readIds = Set(id1, id2), updatedIds = Set(id3))
      val validated = fp.getValidated
      validated.readIds shouldBe Set(id1, id2)
    }

    "is idempotent" in {
      val fp    = IdFootprint(readIds = Set(id1, id2, childId), updatedIds = Set(id1, parentId))
      val once  = fp.getValidated
      val twice = once.getValidated
      twice.readIds shouldBe once.readIds
      twice.updatedIds shouldBe once.updatedIds
    }

    "sets isValidated flag" in {
      val fp = IdFootprint(readIds = Set(id1), updatedIds = Set(id2))
      fp.isValidated shouldBe false
      fp.getValidated.isValidated shouldBe true
    }

    "is reset by every content-changing op, and only those" in {
      val validated = IdFootprint(readIds = Set(id1), updatedIds = Set(id2)).getValidated
      validated.isValidated shouldBe true
      validated.addReadId(id3).isValidated shouldBe false
      validated.addWriteId(id3).isValidated shouldBe false
      validated.mergeWith(IdFootprint(readIds = Set(id3), updatedIds = Set.empty)).isValidated shouldBe false
      // A VALIDATED argument must not smuggle its flag into the merge result:
      // two validated halves can still cross-overlap, so their union is
      // unvalidated no matter what either flag said.
      validated
        .mergeWith(IdFootprint(readIds = Set(id3), updatedIds = Set(id1)).getValidated)
        .isValidated shouldBe false
      // markUnderApproximated alone preserves the flag: it changes no content,
      // and validation carries the under flag through.
      validated.markUnderApproximated.isValidated shouldBe true
    }

    "re-validates content added after a validation" in {
      // A read equal to an existing write arrives AFTER validation: were the
      // memo flag carried through the mutation, the second getValidated would
      // short-circuit and the overlapping read would survive undeduped.
      val validated = IdFootprint(readIds = Set(id1), updatedIds = Set(id2)).getValidated
      val mutated   = validated.addReadId(id2)
      mutated.getValidated.readIds shouldBe Set(id1)
    }
  }

  "isCompatibleWith" - {
    "basic" - {
      "empty vs empty is compatible" in {
        IdFootprint.empty.isCompatibleWith(IdFootprint.empty) shouldBe true
      }

      "read-read same ID is compatible" in {
        val a = IdFootprint(readIds = Set(id1), updatedIds = Set.empty)
        val b = IdFootprint(readIds = Set(id1), updatedIds = Set.empty)
        a.isCompatibleWith(b) shouldBe true
      }

      "read-write same ID is incompatible" in {
        val a = IdFootprint(readIds = Set(id1), updatedIds = Set.empty)
        val b = IdFootprint(readIds = Set.empty, updatedIds = Set(id1))
        a.isCompatibleWith(b) shouldBe false
      }

      "write-write same ID is incompatible" in {
        val a = IdFootprint(readIds = Set.empty, updatedIds = Set(id1))
        val b = IdFootprint(readIds = Set.empty, updatedIds = Set(id1))
        a.isCompatibleWith(b) shouldBe false
      }

      "disjoint footprints are compatible" in {
        val a = IdFootprint(readIds = Set(id1), updatedIds = Set(id2))
        val b = IdFootprint(readIds = Set(id3), updatedIds = Set.empty)
        a.isCompatibleWith(b) shouldBe true
      }

      "symmetry holds" in {
        val a = IdFootprint(readIds = Set(id1), updatedIds = Set.empty)
        val b = IdFootprint(readIds = Set.empty, updatedIds = Set(id1))
        a.isCompatibleWith(b) shouldBe b.isCompatibleWith(a)
      }
    }

    "parent/child" - {
      "write parent vs read child is incompatible" in {
        val a = IdFootprint(readIds = Set.empty, updatedIds = Set(parentId))
        val b = IdFootprint(readIds = Set(childId), updatedIds = Set.empty)
        a.isCompatibleWith(b) shouldBe false
      }

      "write parent vs write child is incompatible" in {
        val a = IdFootprint(readIds = Set.empty, updatedIds = Set(parentId))
        val b = IdFootprint(readIds = Set.empty, updatedIds = Set(childId))
        a.isCompatibleWith(b) shouldBe false
      }

      "read parent vs read child is compatible" in {
        val a = IdFootprint(readIds = Set(parentId), updatedIds = Set.empty)
        val b = IdFootprint(readIds = Set(childId), updatedIds = Set.empty)
        a.isCompatibleWith(b) shouldBe true
      }

      "read parent vs write child is incompatible" in {
        // A parent (structure) read observes the key set, so a child-entry
        // write must conflict with it — the H5 phantom-write-skew fix.
        // Before the fix this pair was judged compatible, which let
        // whole-map readers race new-key inserters unserialized.
        val a = IdFootprint(readIds = Set(parentId), updatedIds = Set.empty)
        val b = IdFootprint(readIds = Set.empty, updatedIds = Set(childId))
        a.isCompatibleWith(b) shouldBe false
      }
    }

    "cross-conflict" - {
      "A reads X + writes Y, B reads Y + writes X is incompatible" in {
        val a = IdFootprint(readIds = Set(id1), updatedIds = Set(id2))
        val b = IdFootprint(readIds = Set(id2), updatedIds = Set(id1))
        a.isCompatibleWith(b) shouldBe false
      }
    }

    // The H3 fix. An under-approximated footprint is not a small footprint, it
    // is an unknown one, and there is no sound way to compare against a set you
    // know to be incomplete.
    "under-approximation" - {
      "is incompatible with everything, including the empty footprint" in {
        val unknown = IdFootprint(Set.empty, Set.empty, isUnderApproximated = true)
        unknown.isCompatibleWith(IdFootprint.empty) shouldBe false
        IdFootprint.empty.isCompatibleWith(unknown) shouldBe false
        unknown.isCompatibleWith(unknown) shouldBe false
        unknown.isCompatibleWith(IdFootprint(readIds = Set(id1), updatedIds = Set.empty)) shouldBe false
      }

      "does not serialize anything it shouldn't — complete disjoint footprints stay compatible" in {
        val a = IdFootprint(readIds = Set(id1), updatedIds = Set.empty)
        val b = IdFootprint(readIds = Set.empty, updatedIds = Set(id2))
        a.isCompatibleWith(b) shouldBe true
      }

      "survives getValidated — a validated footprint must not regain the scheduler's trust" in {
        // The runtime compares getValidated footprints EVERYWHERE, so if
        // validation dropped the flag the whole fix would be a no-op.
        IdFootprint(Set(id1), Set(id1), isUnderApproximated = true).getValidated.isUnderApproximated shouldBe true
      }

      "is contagious under merge" in {
        val known   = IdFootprint(readIds = Set(id1), updatedIds = Set.empty)
        val unknown = IdFootprint(Set.empty, Set.empty, isUnderApproximated = true)
        known.mergeWith(unknown).isUnderApproximated shouldBe true
        unknown.mergeWith(known).isUnderApproximated shouldBe true
      }
    }
  }

  // The H6 fix, and a DIFFERENT RELATION from the one above — `isCompatibleWith`
  // asks whether two transactions may overlap, `covers` asks whether one
  // footprint's declaration subsumes another's. They were nested together once,
  // which read as though coverage were a mode of compatibility. It is not.
  //
  // `covers` asks whether declaring THIS footprint excluded at least as much
  // concurrency as declaring the actual one would have. It is deliberately NOT a
  // subset test, and the asymmetry below is why.
  "covers" - {
    "an accurate footprint covers itself, so a static access set never trips the check" in {
      val f = IdFootprint(readIds = Set(id1, parentId), updatedIds = Set(id2))
      f.covers(f) shouldBe true
    }

    "a parent-structure READ covers a child-entry READ" in {
      // The whole-map-read case: the log expands `getVarMap` into a read-only
      // entry for EVERY existing key, so the actual footprint properly
      // contains ids the static walker never named. A subset test would abort
      // every whole-map read in the library; these ids are genuinely covered,
      // because a parent read conflicts with any child write (the H5 conjunct).
      val declared = IdFootprint(readIds = Set(parentId), updatedIds = Set.empty)
      val actual   = IdFootprint(readIds = Set(parentId, childId), updatedIds = Set.empty)
      declared.covers(actual) shouldBe true
    }

    "a parent-structure READ does NOT cover a child-entry WRITE" in {
      // Reading a map does not announce that you will write a key in it.
      val declared = IdFootprint(readIds = Set(parentId), updatedIds = Set.empty)
      val actual   = IdFootprint(readIds = Set.empty, updatedIds = Set(childId))
      declared.covers(actual) shouldBe false
    }

    "a parent-structure WRITE covers child reads and writes alike" in {
      // The setVarMap case: the log expands a structure write into a per-key
      // update entry.
      val declared = IdFootprint(readIds = Set.empty, updatedIds = Set(parentId))
      declared.covers(IdFootprint(readIds = Set.empty, updatedIds = Set(childId))) shouldBe true
      declared.covers(IdFootprint(readIds = Set(childId), updatedIds = Set.empty)) shouldBe true
    }

    "declaring a write to one id does NOT cover writing a different one — this is H6" in {
      // A data-dependent key: the walker read the key source before the
      // transaction was scheduled, that source changed, and the run touched an
      // entry nobody declared.
      val declared = IdFootprint(readIds = Set.empty, updatedIds = Set(id1))
      val actual   = IdFootprint(readIds = Set.empty, updatedIds = Set(id2))
      declared.covers(actual) shouldBe false
    }

    "nor does it cover a SIBLING entry of the same map — the map-key form of H6" in {
      val sibling  = TxnVarRuntimeId(21, parent = Some(parentId))
      val declared = IdFootprint(readIds = Set.empty, updatedIds = Set(childId))
      declared.covers(IdFootprint(readIds = Set.empty, updatedIds = Set(sibling))) shouldBe false
    }

    "declaring a READ does not cover a WRITE of the same id" in {
      val declared = IdFootprint(readIds = Set(id1), updatedIds = Set.empty)
      val actual   = IdFootprint(readIds = Set.empty, updatedIds = Set(id1))
      declared.covers(actual) shouldBe false
    }

    "an empty actual footprint is covered by anything" in {
      IdFootprint.empty.covers(IdFootprint.empty) shouldBe true
      IdFootprint(readIds = Set(id3), updatedIds = Set.empty).covers(IdFootprint.empty) shouldBe true
    }
  }
}
