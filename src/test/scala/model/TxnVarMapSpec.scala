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

import cats.effect.implicits._
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all._
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import bengal.stm.model._
import bengal.stm.syntax.all._

class TxnVarMapSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers with EitherValues with StmSuite {
  val baseMap: Map[String, Int] = Map("foo" -> 42, "bar" -> 27, "baz" -> 18)

  "TxnVarMap.get" - {
    "return the value of a transactional map" in {
      withRuntime { implicit stm =>
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

      withRuntime { implicit stm =>
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

      withRuntime { implicit stm =>
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
      withRuntime { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(baseMap)
          result  <- tVarMap.get("foo").commit
        } yield result
      }
        .asserting(_ shouldBe Some(42))
    }

    "return None if key isn't present" in {
      withRuntime { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(baseMap)
          result  <- tVarMap.get("foobar").commit
        } yield result
      }
        .asserting(_ shouldBe None)
    }

    "return None if the key is deleted in the current transaction" in {
      withRuntime { implicit stm =>
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
      withRuntime { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(baseMap)
          _       <- tVarMap.set("foo", 2).commit
          result  <- tVarMap.get("foo").commit
        } yield result
      }
        .asserting(_ shouldBe Some(2))
    }

    "creates new entry for non-existent key" in {
      withRuntime { implicit stm =>
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
      withRuntime { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(baseMap)
          _       <- tVarMap.modify("baz", _ - 12).commit
          result  <- tVarMap.get("baz").commit
        } yield result
      }
        .asserting(_ shouldBe Some(6))
    }

    "throw an error if key isn't present" in {
      withRuntime { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(baseMap)
          result  <- tVarMap.modify("foobar", _ + 2).commit.attempt
        } yield result
      }
        .asserting(_.left.value shouldBe a[RuntimeException])
    }

    "modify value for key created in current transaction" in {
      withRuntime { implicit stm =>
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
      withRuntime { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(baseMap)
          _       <- tVarMap.remove("baz").commit
          result  <- tVarMap.get.commit
        } yield result
      }
        .asserting(_ shouldBe Map("foo" -> 42, "bar" -> 27))
    }

    "throw an error if key doesn't exist" in {
      withRuntime { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(baseMap)
          result  <- tVarMap.remove("foobar").commit.attempt
        } yield result
      }
        .asserting(_.left.value shouldBe a[RuntimeException])
    }

    "remove value of entry created in current transaction" in {
      withRuntime { implicit stm =>
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

  "concurrent new-key operations" - {
    "concurrent set of same new key" in {
      withRuntime { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(Map.empty[String, Int])
          _ <- (
                 tVarMap.set("x", 1).commit,
                 tVarMap.set("x", 2).commit
               ).parTupled
          result <- tVarMap.get("x").commit
        } yield result
      }
        .asserting(_ shouldBe defined)
    }

    "concurrent delete of different keys" in {
      withRuntime { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(Map("a" -> 1, "b" -> 2))
          _ <- (
                 tVarMap.remove("a").commit,
                 tVarMap.remove("b").commit
               ).parTupled
          result <- tVarMap.get.commit
        } yield result
      }
        .asserting(_ shouldBe empty)
    }
  }

  "key identity is the key's own equality" - {
    // scala.math.BigDecimal("1.0") == BigDecimal("1.00") with equal hashCodes is a SCALA
    // property (scale-insensitive equality); java.math.BigDecimal differs. The two literals
    // therefore name ONE map slot while rendering differently — conflict detection must
    // treat them as the same slot, or concurrent read-modify-writes through the two
    // spellings race each other and lose updates.
    "concurrent modifies through equal-but-differently-rendered keys lose no updates" in {
      val k1 = BigDecimal("1.0")
      val k2 = BigDecimal("1.00")

      withRuntime { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(Map(k1 -> 0))
          _ <- (1 to 100).toList.parTraverse { i =>
                 val key = if (i % 2 == 0) k1 else k2
                 tVarMap.modify(key, (v: Int) => v + 1).commit
               }
          result <- tVarMap.get(k1).commit
        } yield result
      }
        .asserting(_ shouldBe Some(100))
    }

    "one transaction inserting two distinct keys with identical renderings lands both" in {
      // Distinct by reference equality, identical by toString: under a rendering-based
      // identity the second insert silently overwrote the first's log entry.
      final class SameString {
        override def toString: String = "same"
      }
      val k1 = new SameString
      val k2 = new SameString

      withRuntime { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(Map.empty[SameString, Int])
          _ <- (for {
                 _ <- tVarMap.set(k1, 1)
                 _ <- tVarMap.set(k2, 2)
               } yield ()).commit
          result <- tVarMap.get.commit
        } yield result
      }
        .asserting { result =>
          result.size shouldBe 2
          result.values.toSet shouldBe Set(1, 2)
        }
    }
  }
}
