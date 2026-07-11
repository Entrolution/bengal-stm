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
package bengal.stm.runtime

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import bengal.stm.STM
import bengal.stm.model._

class TxnLogEntrySpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  private def withRuntime[A](f: STM[IO] => IO[A]): IO[A] =
    STM.runtime[IO].flatMap(f)

  "TxnLogReadOnlyVarEntry" - {

    "get returns initial value" in withRuntime { implicit stm =>
      for {
        tvar <- TxnVar.of(42)
      } yield {
        val entry = stm.TxnLogReadOnlyVarEntry(42, tvar)
        entry.get shouldBe 42
      }
    }

    "set with same value returns unchanged entry" in withRuntime { implicit stm =>
      for {
        tvar <- TxnVar.of(42)
      } yield {
        val entry  = stm.TxnLogReadOnlyVarEntry(42, tvar)
        val result = entry.set(42)
        result shouldBe entry
      }
    }

    "set with different value returns TxnLogUpdateVarEntry" in withRuntime { implicit stm =>
      for {
        tvar <- TxnVar.of(42)
      } yield {
        val entry  = stm.TxnLogReadOnlyVarEntry(42, tvar)
        val result = entry.set(99)
        result shouldBe stm.TxnLogUpdateVarEntry(42, 99, tvar)
      }
    }

    "commit does not modify underlying value" in withRuntime { implicit stm =>
      for {
        tvar <- TxnVar.of(42)
        entry = stm.TxnLogReadOnlyVarEntry(42, tvar)
        _     <- entry.commit
        value <- tvar.get
      } yield value shouldBe 42
    }

    "isDirty returns false" in withRuntime { implicit stm =>
      for {
        tvar <- TxnVar.of(42)
        entry = stm.TxnLogReadOnlyVarEntry(42, tvar)
        dirty <- entry.isDirty
      } yield dirty shouldBe false
    }

    "lock returns None" in withRuntime { implicit stm =>
      for {
        tvar <- TxnVar.of(42)
        entry = stm.TxnLogReadOnlyVarEntry(42, tvar)
        lock <- entry.lock
      } yield lock shouldBe None
    }

    "idFootprint contains readIds only" in withRuntime { implicit stm =>
      for {
        tvar <- TxnVar.of(42)
        entry = stm.TxnLogReadOnlyVarEntry(42, tvar)
        footprint <- entry.idFootprint
      } yield {
        footprint.readIds shouldBe Set(tvar.runtimeId)
        footprint.updatedIds shouldBe empty
      }
    }
  }

  "TxnLogUpdateVarEntry" - {

    "get returns current value" in withRuntime { implicit stm =>
      for {
        tvar <- TxnVar.of(42)
      } yield {
        val entry = stm.TxnLogUpdateVarEntry(42, 99, tvar)
        entry.get shouldBe 99
      }
    }

    "set with value different from initial returns TxnLogUpdateVarEntry" in withRuntime { implicit stm =>
      for {
        tvar <- TxnVar.of(42)
      } yield {
        val entry  = stm.TxnLogUpdateVarEntry(42, 99, tvar)
        val result = entry.set(77)
        result shouldBe stm.TxnLogUpdateVarEntry(42, 77, tvar)
      }
    }

    "set with initial value returns TxnLogReadOnlyVarEntry" in withRuntime { implicit stm =>
      for {
        tvar <- TxnVar.of(42)
      } yield {
        val entry  = stm.TxnLogUpdateVarEntry(42, 99, tvar)
        val result = entry.set(42)
        result shouldBe stm.TxnLogReadOnlyVarEntry(42, tvar)
      }
    }

    "commit writes current value to underlying TxnVar" in withRuntime { implicit stm =>
      for {
        tvar <- TxnVar.of(42)
        entry = stm.TxnLogUpdateVarEntry(42, 99, tvar)
        _     <- entry.commit
        value <- tvar.get
      } yield value shouldBe 99
    }

    "isDirty reflects external modifications" in withRuntime { implicit stm =>
      for {
        tvar <- TxnVar.of(42)
        entry = stm.TxnLogUpdateVarEntry(42, 99, tvar)
        dirty1 <- entry.isDirty
        _      <- tvar.set(50)
        dirty2 <- entry.isDirty
      } yield {
        dirty1 shouldBe false
        dirty2 shouldBe true
      }
    }

    "lock returns the commitLock paired with its OWNER's runtime id" in withRuntime { implicit stm =>
      for {
        tvar <- TxnVar.of(42)
        entry = stm.TxnLogUpdateVarEntry(42, 99, tvar)
        lock <- entry.lock
      } yield lock shouldBe Some((tvar.runtimeId, tvar.commitLock))
    }

    "idFootprint contains updatedIds only" in withRuntime { implicit stm =>
      for {
        tvar <- TxnVar.of(42)
        entry = stm.TxnLogUpdateVarEntry(42, 99, tvar)
        footprint <- entry.idFootprint
      } yield {
        footprint.readIds shouldBe empty
        footprint.updatedIds shouldBe Set(tvar.runtimeId)
      }
    }
  }

  "TxnLogReadOnlyVarMapStructureEntry" - {

    "get returns initial map" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1, "b" -> 2))
      } yield {
        val entry = stm.TxnLogReadOnlyVarMapStructureEntry(Map("a" -> 1, "b" -> 2), tvarMap)
        entry.get shouldBe Map("a" -> 1, "b" -> 2)
      }
    }

    "set with same map returns unchanged entry" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
      } yield {
        val entry  = stm.TxnLogReadOnlyVarMapStructureEntry(Map("a" -> 1), tvarMap)
        val result = entry.set(Map("a" -> 1))
        result shouldBe entry
      }
    }

    "set with different map returns TxnLogUpdateVarMapStructureEntry" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
      } yield {
        val entry  = stm.TxnLogReadOnlyVarMapStructureEntry(Map("a" -> 1), tvarMap)
        val result = entry.set(Map("a" -> 2))
        result shouldBe stm.TxnLogUpdateVarMapStructureEntry(Map("a" -> 1), Map("a" -> 2), tvarMap)
      }
    }

    "commit does not modify underlying map" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
        entry = stm.TxnLogReadOnlyVarMapStructureEntry(Map("a" -> 1), tvarMap)
        _     <- entry.commit
        value <- tvarMap.get
      } yield value shouldBe Map("a" -> 1)
    }

    "isDirty returns false" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
        entry = stm.TxnLogReadOnlyVarMapStructureEntry(Map("a" -> 1), tvarMap)
        dirty <- entry.isDirty
      } yield dirty shouldBe false
    }

    "lock returns None" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
        entry = stm.TxnLogReadOnlyVarMapStructureEntry(Map("a" -> 1), tvarMap)
        lock <- entry.lock
      } yield lock shouldBe None
    }

    "idFootprint contains readIds only" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
        entry = stm.TxnLogReadOnlyVarMapStructureEntry(Map("a" -> 1), tvarMap)
        footprint <- entry.idFootprint
      } yield {
        footprint.readIds shouldBe Set(tvarMap.runtimeId)
        footprint.updatedIds shouldBe empty
      }
    }
  }

  "TxnLogUpdateVarMapStructureEntry" - {

    "get returns current map" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
      } yield {
        val entry = stm.TxnLogUpdateVarMapStructureEntry(Map("a" -> 1), Map("a" -> 2), tvarMap)
        entry.get shouldBe Map("a" -> 2)
      }
    }

    "set with value different from initial returns TxnLogUpdateVarMapStructureEntry" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
      } yield {
        val entry  = stm.TxnLogUpdateVarMapStructureEntry(Map("a" -> 1), Map("a" -> 2), tvarMap)
        val result = entry.set(Map("a" -> 3))
        result shouldBe stm.TxnLogUpdateVarMapStructureEntry(Map("a" -> 1), Map("a" -> 3), tvarMap)
      }
    }

    "set with initial value returns TxnLogReadOnlyVarMapStructureEntry" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
      } yield {
        val entry  = stm.TxnLogUpdateVarMapStructureEntry(Map("a" -> 1), Map("a" -> 2), tvarMap)
        val result = entry.set(Map("a" -> 1))
        result shouldBe stm.TxnLogReadOnlyVarMapStructureEntry(Map("a" -> 1), tvarMap)
      }
    }

    "commit is a no-op" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
        entry = stm.TxnLogUpdateVarMapStructureEntry(Map("a" -> 1), Map("a" -> 2), tvarMap)
        _     <- entry.commit
        value <- tvarMap.get
      } yield value shouldBe Map("a" -> 1)
    }

    "isDirty reflects external modifications" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
        entry = stm.TxnLogUpdateVarMapStructureEntry(Map("a" -> 1), Map("a" -> 2), tvarMap)
        dirty1 <- entry.isDirty
        _      <- tvarMap.addOrUpdate("a", 99)
        dirty2 <- entry.isDirty
      } yield {
        dirty1 shouldBe false
        dirty2 shouldBe true
      }
    }

    "lock returns the map's commitLock paired with the MAP's runtime id" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
        entry = stm.TxnLogUpdateVarMapStructureEntry(Map("a" -> 1), Map("a" -> 2), tvarMap)
        lock <- entry.lock
      } yield lock shouldBe Some((tvarMap.runtimeId, tvarMap.commitLock))
    }

    "idFootprint contains updatedIds only" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
        entry = stm.TxnLogUpdateVarMapStructureEntry(Map("a" -> 1), Map("a" -> 2), tvarMap)
        footprint <- entry.idFootprint
      } yield {
        footprint.readIds shouldBe empty
        footprint.updatedIds shouldBe Set(tvarMap.runtimeId)
      }
    }
  }

  "TxnLogReadOnlyVarMapEntry" - {

    "get returns initial value" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
      } yield {
        val entry = stm.TxnLogReadOnlyVarMapEntry("a", Some(1), tvarMap)
        entry.get shouldBe Some(1)
      }
    }

    "set with same value returns unchanged entry" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
      } yield {
        val entry  = stm.TxnLogReadOnlyVarMapEntry("a", Some(1), tvarMap)
        val result = entry.set(Some(1))
        result shouldBe entry
      }
    }

    "set with different value returns TxnLogUpdateVarMapEntry" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
      } yield {
        val entry  = stm.TxnLogReadOnlyVarMapEntry("a", Some(1), tvarMap)
        val result = entry.set(Some(2))
        result shouldBe stm.TxnLogUpdateVarMapEntry("a", Some(1), Some(2), tvarMap)
      }
    }

    "commit does not modify underlying map" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
        entry = stm.TxnLogReadOnlyVarMapEntry("a", Some(1), tvarMap)
        _     <- entry.commit
        value <- tvarMap.get("a")
      } yield value shouldBe Some(1)
    }

    "isDirty returns false" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
        entry = stm.TxnLogReadOnlyVarMapEntry("a", Some(1), tvarMap)
        dirty <- entry.isDirty
      } yield dirty shouldBe false
    }

    "lock returns None" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
        entry = stm.TxnLogReadOnlyVarMapEntry("a", Some(1), tvarMap)
        lock <- entry.lock
      } yield lock shouldBe None
    }

    "idFootprint contains readIds only" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
        entry = stm.TxnLogReadOnlyVarMapEntry("a", Some(1), tvarMap)
        footprint <- entry.idFootprint
        rid       <- tvarMap.getRuntimeId("a")
      } yield {
        footprint.readIds shouldBe Set(rid)
        footprint.updatedIds shouldBe empty
      }
    }
  }

  "TxnLogUpdateVarMapEntry" - {

    "get returns current value" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
      } yield {
        val entry = stm.TxnLogUpdateVarMapEntry("a", Some(1), Some(2), tvarMap)
        entry.get shouldBe Some(2)
      }
    }

    "set with value different from initial returns TxnLogUpdateVarMapEntry" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
      } yield {
        val entry  = stm.TxnLogUpdateVarMapEntry("a", Some(1), Some(2), tvarMap)
        val result = entry.set(Some(3))
        result shouldBe stm.TxnLogUpdateVarMapEntry("a", Some(1), Some(3), tvarMap)
      }
    }

    "set with initial value returns TxnLogReadOnlyVarMapEntry" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
      } yield {
        val entry  = stm.TxnLogUpdateVarMapEntry("a", Some(1), Some(2), tvarMap)
        val result = entry.set(Some(1))
        result shouldBe stm.TxnLogReadOnlyVarMapEntry("a", Some(1), tvarMap)
      }
    }

    "commit with Some current adds or updates value" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
        entry = stm.TxnLogUpdateVarMapEntry("a", Some(1), Some(99), tvarMap)
        _     <- entry.commit
        value <- tvarMap.get("a")
      } yield value shouldBe Some(99)
    }

    "commit with None current and Some initial deletes key" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
        entry = stm.TxnLogUpdateVarMapEntry[String, Int]("a", Some(1), None, tvarMap)
        _     <- entry.commit
        value <- tvarMap.get("a")
      } yield value shouldBe None
    }

    "commit with None current and None initial is a no-op" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
        entry = stm.TxnLogUpdateVarMapEntry[String, Int]("b", None, None, tvarMap)
        _     <- entry.commit
        value <- tvarMap.get("b")
      } yield value shouldBe None
    }

    "isDirty reflects external modifications" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
        entry = stm.TxnLogUpdateVarMapEntry("a", Some(1), Some(2), tvarMap)
        dirty1 <- entry.isDirty
        _      <- tvarMap.addOrUpdate("a", 99)
        dirty2 <- entry.isDirty
      } yield {
        dirty1 shouldBe false
        dirty2 shouldBe true
      }
    }

    "isDirty detects new key when initial was None" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
        entry = stm.TxnLogUpdateVarMapEntry[String, Int]("b", None, Some(5), tvarMap)
        dirty1 <- entry.isDirty
        _      <- tvarMap.addOrUpdate("b", 10)
        dirty2 <- entry.isDirty
      } yield {
        dirty1 shouldBe false
        dirty2 shouldBe true
      }
    }

    // This test and the new-key one below are the H2 fix at unit level. A map
    // entry's lock owner is NOT recoverable from the id the log keys it by, so
    // the owner has to travel WITH the lock — which is why `lock` returns a
    // pair. Here the key EXISTS, so the owner is the entry's own TxnVar.
    "lock returns the entry TxnVar's commitLock paired with THAT TxnVar's runtime id" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
        entry = stm.TxnLogUpdateVarMapEntry("a", Some(1), Some(2), tvarMap)
        lock    <- entry.lock
        oTxnVar <- tvarMap.getTxnVar("a")
      } yield lock shouldBe oTxnVar.map(txnVar => (txnVar.runtimeId, txnVar.commitLock))
    }

    "idFootprint contains updatedIds only" in withRuntime { implicit stm =>
      for {
        tvarMap <- TxnVarMap.of(Map("a" -> 1))
        entry = stm.TxnLogUpdateVarMapEntry("a", Some(1), Some(2), tvarMap)
        footprint <- entry.idFootprint
        rid       <- tvarMap.getRuntimeId("a")
      } yield {
        footprint.readIds shouldBe empty
        footprint.updatedIds shouldBe Set(rid)
      }
    }

    // A NEW key has no TxnVar yet, so it falls back to the MAP's structural
    // commitLock — and the owner id must be the MAP's runtimeId, NOT the key's
    // existential id. That distinction is the whole of H2: the log keys this
    // entry by the existential id, so sorting log entries ordered acquisitions
    // by a hash of the KEY while the lock taken belonged to the MAP
    // (specs/commit/CommitH2.cfg, NoWaitsForCycle).
    "lock for a NEW key falls back to the map's commitLock paired with the MAP's runtime id" in withRuntime {
      implicit stm =>
        for {
          tvarMap <- TxnVarMap.of(Map("a" -> 1))
          entry = stm.TxnLogUpdateVarMapEntry[String, Int]("newkey", None, Some(5), tvarMap)
          lock          <- entry.lock
          existentialId <- tvarMap.getRuntimeId("newkey")
        } yield {
          lock shouldBe Some((tvarMap.runtimeId, tvarMap.commitLock))
          // and emphatically NOT the existential id the log keys it by
          lock.map(_._1) should not be Some(existentialId)
        }
    }
  }
}
