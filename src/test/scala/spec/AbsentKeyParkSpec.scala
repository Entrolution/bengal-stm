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

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all._
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import bengal.stm.STM
import bengal.stm.model._
import bengal.stm.syntax.all._

/** Parking on a map key that DOES NOT EXIST YET.
  *
  * This path had no test at all. Every `waitFor` in the suite parks on a `TxnVar` — `StmRuntimeSpec`, `StmStressSpec`,
  * `TxnLogDirtySpec`, `SerializabilityOracleSpec`, and `RetrySoakSpec`, which is `TxnVar`-only by construction. That
  * gap hid a lost wakeup for the whole H1 workstream.
  *
  * THE DEFECT. `getVarMapValue`'s key-absent branch used to return the log UNCHANGED — an absent key was read and
  * nothing was recorded. But `TxnLogValid.anyReadChangedSinceRead` folds over `log.values`, and that fold IS H1's
  * SECOND park guard: the one that catches a conflictor which already committed and left `activeTransactions`. A read
  * with no log entry is invisible to it, so for an absent-key predicate that guard was structurally dead. The first
  * guard (the `activeTransactions` scan) still caught conflictors that were still running — so what was left uncovered
  * was exactly the case the second guard exists for.
  *
  * The read was in the DECLARED footprint the whole time (the static-analysis walker registers the key's existential id
  * whether or not the key exists), which is why Contract C and the wake sweep both looked fine. Only the LOG was
  * missing it. Two id spaces again.
  *
  * ===========================================================================
  * WHAT THIS SUITE CANNOT DO — SAID PLAINLY, BECAUSE IT MATTERS
  * ===========================================================================
  * IT DOES NOT CATCH THE DEFECT. Revert the fix and this suite stays GREEN.
  *
  * The lost wakeup needs a conflictor's ENTIRE lifecycle — submit, execute, commit, complete — to land inside the
  * parker's [registerCompletion -> activeTransactions scan] gap. Land any earlier and the conflictor subscribes to the
  * still-running parker, which makes `hasDownstream` resubmit it instead of parking (TxnRuntimeContext's retry
  * dispatch), so it never parks at all. Land any later and the scan sees the conflictor and resubmits. A two-party
  * behavioural test cannot build that interleaving, and 240 reps under heavy scheduling pressure did not produce one.
  *
  * THE DEFECT IS PINNED BY THE MODEL: `specs/scheduler/SchedulerAbsentKey.cfg`. It is `SchedulerRetry.cfg` with one
  * difference — the parker's read is not logged — and it deadlocks where `SchedulerRetry` verifies clean. That is the
  * regression guard. This suite is the coverage floor: it proves the absent-key park/wake path works at all, which
  * would catch a gross breakage, and it must not be read as more than that.
  */
class AbsentKeyParkSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  "parking on an absent map key" - {

    "a transaction blocked on a key that does not exist is woken when the key is created" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            map <- TxnVarMap.of[IO, String, Int](Map.empty)
            // Parks: "k" is absent, so the predicate is false and there is no
            // entry for it in the map at all.
            reader <- (for {
                        ov <- map.get("k")
                        _  <- STM[IO].waitFor(ov.isDefined)
                      } yield ov.getOrElse(-1)).commit.start
            // Give the reader time to reach the retry map, so the writer's
            // submission sweep is the thing that wakes it.
            _      <- IO.sleep(100.millis)
            _      <- map.set("k", 42).commit
            result <- reader.joinWithNever
          } yield result
        }
        // A lost wakeup HANGS — there is nothing to assert against, so the
        // timeout is the assertion.
        .timeout(30.seconds)
        .asserting(_ shouldBe 42)
    }

    "a transaction blocked on an absent key is woken by a key created in a DIFFERENT transaction's write" in {
      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            map   <- TxnVarMap.of[IO, String, Int](Map.empty)
            other <- TxnVar.of(0)
            reader <- (for {
                        ov <- map.get("target")
                        _  <- STM[IO].waitFor(ov.exists(_ > 5))
                      } yield ov.getOrElse(-1)).commit.start
            _ <- IO.sleep(100.millis)
            // A write that does NOT satisfy the predicate: the key now exists,
            // but the value is too small. The reader must wake, re-run, and
            // park again rather than commit a stale view.
            _ <- map.set("target", 1).commit
            _ <- other.set(1).commit
            // Now satisfy it.
            _      <- map.set("target", 9).commit
            result <- reader.joinWithNever
          } yield result
        }
        .timeout(30.seconds)
        .asserting(_ shouldBe 9)
    }

    "many transactions parked on distinct absent keys are all woken" in {
      val keys = (0 until 12).toList.map(i => s"k$i")

      STM
        .runtime[IO]
        .flatMap { implicit stm =>
          for {
            map <- TxnVarMap.of[IO, String, Int](Map.empty)
            readers <- keys.traverse { k =>
                         (for {
                           ov <- map.get(k)
                           _  <- STM[IO].waitFor(ov.isDefined)
                         } yield ov.getOrElse(-1)).commit.start
                       }
            _       <- IO.sleep(200.millis)
            _       <- keys.zipWithIndex.traverse { case (k, i) => map.set(k, i).commit }
            results <- readers.traverse(_.joinWithNever)
          } yield results
        }
        .timeout(60.seconds)
        .asserting(_ shouldBe keys.indices.toList)
    }
  }
}
