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
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

class TxnVarSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {
  "TxnVar.get" - {
    "return the value of a transactional variable" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVar   <- TxnVar.of(123)
            result <- tVar.get.commit
          } yield result
        }
        .asserting(_ shouldBe 123)
    }
  }

  "TxnVar.set" - {
    "update the value of a transactional variable" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVar   <- TxnVar.of(123)
            _      <- tVar.set(2718).commit
            result <- tVar.get.commit
          } yield result
        }
        .asserting(_ shouldBe 2718)
    }
  }

  "TxnVar.modify" - {
    "modify the value of a transactional variable" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            tVar   <- TxnVar.of("foo")
            _      <- tVar.modify(_ + "bar").commit
            result <- tVar.get.commit
          } yield result
        }
        .asserting(_ shouldBe "foobar")
    }
  }
}
