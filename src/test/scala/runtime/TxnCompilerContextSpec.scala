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
package runtime

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import bengal.stm.STM
import bengal.stm.model._
import bengal.stm.syntax.all._

class TxnCompilerContextSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  "read-only transaction" - {
    "correctly reads a TxnVar value" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVar   <- TxnVar.of(42)
            result <- tVar.get.commit
          } yield result
        }
        .asserting(_ shouldBe 42)
    }

    "correctly reads multiple TxnVars" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVar1 <- TxnVar.of(10)
            tVar2 <- TxnVar.of(20)
            result <- (for {
                        a <- tVar1.get
                        b <- tVar2.get
                      } yield a + b).commit
          } yield result
        }
        .asserting(_ shouldBe 30)
    }
  }

  "read-write transaction" - {
    "correctly reads then writes a TxnVar" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVar <- TxnVar.of(10)
            result <- (for {
                        v <- tVar.get
                        _ <- tVar.set(v + 5)
                      } yield v).commit
            updated <- tVar.get.commit
          } yield (result, updated)
        }
        .asserting(_ shouldBe (10, 15))
    }

    "correctly reads and writes separate TxnVars" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            src  <- TxnVar.of(100)
            dest <- TxnVar.of(0)
            _ <- (for {
                   v <- src.get
                   _ <- dest.set(v)
                 } yield ()).commit
            srcResult  <- src.get.commit
            destResult <- dest.get.commit
          } yield (srcResult, destResult)
        }
        .asserting(_ shouldBe (100, 100))
    }
  }

  "TxnVarMap transaction" - {
    "correctly reads and writes map values" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVarMap <- TxnVarMap.of(Map("a" -> 1, "b" -> 2))
            result <- (for {
                        v <- tVarMap.get("a")
                        _ <- tVarMap.set("c", 3)
                      } yield v).commit
            mapResult <- tVarMap.get.commit
          } yield (result, mapResult)
        }
        .asserting { case (readValue, finalMap) =>
          readValue shouldBe Some(1)
          finalMap shouldBe Map("a" -> 1, "b" -> 2, "c" -> 3)
        }
    }

    "correctly sets multiple keys in the same transaction" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVarMap <- TxnVarMap.of(Map("a" -> 1, "b" -> 2))
            _ <- (for {
                   _ <- tVarMap.set("a", 10)
                   _ <- tVarMap.set("b", 20)
                 } yield ()).commit
            result <- tVarMap.get.commit
          } yield result
        }
        .asserting(_ shouldBe Map("a" -> 10, "b" -> 20))
    }

    "correctly sets a key after reading the full map" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVarMap <- TxnVarMap.of(Map("a" -> 1, "b" -> 2))
            _ <- (for {
                   _ <- tVarMap.get
                   _ <- tVarMap.set("a", 99)
                 } yield ()).commit
            result <- tVarMap.get.commit
          } yield result
        }
        .asserting(_ shouldBe Map("a" -> 99, "b" -> 2))
    }

    "correctly modifies multiple keys in the same transaction" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVarMap <- TxnVarMap.of(Map("a" -> 1, "b" -> 2))
            _ <- (for {
                   _ <- tVarMap.modify("a", (v: Int) => v * 10)
                   _ <- tVarMap.modify("b", (v: Int) => v * 10)
                 } yield ()).commit
            result <- tVarMap.get.commit
          } yield result
        }
        .asserting(_ shouldBe Map("a" -> 10, "b" -> 20))
    }
  }

  "waitFor transaction" - {
    "correctly handles waitFor with satisfied predicate" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVar <- TxnVar.of(10)
            result <- (for {
                        v <- tVar.get
                        _ <- STM[IO].waitFor(v > 5)
                      } yield v).commit
          } yield result
        }
        .asserting(_ shouldBe 10)
    }

    "retries when predicate is not satisfied then succeeds" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVar <- TxnVar.of(0)
            readerFiber <- (for {
                             v <- tVar.get
                             _ <- STM[IO].waitFor(v > 0)
                           } yield v).commit.start
            _      <- tVar.set(42).commit
            result <- readerFiber.joinWithNever
          } yield result
        }
        .asserting(_ shouldBe 42)
    }
  }

  "handleErrorWith transaction" - {
    "correctly handles error recovery" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          STM[IO]
            .abort(new RuntimeException("error"))
            .flatMap(_ => STM[IO].pure("unreachable"))
            .handleErrorWith(_ => STM[IO].pure("recovered"))
            .commit
        }
        .asserting(_ shouldBe "recovered")
    }

    "retry propagates through handleErrorWith" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVar <- TxnVar.of(0)
            readerFiber <- (for {
                             v <- tVar.get
                             _ <- STM[IO].waitFor(v > 0)
                           } yield v)
                             .handleErrorWith(_ => STM[IO].pure(-1))
                             .commit
                             .start
            _      <- tVar.set(42).commit
            result <- readerFiber.joinWithNever
          } yield result
        }
        .asserting(_ shouldBe 42)
    }

    "handleErrorWith still recovers real errors" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          STM[IO]
            .abort(new RuntimeException("boom"))
            .flatMap(_ => STM[IO].pure("unreachable"))
            .handleErrorWith(_ => STM[IO].pure("recovered"))
            .commit
        }
        .asserting(_ shouldBe "recovered")
    }
  }

  "pure/delay transaction" - {
    "pure transaction with no var access succeeds" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          STM[IO].pure(99).commit
        }
        .asserting(_ shouldBe 99)
    }

    "delay transaction with no var access succeeds" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          STM[IO].delay(7 * 6).commit
        }
        .asserting(_ shouldBe 42)
    }

    "chained pure/delay without var access succeeds" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          (for {
            a <- STM[IO].pure(10)
            b <- STM[IO].delay(a + 5)
          } yield b).commit
        }
        .asserting(_ shouldBe 15)
    }
  }
}
