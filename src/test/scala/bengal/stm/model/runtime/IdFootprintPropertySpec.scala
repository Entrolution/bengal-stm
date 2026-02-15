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

class IdFootprintPropertySpec extends AnyFreeSpec with ScalaCheckPropertyChecks with Matchers {

  private val genRawId: Gen[Int] = Gen.choose(0, 20)

  private val genTxnVarRuntimeId: Gen[TxnVarRuntimeId] = for {
    value     <- genRawId
    hasParent <- Gen.oneOf(true, false)
    parentVal <- genRawId
  } yield
    if (hasParent) TxnVarRuntimeId(value, Some(TxnVarRuntimeId(parentVal)))
    else TxnVarRuntimeId(value)

  private val genIdSet: Gen[Set[TxnVarRuntimeId]] =
    Gen.choose(0, 5).flatMap(n => Gen.listOfN(n, genTxnVarRuntimeId).map(_.toSet))

  private val genIdFootprint: Gen[IdFootprint] = for {
    reads  <- genIdSet
    writes <- genIdSet
  } yield IdFootprint(reads, writes)

  private val genReadOnlyFootprint: Gen[IdFootprint] =
    genIdSet.map(reads => IdFootprint(reads, Set.empty))

  "isCompatibleWith is symmetric" in {
    forAll(genIdFootprint, genIdFootprint) { (a, b) =>
      a.isCompatibleWith(b) shouldBe b.isCompatibleWith(a)
    }
  }

  "IdFootprint.empty is compatible with any footprint" in {
    forAll(genIdFootprint) { fp =>
      IdFootprint.empty.isCompatibleWith(fp) shouldBe true
    }
  }

  "read-only footprint is self-compatible" in {
    forAll(genReadOnlyFootprint) { fp =>
      fp.isCompatibleWith(fp) shouldBe true
    }
  }

  "mergeWith is commutative" in {
    forAll(genIdFootprint, genIdFootprint) { (a, b) =>
      val ab = a.mergeWith(b)
      val ba = b.mergeWith(a)
      ab.readIds shouldBe ba.readIds
      ab.updatedIds shouldBe ba.updatedIds
    }
  }

  "mergeWith is associative" in {
    forAll(genIdFootprint, genIdFootprint, genIdFootprint) { (a, b, c) =>
      val left  = a.mergeWith(b).mergeWith(c)
      val right = a.mergeWith(b.mergeWith(c))
      left.readIds shouldBe right.readIds
      left.updatedIds shouldBe right.updatedIds
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
}
