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

      "read parent vs write child is compatible" in {
        val a = IdFootprint(readIds = Set(parentId), updatedIds = Set.empty)
        val b = IdFootprint(readIds = Set.empty, updatedIds = Set(childId))
        a.isCompatibleWith(b) shouldBe true
      }
    }

    "cross-conflict" - {
      "A reads X + writes Y, B reads Y + writes X is incompatible" in {
        val a = IdFootprint(readIds = Set(id1), updatedIds = Set(id2))
        val b = IdFootprint(readIds = Set(id2), updatedIds = Set(id1))
        a.isCompatibleWith(b) shouldBe false
      }
    }
  }
}
