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

/** The two park/wake tests here regression-guard H1 (the lost wakeup). A lost wakeup corrupts nothing — it HANGS, so a
  * timeout is the only symptom it can produce. A timeout here is a report, not a flake: a parked reader missed the wake
  * it was owed. The window needs scheduling pressure to open, so load makes these tests more informative, not less.
  * (The fix under guard: submitTxnForRetry re-checks the read set and scans activeTransactions inside the
  * retry-semaphore region before parking.)
  */
class TxnLogDirtySpec extends AsyncFreeSpec with AsyncIOSpec with Matchers with StmSuite {

  "dirty detection" - {
    "transaction retries and sees updated value when variable is modified externally" in {
      withRuntime { implicit stm =>
        for {
          tVar   <- TxnVar.of(0)
          signal <- TxnVar.of(false)
          // Reader waits for the signal to be set, then reads tVar
          readerFiber <- (for {
                           s <- signal.get
                           _ <- STM[IO].waitFor(s)
                           v <- tVar.get
                         } yield v).commit.start
          // Writer sets the var then signals
          _      <- tVar.set(42).commit
          _      <- signal.set(true).commit
          result <- readerFiber.joinWithNever
        } yield result
      }.asserting(_ shouldBe 42)
    }

    "transaction that only reads commits successfully when no external modification occurs" in {
      withRuntime { implicit stm =>
        for {
          tVar   <- TxnVar.of(100)
          result <- tVar.get.commit
        } yield result
      }
        .asserting(_ shouldBe 100)
    }

    "write-only transaction always commits successfully" in {
      withRuntime { implicit stm =>
        for {
          tVar   <- TxnVar.of(0)
          _      <- tVar.set(999).commit
          result <- tVar.get.commit
        } yield result
      }
        .asserting(_ shouldBe 999)
    }

    "concurrent modification forces retry and transaction sees final value" in {
      withRuntime { implicit stm =>
        for {
          counter <- TxnVar.of(0)
          gate    <- TxnVar.of(false)
          // This transaction waits for the gate, then reads the counter
          readerFiber <- (for {
                           g <- gate.get
                           _ <- STM[IO].waitFor(g)
                           v <- counter.get
                         } yield v).commit.start
          // Writer increments counter multiple times, then opens the gate
          _      <- counter.set(1).commit
          _      <- counter.set(2).commit
          _      <- counter.set(3).commit
          _      <- gate.set(true).commit
          result <- readerFiber.joinWithNever
        } yield result
      }.asserting(_ shouldBe 3)
    }
  }
}
