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

class StmApiSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers with StmSuite {

  "delay" - {
    "yield argument value" in {
      withRuntime { implicit stm =>
        for {
          result <- STM[IO].delay("foo").commit
        } yield result
      }
        .asserting(_ shouldBe "foo")
    }
  }

  "pure" - {
    "yield argument value" in {
      withRuntime { implicit stm =>
        for {
          result <- STM[IO].pure("foo").commit
        } yield result
      }
        .asserting(_ shouldBe "foo")
    }
  }

  "raiseError" - {
    "throw an error when run" in {
      withRuntime { implicit stm =>
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

      withRuntime { implicit stm =>
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

      withRuntime { implicit stm =>
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

  "error identity" - {
    // These assert on the EXACT exception instance. Weaker assertions
    // (`a[RuntimeException]`) would also pass for the internal error carrier or
    // for a masking ClassCastException, which is precisely the failure mode
    // they pin against.

    "abort followed by a typed bind surfaces the abort's own exception" in {
      val boom = new RuntimeException("abort-then-bind")

      withRuntime { implicit stm =>
        (for {
          _ <- STM[IO].abort(boom)
          v <- STM[IO].pure("x")
        } yield v).commit.attempt
      }
        .asserting(_.left.toOption.get shouldBe theSameInstanceAs(boom))
    }

    "a failing fromF followed by a typed map surfaces the effect's own exception" in {
      val boom = new RuntimeException("fromF-then-map")

      withRuntime { implicit stm =>
        STM[IO].fromF(IO.raiseError[String](boom)).map(_.length).commit.attempt
      }
        .asserting(_.left.toOption.get shouldBe theSameInstanceAs(boom))
    }

    "handleErrorWith recovers a failing fromF even through an intervening map" in {
      val boom = new RuntimeException("fromF-map-handle")

      withRuntime { implicit stm =>
        STM[IO]
          .fromF(IO.raiseError[String](boom))
          .map(_.length)
          .handleErrorWith(ex => STM[IO].pure(ex.getMessage.length))
          .commit
      }
        .asserting(_ shouldBe boom.getMessage.length)
    }

    "handleErrorWith recovers a remove of an absent key" in {
      withRuntime { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(Map.empty[String, Int])
          result <- tVarMap
                      .remove("missing")
                      .map(_ => "not-recovered")
                      .handleErrorWith(ex => STM[IO].pure(ex.getMessage))
                      .commit
        } yield result
      }
        .asserting(_ should include("missing"))
    }

    "handleErrorWith recovers a remove of a key this transaction already removed" in {
      withRuntime { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(Map("k" -> 1))
          _ <- (for {
                 _ <- tVarMap.remove("k")
                 _ <- tVarMap
                        .remove("k")
                        .handleErrorWith(_ => STM[IO].unit)
               } yield ()).commit
          after <- tVarMap.get.commit
        } yield after
      }
        // Recovery runs against the PRE-BLOCK log, which already stages the first
        // remove: the transaction commits with the key deleted exactly once.
        .asserting(_ shouldBe Map.empty[String, Int])
    }

    "handleErrorWith recovers a modify of an absent key" in {
      withRuntime { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(Map.empty[String, Int])
          result <- tVarMap
                      .modify("missing", (v: Int) => v + 1)
                      .map(_ => "not-recovered")
                      .handleErrorWith(ex => STM[IO].pure(ex.getMessage))
                      .commit
        } yield result
      }
        .asserting(_ should include("missing"))
    }

    "a modify of an absent key followed by a typed fromF surfaces the original message" in {
      withRuntime { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(Map.empty[String, Int])
          result <- (for {
                      _ <- tVarMap.modify("missing", (v: Int) => v + 1)
                      v <- STM[IO].fromF(IO.pure("x"))
                      n <- STM[IO].pure(v.length)
                    } yield n).commit.attempt
        } yield result
      }
        .asserting(_.left.toOption.get.getMessage should include("missing"))
    }

    "a remove of an absent key followed by a typed fromF surfaces the original message" in {
      withRuntime { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(Map.empty[String, Int])
          result <- (for {
                      _ <- tVarMap.remove("missing")
                      v <- STM[IO].fromF(IO.pure("x"))
                      n <- STM[IO].pure(v.length)
                    } yield n).commit.attempt
        } yield result
      }
        .asserting(_.left.toOption.get.getMessage should include("missing"))
    }

    "a throwing map function is caught by the immediate handleErrorWith" in {
      val boom = new RuntimeException("map-throw")

      withRuntime { implicit stm =>
        STM[IO]
          .pure(1)
          .map[Int](_ => throw boom)
          .handleErrorWith(ex => STM[IO].pure(if (ex eq boom) 99 else -1))
          .commit
      }
        .asserting(_ shouldBe 99)
    }

    "an abort inside a recovery branch is caught by the next outer handler" in {
      val first  = new RuntimeException("first")
      val second = new RuntimeException("second")

      withRuntime { implicit stm =>
        STM[IO]
          .abort(first)
          .map(_ => 0)
          .handleErrorWith(_ => STM[IO].abort(second).map(_ => 0))
          .handleErrorWith(ex => STM[IO].pure(if (ex eq second) 42 else -1))
          .commit
      }
        .asserting(_ shouldBe 42)
    }

    "a failing recovery with no outer handler surfaces the recovery's error" in {
      val first  = new RuntimeException("first")
      val second = new RuntimeException("second")

      withRuntime { implicit stm =>
        STM[IO]
          .abort(first)
          .map(_ => 0)
          .handleErrorWith(_ => STM[IO].abort(second).map(_ => 0))
          .commit
          .attempt
      }
        .asserting(_.left.toOption.get shouldBe theSameInstanceAs(second))
    }
  }

  "fromF" - {
    "lift an effect into a transaction" in {
      withRuntime { implicit stm =>
        for {
          result <- STM[IO].fromF(IO.pure(42)).commit
        } yield result
      }
        .asserting(_ shouldBe 42)
    }

    "compose with other transaction operations" in {
      withRuntime { implicit stm =>
        for {
          tVar <- TxnVar.of(10)
          _ <- (for {
                 extra <- STM[IO].fromF(IO.pure(5))
                 v     <- tVar.get
                 _     <- tVar.set(v + extra)
               } yield ()).commit
          updated <- tVar.get.commit
        } yield updated
      }
        .asserting(_ shouldBe 15)
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

      withRuntime { implicit stm =>
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
