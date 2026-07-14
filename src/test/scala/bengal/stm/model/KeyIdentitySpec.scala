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
package bengal.stm.model

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all._
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

/** The key-identity contract of the runtime-id machinery.
  *
  * Conflict detection identifies a map slot by the KEY'S OWN EQUALITY — the same `equals`/`hashCode` the value store
  * uses — never by a rendering of the key. These tests pin the two directions in which a rendering-based identity
  * (`toString`, historically) diverges from equality, plus the two allocator invariants everything downstream leans on:
  * raw id values are globally unique across entity and existential ids, and a key's existential id is stable for the
  * lifetime of the map, deletes included (a parked transaction's footprint holds the id, so evict-and-reallocate would
  * un-match its wakeup).
  *
  * White-box on purpose: it asserts on `getRuntimeId`/`runtimeId` directly, so a regression is a red assertion here
  * even when the concurrent consequences (lost updates, false conflicts) would need a racing schedule to observe.
  */
class KeyIdentitySpec extends AsyncFreeSpec with AsyncIOSpec with Matchers with StmSuite {

  /** Distinct by reference equality (the default), identical by toString. */
  final private class OpaqueKey {
    override def toString: String = "opaque"
  }

  "map-key runtime ids" - {

    "equal keys with different renderings share ONE id" in withRuntime { implicit stm =>
      // scala.math.BigDecimal("1.0") == BigDecimal("1.00") with equal hashCodes is a SCALA
      // property (scale-insensitive equality); java.math.BigDecimal is NOT equal there. The
      // pair is exactly an equal-by-equals, different-by-toString key.
      for {
        tVarMap <- TxnVarMap.of(Map.empty[BigDecimal, Int])
        rid1    <- tVarMap.getRuntimeId(BigDecimal("1.0"))
        rid2    <- tVarMap.getRuntimeId(BigDecimal("1.00"))
      } yield rid1 shouldBe rid2
    }

    "distinct keys with identical renderings get DISTINCT ids" in withRuntime { implicit stm =>
      val k1 = new OpaqueKey
      val k2 = new OpaqueKey
      for {
        tVarMap <- TxnVarMap.of(Map.empty[OpaqueKey, Int])
        rid1    <- tVarMap.getRuntimeId(k1)
        rid2    <- tVarMap.getRuntimeId(k2)
      } yield {
        rid1 should not be rid2
        rid1.value should not be rid2.value
      }
    }

    "existential ids never collide with entity ids, raw values included" in withRuntime { implicit stm =>
      // The footprint relation compares RAW values with the parent stripped
      // (IdFootprint.combinedRawIds), so uniqueness must hold across the two kinds
      // of id, not merely within each.
      for {
        tVar    <- TxnVar.of(0)
        tVarMap <- TxnVarMap.of(Map("live" -> 1))
        ridA    <- tVarMap.getRuntimeId("live")
        ridB    <- tVarMap.getRuntimeId("absent")
      } yield {
        val rawValues = List(tVar.runtimeId.value, tVarMap.runtimeId.value, ridA.value, ridB.value)
        rawValues.distinct should have size 4
      }
    }

    "a key's existential id survives delete and reinsert unchanged" in withRuntime { implicit stm =>
      for {
        tVarMap <- TxnVarMap.of(Map.empty[String, Int])
        before  <- tVarMap.getRuntimeId("k")
        _       <- tVarMap.addOrUpdate("k", 1)
        _       <- tVarMap.delete("k")
        _       <- tVarMap.addOrUpdate("k", 2)
        after   <- tVarMap.getRuntimeId("k")
      } yield after shouldBe before
    }

    "an existential id carries the map's runtime id as its parent" in withRuntime { implicit stm =>
      for {
        tVarMap <- TxnVarMap.of(Map.empty[String, Int])
        rid     <- tVarMap.getRuntimeId("k")
      } yield rid.parent shouldBe Some(tVarMap.runtimeId)
    }

    "racing first touches of one key converge on one id" in withRuntime { implicit stm =>
      // Exercises the allocate-and-publish race arm deterministically: every key is
      // fresh, so BOTH fibers take the allocation branch and the loser must adopt the
      // winner's published id. A per-fiber id here is a split footprint — a missed
      // conflict — so the pair must be equal on every round.
      for {
        tVarMap <- TxnVarMap.of(Map.empty[String, Int])
        pairs <- (1 to 32).toList.traverse { i =>
                   (tVarMap.getRuntimeId(s"fresh-$i"), tVarMap.getRuntimeId(s"fresh-$i")).parTupled
                 }
      } yield pairs.foreach { case (a, b) => a shouldBe b }
    }

    "same-named keys in two maps get distinct ids" in withRuntime { implicit stm =>
      for {
        m1   <- TxnVarMap.of(Map.empty[String, Int])
        m2   <- TxnVarMap.of(Map.empty[String, Int])
        rid1 <- m1.getRuntimeId("k")
        rid2 <- m2.getRuntimeId("k")
      } yield {
        rid1 should not be rid2
        rid1.value should not be rid2.value
      }
    }

    "id allocation follows first-touch order across maps" in withRuntime { implicit stm =>
      // Pins the precondition CommitLockOrderSpec's pre-touch phase is built on: an
      // id is issued at the key's FIRST reference, so touching (m2, k) before
      // (m1, k) yields m2's id below m1's, and the reverse touch order reverses the
      // inequality. Without this property the pre-touch could not manufacture the
      // opposed lock-acquisition orders that keep that suite a real H2 guard.
      def touchInOrder(first: TxnVarMap[IO, String, Int], second: TxnVarMap[IO, String, Int], key: String) =
        for {
          firstId  <- first.getRuntimeId(key)
          secondId <- second.getRuntimeId(key)
        } yield (firstId, secondId)

      for {
        m1    <- TxnVarMap.of(Map.empty[String, Int])
        m2    <- TxnVarMap.of(Map.empty[String, Int])
        viaM2 <- touchInOrder(m2, m1, "odd-style")
        viaM1 <- touchInOrder(m1, m2, "even-style")
      } yield {
        val (m2First, m1Second) = viaM2
        val (m1First, m2Second) = viaM1
        m2First.value should be < m1Second.value
        m1First.value should be < m2Second.value
      }
    }
  }
}
