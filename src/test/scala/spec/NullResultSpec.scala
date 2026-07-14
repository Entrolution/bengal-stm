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

  "a null value stored in a TxnVarMap" - {

    /* THE SAME ROOT CAUSE AS ABOVE, one layer down, and it lost data silently.
     *
     * TxnVarMap.get(key) encodes a live null as Some(null) -- the key EXISTS, its value is
     * null. But the log's read entry built its `initial` with Option(txnVal), which
     * collapses null to None, and None in that position means "the key is not there". So
     * the live map and the transaction log disagreed about a key that had just been set,
     * and two things followed:
     *
     *   1. get(key) reported the key ABSENT, and a whole-map read dropped it entirely
     *      (extractMap only matches Some(initial)). Setting a null silently lost the key.
     *   2. hasChangedSinceRead computed Some(null) != None -- PERPETUALLY TRUE -- so the
     *      park-time staleness guard misfired on every null-valued key.
     *
     * The fix is Some(txnVal) rather than Option(txnVal), which makes the log agree with
     * TxnVarMap.get. For any non-null value Option(v) == Some(v), so nothing else moves.
     */
    "is read back, rather than making the key disappear" in {
      withRuntime { implicit stm =>
        for {
          map <- TxnVarMap.of[IO, String, String](Map.empty)
          _   <- map.set("k", null: String).commit
          one <- map.get("k").commit
          all <- map.get.commit
        } yield (one, all)
      }
        .asserting { case (one, all) =>
          // The key EXISTS. Its value happens to be null.
          one shouldBe Some(null)
          // ...and a whole-map read must not quietly drop it.
          all shouldBe Map("k" -> null)
        }
    }

    "can be removed, with and without a prior read in the same transaction" in {
      withRuntime { implicit stm =>
        for {
          mapA <- TxnVarMap.of[IO, String, String](Map("k" -> null))
          _    <- mapA.remove("k").commit
          allA <- mapA.get.commit
          mapB <- TxnVarMap.of[IO, String, String](Map("k" -> null))
          read <- (for {
                    innerRead <- mapB.get("k")
                    _         <- mapB.remove("k")
                  } yield innerRead).commit
          allB <- mapB.get.commit
        } yield (allA, read, allB)
      }
        .asserting { case (allA, read, allB) =>
          // A null-valued key is PRESENT -- its log entry holds Some(null), never
          // None -- so removing it must succeed. Failing it as absent, or silently
          // skipping the delete, would both be the Option(...) collapse regressing
          // somewhere in the remove path.
          allA shouldBe Map.empty[String, String]
          read shouldBe Some(null)
          allB shouldBe Map.empty[String, String]
        }
    }
  }
}
