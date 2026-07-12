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

// DELIBERATELY OUTSIDE `ai.entrolution`. That is the entire point of this file.
//
// Every other test in this repository sits inside `package ai.entrolution`, where a
// relative `import bengal.stm.STM` resolves. A user's code is not inside that package,
// and for them the same import is `not found: object bengal`.
//
// So the README's Quick Start, the Bank Transfer example and the STM class Scaladoc all
// shipped with imports THAT DO NOT COMPILE for anybody but us, and the whole test suite
// stayed green. Nothing here could see it.
//
// This suite is compiled from a user's vantage point. It uses the fully-qualified imports
// exactly as the README prints them, and it will fail to COMPILE if the public entry points
// move or if the syntax import stops being sufficient. A compile error here is the finding.
package usercode

import scala.concurrent.duration._

import ai.entrolution.bengal.stm.STM
import ai.entrolution.bengal.stm.model._
import ai.entrolution.bengal.stm.syntax.all._
import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

class PublicApiSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  "the public API, imported the way the README says to import it" - {

    // README, "Quick Start", verbatim apart from the IOApp wrapper.
    "Quick Start compiles and runs" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            counter <- TxnVar.of[IO, Int](0)
            _       <- counter.modify(_ + 1).commit
            value   <- counter.get.commit
          } yield value
        }
        .timeout(30.seconds)
        .asserting(_ shouldBe 1)
    }

    // The combinators are members of STM[F], not free functions. The README's API table
    // used to print them bare (`pure(10)`, `waitFor(x > 10)`), which does not compile.
    "the STM[F] combinators are reachable as the API table prints them" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            flag <- TxnVar.of[IO, Boolean](true)
            out <- (for {
                     f <- flag.get
                     _ <- STM[IO].waitFor(f)
                     a <- STM[IO].pure(10)
                     b <- STM[IO].delay(a + 2)
                     c <- STM[IO].fromF(IO.pure(b + 1))
                     _ <- STM[IO].unit
                   } yield c).commit
          } yield out
        }
        .timeout(30.seconds)
        .asserting(_ shouldBe 13)
    }

    // README says `map.get(key)` returns None for an absent key and does NOT raise.
    // It used to claim the opposite, while the code and TxnVarMapSpec both said None.
    "an absent key reads as None rather than failing" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            map <- TxnVarMap.of[IO, String, Int](Map.empty)
            got <- map.get("never-created").commit
          } yield got
        }
        .timeout(30.seconds)
        .asserting(_ shouldBe None)
    }

    // README, "Static analysis and transaction footprints": the value argument to `set` is
    // suspended and the analyser never forces it, so a `.get` on a read-back-own-write is
    // FINE there. Only eagerly-forced positions (delay/fromF thunks, and map KEY thunks)
    // can throw during analysis. The README asserted the opposite for years, and its
    // "avoid it" advice was a no-op as a result.
    "a read-your-own-write .get in a set() value argument does not fail" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            inventory <- TxnVarMap.of[IO, String, Int](Map.empty)
            restock   <- TxnVarMap.of[IO, String, Int](Map.empty)
            _ <- (for {
                   _   <- inventory.set("sku", 10)
                   qty <- inventory.get("sku")
                   // `qty` is None during analysis. This `.get` would throw — but the
                   // analyser never evaluates a set() VALUE argument, so it never runs.
                   _ <- restock.set("sku", qty.get + 1)
                 } yield ()).commit
            out <- restock.get("sku").commit
          } yield out
        }
        .timeout(30.seconds)
        .asserting(_ shouldBe Some(11))
    }
  }
}
