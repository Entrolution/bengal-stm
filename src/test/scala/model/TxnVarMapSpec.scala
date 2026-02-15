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
package model

import bengal.stm.STM
import bengal.stm.model.*
import bengal.stm.syntax.all.*

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

class TxnVarMapSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers with EitherValues {
  val baseMap: Map[String, Int] = Map("foo" -> 42, "bar" -> 27, "baz" -> 18)

  "TxnVarMap.get" - {
    "return the value of a transactional map" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVarMap <- TxnVarMap.of(baseMap)
            result  <- tVarMap.get.commit
          } yield result
        }
        .asserting(_ shouldBe baseMap)
    }
  }

  "TxnVarMap.set" - {
    "should update the underpinning map" in {
      val newMap = Map("foo" -> -10, "foobaz" -> 31)

      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVarMap <- TxnVarMap.of(baseMap)
            _       <- tVarMap.set(newMap).commit
            result  <- tVarMap.get.commit
          } yield result
        }
        .asserting(_ shouldBe newMap)
    }
  }

  "TxnVarMap.modify" - {
    "should modify values according to the specified transform" in {
      def mapTransform(input: Map[String, Int]): Map[String, Int] =
        input.map(i => i._1 -> i._2 * 2)

      val resultMap = Map("foo" -> 84, "bar" -> 54, "baz" -> 36)

      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVarMap <- TxnVarMap.of(baseMap)
            _       <- tVarMap.modify(mapTransform).commit
            result  <- tVarMap.get.commit
          } yield result
        }
        .asserting(_ shouldBe resultMap)
    }
  }

  "TxnVarMap.get(key)" - {
    "return the value of transactional variable" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVarMap <- TxnVarMap.of(baseMap)
            result  <- tVarMap.get("foo").commit
          } yield result
        }
        .asserting(_ shouldBe Some(42))
    }

    "return None if key isn't present" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVarMap <- TxnVarMap.of(baseMap)
            result  <- tVarMap.get("foobar").commit
          } yield result
        }
        .asserting(_ shouldBe None)
    }

    "return None if the key is deleted in the current transaction" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVarMap <- TxnVarMap.of(baseMap)
            result <- (for {
                        _           <- tVarMap.remove("foo")
                        innerResult <- tVarMap.get("foo")
                      } yield innerResult).commit
          } yield result
        }
        .asserting(_ shouldBe None)
    }
  }

  "TxnVarMap.set(key)" - {
    "update values for existing keys" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVarMap <- TxnVarMap.of(baseMap)
            _       <- tVarMap.set("foo", 2).commit
            result  <- tVarMap.get("foo").commit
          } yield result
        }
        .asserting(_ shouldBe Some(2))
    }

    "creates new entry for non-existent key" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVarMap <- TxnVarMap.of(baseMap)
            _       <- tVarMap.set("foobaz", 2).commit
            result  <- tVarMap.get("foobaz").commit
          } yield result
        }
        .asserting(_ shouldBe Some(2))
    }
  }

  "TxnVarMap.modify(key)" - {
    "modify value for pre-existing entry" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVarMap <- TxnVarMap.of(baseMap)
            _       <- tVarMap.modify("baz", _ - 12).commit
            result  <- tVarMap.get("baz").commit
          } yield result
        }
        .asserting(_ shouldBe Some(6))
    }

    "throw an error if key isn't present" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVarMap <- TxnVarMap.of(baseMap)
            result  <- tVarMap.modify("foobar", _ + 2).commit.attempt
          } yield result
        }
        .asserting(_.left.value shouldBe a[RuntimeException])
    }

    "modify value for key created in current transaction" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVarMap <- TxnVarMap.of(baseMap)
            result <- (for {
                        _           <- tVarMap.set("foobaz", 3)
                        _           <- tVarMap.modify("foobaz", _ + 22)
                        innerResult <- tVarMap.get("foobaz")
                      } yield innerResult).commit
          } yield result
        }
        .asserting(_ shouldBe Some(25))
    }
  }

  "TxnVarMap.remove(key)" - {
    "remove value for pre-existing entry" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVarMap <- TxnVarMap.of(baseMap)
            _       <- tVarMap.remove("baz").commit
            result  <- tVarMap.get.commit
          } yield result
        }
        .asserting(_ shouldBe Map("foo" -> 42, "bar" -> 27))
    }

    "throw an error if key doesn't exist" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVarMap <- TxnVarMap.of(baseMap)
            result  <- tVarMap.remove("foobar").commit.attempt
          } yield result
        }
        .asserting(_.left.value shouldBe a[RuntimeException])
    }

    "remove value of entry created in current transaction" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVarMap <- TxnVarMap.of(baseMap)
            result <- (for {
                        _           <- tVarMap.set("foobar", 22)
                        _           <- tVarMap.remove("foobar")
                        innerResult <- tVarMap.get("foobar")
                      } yield innerResult).commit
          } yield result
        }
        .asserting(_ shouldBe None)
    }
  }
}
