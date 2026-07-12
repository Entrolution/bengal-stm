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
package spec

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import bengal.stm.STM
import bengal.stm.model._
import bengal.stm.syntax.all._

/** A transaction whose RESULT is null.
  *
  * `None` used to mean two different things on the commit path, and they collided.
  *
  * `getTxnLogResult` wrapped the transaction's value with `Option(...)`, which maps a null result to `None`. But
  * `AnalysedTxn.commit` ALSO uses `None` to signal "the placement was unsound or the log went dirty — release, refine
  * and re-run". So a transaction that legitimately yielded null was read as a refinement request.
  *
  * The consequence was not a wrong answer, it was worse. The sound, non-dirty path runs `log.commit.as(logValue)` — it
  * PUBLISHES THE WRITE SET FIRST and only then yields the `None`. The runtime reads that `None` as `TxnResultLogDirty`,
  * builds a fresh incarnation and runs the whole transaction again. Which publishes again. Which yields null again.
  * Forever: an unbounded retry loop that re-applies its effects on every lap.
  *
  * Reachable from ordinary code — any `STM[F].delay`/`fromF` over a Java API that can return null, or a `TxnVar` that
  * holds one.
  *
  * The fix is to stop overloading `None`: the value is wrapped with `Some(...)`, so a committed transaction ALWAYS
  * yields `Some` (of a possibly-null value) and `None` on that path can only ever mean "refine". Reverting it turns
  * both tests below red — the first hangs to its timeout, the second reports an inflated lap count.
  */
class NullResultSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers with StmSuite {

  "a transaction that yields null" - {

    "commits exactly once and returns the null, rather than retrying forever" in {
      withRuntime { implicit stm =>
        for {
          // Counts publishes: every lap of the retry loop re-applies this.
          laps <- TxnVar.of(0)
          result <- (for {
                      n <- laps.get
                      _ <- laps.set(n + 1)
                      s <- STM[IO].delay[String](null)
                    } yield s).commit
          published <- laps.get.commit
        } yield (result, published)
      }
        // The pre-fix failure is an UNBOUNDED retry loop, so there is nothing to
        // assert against — it never returns. StmSuite's timeout is the assertion.
        .asserting { case (result, published) =>
          result shouldBe null
          published shouldBe 1
        }
    }

    "a null read back out of a TxnVar is returned unchanged" in {
      withRuntime { implicit stm =>
        for {
          tVar   <- TxnVar.of[IO, String](null)
          result <- tVar.get.commit
        } yield result
      }.asserting(_ shouldBe null)
    }
  }
}
