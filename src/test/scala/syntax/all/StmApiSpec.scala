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

import bengal.stm.STM
import bengal.stm.model.*
import bengal.stm.syntax.all.*

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

class StmApiSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  "delay" - {
    "yield argument value" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            result <- STM[IO].delay("foo").commit
          } yield result
        }
        .asserting(_ shouldBe "foo")
    }
  }

  "pure" - {
    "yield argument value" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            result <- STM[IO].pure("foo").commit
          } yield result
        }
        .asserting(_ shouldBe "foo")
    }
  }

  "raiseError" - {
    "throw an error when run" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            result <-
              STM[IO].abort(new RuntimeException("test error")).commit.attempt
          } yield result
        }
        .asserting(_.left.map(_.getMessage()) shouldBe Left("test error"))
    }
  }

  "handleErrorWith" - {
    "recover from an error" in {
      val mockError = new RuntimeException("mock error")

      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            result <- STM[IO]
                        .abort(mockError)
                        .flatMap(_ => STM[IO].delay("test"))
                        .handleErrorWith(ex => STM[IO].delay(ex.getMessage))
                        .commit
          } yield result
        }
        .asserting(_ shouldBe mockError.getMessage)
    }

    "bypass mutations from the error transaction" in {
      val baseMap = Map("foo" -> 42, "bar" -> 27, "baz" -> 18)

      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVarMap <- TxnVarMap.of(baseMap)
            result <- (for {
                        innerResult <- tVarMap.get("foo")
                        _           <- tVarMap.modify("foo", _ + 3)
                        _           <- STM[IO].abort(new RuntimeException("fake exception"))
                        _           <- tVarMap.modify("foo", _ + 2)
                      } yield innerResult).handleErrorWith { _ =>
                        for {
                          _           <- tVarMap.modify("foo", _ + 2)
                          innerResult <- tVarMap.get("foo")
                        } yield innerResult
                      }.commit
          } yield result
        }
        .asserting(_ shouldBe Some(44))
    }
  }

  "waitFor" - {
    "should complete when predicate is satisfied" in {
      def program1(input: TxnVar[IO, Int])(implicit stm: STM[IO]): Txn[Int] =
        for {
          result <- input.get
          _      <- STM[IO].waitFor(result > 3)
        } yield result

      def program2(input: TxnVar[IO, Int])(implicit stm: STM[IO]): Txn[Unit] =
        input.set(5)

      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVar <- TxnVar.of(1)
            result <- for {
                        resFib      <- program1(tVar).commit.start
                        _           <- program2(tVar).commit.start
                        innerResult <- resFib.joinWithNever
                      } yield innerResult
          } yield result
        }
        .asserting(_ shouldBe 5)
    }
  }
}
