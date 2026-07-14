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
import cats.effect.implicits._
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all._
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import bengal.stm.STM
import bengal.stm.model._
import bengal.stm.syntax.all._

/** Structural writers racing unlocked readers on one `TxnVarMap`.
  *
  * The COMPILE-TIME guard for the index's thread-safety is `VarIndex` being an immutable `Map`
  * (`model/runtime/package.scala`): readers snapshot through the `Ref`, writers swap, and in-place mutation is
  * unrepresentable. This suite is the behavioural smoke test over the same surface — per-key reads, whole-map
  * iteration, and park-path checks racing structural inserts at a scale that rebuilds the index many times. It cannot
  * prove the absence of a race the way the type does; it exists so that a future regression to a shared mutable index
  * has a test that can at least fail loudly (torn reads, phantom-absent keys, or a hang) instead of corrupting
  * silently.
  *
  * Per-key readers here are footprint-COMPATIBLE with the structural writers (different keys, same map), so the
  * scheduler runs them genuinely concurrently — nothing upstream serializes these accesses.
  */
class MapIndexStressSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers with StmSuite {

  "structural inserts racing per-key reads and whole-map iteration" in withRuntime { implicit stm =>
    for {
      tVarMap <- TxnVarMap.of((1 to 50).map(i => s"stable-$i" -> i).toMap)
      results <- (
                   (1 to 200).toList.traverse(i => tVarMap.set(s"fresh-$i", i).commit),
                   (1 to 200).toList.traverse(i => tVarMap.get(s"stable-${i % 50 + 1}").commit),
                   (1 to 40).toList.traverse(_ => tVarMap.get.commit)
                 ).parTupled
      (_, stableReads, _) = results
      finalMap <- tVarMap.get.commit
    } yield {
      // Every pre-existing key must read as present on every racing per-key read:
      // a phantom-absent result is exactly the torn-read symptom of a mutable
      // index rebuilt mid-lookup.
      stableReads.count(_.isDefined) shouldBe 200
      finalMap.keySet.count(_.startsWith("fresh-")) shouldBe 200
      finalMap.keySet.count(_.startsWith("stable-")) shouldBe 50
    }
  }

  "a parked waitFor survives structural churn and wakes on its key" in withRuntime { implicit stm =>
    for {
      tVarMap <- TxnVarMap.of(Map.empty[String, Int])
      waiter <- (for {
                  v <- tVarMap.get("signal")
                  _ <- STM[IO].waitFor(v.isDefined)
                } yield v).commit.start
      // The park-path staleness checks read the index while these inserts are
      // structurally rebuilding it; the waiter must neither miss its wake nor
      // wake spuriously into a torn view.
      _      <- (1 to 100).toList.traverse(i => tVarMap.set(s"noise-$i", i).commit)
      _      <- tVarMap.set("signal", 1).commit
      _      <- waiter.joinWithNever
      result <- tVarMap.get("signal").commit
    } yield result shouldBe Some(1)
  }
}
