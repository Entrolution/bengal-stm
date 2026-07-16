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
package syntax.all

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import bengal.stm.STM
import bengal.stm.model._
import bengal.stm.syntax.all._

class FVariantSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers with StmSuite {

  "TxnVar.setF" - {
    "set value via effect" in {
      withRuntime { implicit stm =>
        for {
          tVar   <- TxnVar.of(10)
          _      <- tVar.setF(IO.pure(42)).commit
          result <- tVar.get.commit
        } yield result
      }
        .asserting(_ shouldBe 42)
    }
  }

  "TxnVar.modifyF" - {
    "modify value via effectful function" in {
      withRuntime { implicit stm =>
        for {
          tVar   <- TxnVar.of(10)
          _      <- tVar.modifyF(v => IO.pure(v + 1)).commit
          result <- tVar.get.commit
        } yield result
      }
        .asserting(_ shouldBe 11)
    }
  }

  "TxnVarMap.setF(F[Map])" - {
    "set entire map via effect" in {
      val newMap = Map("x" -> 1, "y" -> 2)

      withRuntime { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(Map("a" -> 10))
          _       <- tVarMap.setF(IO.pure(newMap)).commit
          result  <- tVarMap.get.commit
        } yield result
      }
        .asserting(_ shouldBe newMap)
    }
  }

  "TxnVarMap.modifyF" - {
    "modify entire map via effectful function" in {
      val baseMap = Map("a" -> 1, "b" -> 2)

      withRuntime { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(baseMap)
          _       <- tVarMap.modifyF(m => IO.pure(m.map(kv => kv._1 -> (kv._2 * 10)))).commit
          result  <- tVarMap.get.commit
        } yield result
      }
        .asserting(_ shouldBe Map("a" -> 10, "b" -> 20))
    }
  }

  "TxnVarMap.setF(key, F[value])" - {
    "set key-value via effect" in {
      withRuntime { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(Map("a" -> 1))
          _       <- tVarMap.setF("a", IO.pure(99)).commit
          result  <- tVarMap.get("a").commit
        } yield result
      }
        .asserting(_ shouldBe Some(99))
    }

    "create new key via effect" in {
      withRuntime { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(Map("a" -> 1))
          _       <- tVarMap.setF("b", IO.pure(42)).commit
          result  <- tVarMap.get("b").commit
        } yield result
      }
        .asserting(_ shouldBe Some(42))
    }
  }

  "TxnVarMap.modifyF(key, f)" - {
    "modify key-value via effectful function" in {
      withRuntime { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(Map("a" -> 10))
          _       <- tVarMap.modifyF("a", v => IO.pure(v + 5)).commit
          result  <- tVarMap.get("a").commit
        } yield result
      }
        .asserting(_ shouldBe Some(15))
    }

    // The F-variant fails on an absent key exactly like pure modify (both build
    // the same ADT), and recovery goes through handleErrorWith — the contract
    // its scaladoc states. Pinned here so the variants cannot silently diverge.
    "fail the transaction on an absent key, recoverably" in {
      withRuntime { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(Map("a" -> 10))
          result <- tVarMap
                      .modifyF("missing", v => IO.pure(v + 5))
                      .map(_ => "not-recovered")
                      .handleErrorWith(ex => STM[IO].pure(ex.getMessage))
                      .commit
        } yield result
      }
        .asserting(_ should include("missing"))
    }
  }

  "handleErrorWithF" - {
    "recover from error via effectful handler" in {
      val mockError = new RuntimeException("test error")

      withRuntime { implicit stm =>
        for {
          result <- STM[IO]
                      .abort(mockError)
                      .flatMap(_ => STM[IO].delay("unreachable"))
                      .handleErrorWithF(ex => IO.pure(STM[IO].pure(ex.getMessage)))
                      .commit
        } yield result
      }
        .asserting(_ shouldBe "test error")
    }

    "bypass mutations from the error transaction" in {
      withRuntime { implicit stm =>
        for {
          tVar <- TxnVar.of(100)
          result <- (for {
                      _ <- tVar.set(200)
                      _ <- STM[IO].abort(new RuntimeException("fail"))
                      v <- tVar.get
                    } yield v).handleErrorWithF { _ =>
                      IO.pure(tVar.get)
                    }.commit
        } yield result
      }
        .asserting(_ shouldBe 100)
    }
  }
}
