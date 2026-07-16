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

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{ IO, Ref }
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import bengal.stm.model.runtime._

/** The model constructs against a bare counter — no scheduler, no runtime cake. That is the seam
  * [[bengal.stm.model.runtime.TxnIdAllocator]] exists for, and this spec is the pin: if `TxnVar.of` or `TxnVarMap.of`
  * ever grows a dependency on the `STM` cake again, the stub below stops satisfying their bounds and this file stops
  * compiling.
  *
  * The behavioural half checks the identity contract the allocator carries: every entity and every map key draws from
  * ONE counter, so ids never alias across kinds, a key's id is stable across lookups, and each key id is parented by
  * its map (the hierarchy the conflict relation's one-hop tests walk).
  */
class TxnIdAllocatorSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  private def stubAllocator: IO[TxnIdAllocator[IO]] =
    Ref.of[IO, Long](0L).map { counter =>
      new TxnIdAllocator[IO] {
        override private[stm] val txnVarIdGen: Ref[IO, TxnVarId] = counter
      }
    }

  "a TxnVar builds against a stub allocator and draws its id from it" in {
    stubAllocator
      .flatMap { implicit allocator =>
        for {
          txnVar <- TxnVar.of(42)
          value  <- txnVar.get
        } yield (txnVar.id, value)
      }
      .asserting { case (id, value) =>
        id shouldBe 1L
        value shouldBe 42
      }
  }

  "a TxnVarMap mints stable, parent-wired key ids from the same counter as its entities" in {
    stubAllocator
      .flatMap { implicit allocator =>
        for {
          txnVar   <- TxnVar.of(0)
          map      <- TxnVarMap.of(Map("a" -> 1))
          xFirst   <- map.getRuntimeId("x")
          xSecond  <- map.getRuntimeId("x")
          yFirst   <- map.getRuntimeId("y")
          aTxnVar  <- map.getTxnVar("a")
          allRawIds = Set(txnVar.runtimeId.value, map.runtimeId.value, aTxnVar.get.runtimeId.value, xFirst.value, yFirst.value)
        } yield (xFirst, xSecond, yFirst, map.runtimeId, allRawIds)
      }
      .asserting { case (xFirst, xSecond, yFirst, mapRuntimeId, allRawIds) =>
        xSecond shouldBe xFirst
        yFirst should not be xFirst
        xFirst.parent shouldBe Some(mapRuntimeId)
        yFirst.parent shouldBe Some(mapRuntimeId)
        allRawIds should have size 5
      }
  }
}
