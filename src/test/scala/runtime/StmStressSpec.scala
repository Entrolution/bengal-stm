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

import bengal.stm.STM
import bengal.stm.model._
import bengal.stm.syntax.all._

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.foldable._
import cats.syntax.parallel._
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class StmStressSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  private def transfer(
    from: TxnVar[IO, Int],
    to: TxnVar[IO, Int]
  )(implicit stm: STM[IO]): Txn[Unit] =
    for {
      a <- from.get
      b <- to.get
      _ <- from.set(a - 1)
      _ <- to.set(b + 1)
    } yield ()

  private def incrementAllKeys(
    tVarMap: TxnVarMap[IO, String, Int],
    keys: List[String]
  )(implicit stm: STM[IO]): Txn[Unit] =
    keys.foldLeft(STM[IO].unit: Txn[Unit]) { (acc, key) =>
      acc.flatMap(_ => tVarMap.modify(key, _ + 1))
    }

  private def readWaitFor(
    counter: TxnVar[IO, Int],
    threshold: Int
  )(implicit stm: STM[IO]): Txn[Int] =
    for {
      v <- counter.get
      _ <- STM[IO].waitFor(v >= threshold)
    } yield v

  "counter with 100 concurrent writers" in {
    STM
      .runtime[IO]
      .flatMap { implicit stm =>
        for {
          counter <- TxnVar.of(0)
          _       <- (1 to 100).toList.parTraverse_(_ => counter.modify(_ + 1).commit)
          result  <- counter.get.commit
        } yield result
      }
      .timeout(30.seconds)
      .asserting(_ shouldBe 100)
  }

  "account transfer with 100 concurrent transfers" in {
    STM
      .runtime[IO]
      .flatMap { implicit stm =>
        for {
          accountA <- TxnVar.of(1000)
          accountB <- TxnVar.of(1000)
          _ <- (1 to 100).toList.parTraverse_ { i =>
                 if (i <= 50) transfer(accountA, accountB).commit
                 else transfer(accountB, accountA).commit
               }
          a <- accountA.get.commit
          b <- accountB.get.commit
        } yield a + b
      }
      .timeout(30.seconds)
      .asserting(_ shouldBe 2000)
  }

  "map contention with 20 concurrent writers" in {
    val keys       = List("k1", "k2", "k3", "k4", "k5")
    val initialMap = keys.map(_ -> 0).toMap

    STM
      .runtime[IO]
      .flatMap { implicit stm =>
        for {
          tVarMap <- TxnVarMap.of(initialMap)
          _       <- (1 to 20).toList.parTraverse_(_ => incrementAllKeys(tVarMap, keys).commit)
          result  <- tVarMap.get.commit
        } yield result
      }
      .timeout(30.seconds)
      .asserting(_ shouldBe keys.map(_ -> 20).toMap)
  }

  "waitFor under contention" in {
    STM
      .runtime[IO]
      .flatMap { implicit stm =>
        for {
          counter     <- TxnVar.of(0)
          writerFiber <- (1 to 10).toList.traverse_(_ => counter.modify(_ + 1).commit).start
          readers     <- (1 to 10).toList.parTraverse(_ => readWaitFor(counter, 10).commit)
          _           <- writerFiber.joinWithNever
          result      <- counter.get.commit
        } yield (result, readers)
      }
      .timeout(30.seconds)
      .asserting { case (finalValue, readerResults) =>
        finalValue shouldBe 10
        readerResults shouldBe List.fill(10)(10)
      }
  }
}
