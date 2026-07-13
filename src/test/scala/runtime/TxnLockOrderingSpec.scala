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

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.parallel._
import cats.syntax.traverse._
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import bengal.stm.model._
import bengal.stm.syntax.all._

/** Plain `TxnVar`s accessed in OPPOSITE ORDERS by concurrent transactions.
  *
  * ===========================================================================
  * WHAT THIS SUITE CANNOT DO — SAID PLAINLY, BECAUSE THE NAME INVITES THE OPPOSITE
  * ===========================================================================
  * IT CANNOT DEADLOCK, SO IT CANNOT CATCH H2, and it never could: it passes unchanged against the pre-H2-fix tree.
  * Every pair below has INCOMPATIBLE footprints — one writes what the other reads — so the scheduler's Contract C scan
  * serializes them and their execute windows never overlap. `withLock` is therefore never entered concurrently, and a
  * lock-acquisition ORDER that could form a circular wait is unreachable by construction. "No deadlock" is not an
  * assertion here; it is a tautology.
  *
  * The deeper reason is the one H2 turns on. For a plain `TxnVar` the id the log keys an entry by and the id that OWNS
  * the lock that entry takes are THE SAME id — the var's `runtimeId`. Sorting by either orders the same acquisitions.
  * H2 lives in the one configuration where those two diverge: a map key that does not yet exist is LOGGED under the
  * existential id allocated for `(map, key)` but LOCKS the MAP's structural `commitLock`. Two unrelated id spaces. That
  * shape cannot be built out of `TxnVar`s at all, which is why `spec/CommitLockOrderSpec` exists and uses map keys with
  * COMPATIBLE footprints — the only arrangement in which the scheduler deliberately lets two transactions into
  * `withLock` at the same time. The two suites are not duplicates and must not be merged.
  *
  * ===========================================================================
  * WHAT IT DOES PIN
  * ===========================================================================
  * That the scheduler really does serialize these pairs, and that the results are the SERIAL ones. That is worth having
  * and it was not previously asserted: the old assertion was `(a + b) should be > 3`, which a LOST UPDATE passes
  * comfortably (both transactions read A=1, B=2; one writes A=3, the other writes B=3; the sum is 6). The assertions
  * below name the exact reachable outcomes instead, so a scheduler that stopped detecting a read/write conflict on
  * plain vars — the property negative control NC-4 removes in the model — turns them red rather than green.
  */
class TxnLockOrderingSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers with StmSuite {

  /** Every final state some serial order of the transactions produces, computed against a sequential reference model.
    * Each transaction reads all the vars and writes its own, so the model is one line; what it buys is an assertion
    * that admits precisely the serializable outcomes and nothing else.
    */
  private def serialOutcomes(initial: Vector[Int]): Set[Vector[Int]] =
    initial.indices.toList.permutations.map { order =>
      order.foldLeft(initial)((state, target) => state.updated(target, state.sum))
    }.toSet

  "opposite access orders on plain vars" - {

    "read/write conflicts are serialized, so only the two serial outcomes are reachable" in {
      withRuntime { implicit stm =>
        for {
          varA <- TxnVar.of(1)
          varB <- TxnVar.of(2)
          // Reads A and B, writes A. Its validated footprint is r{B}, w{A}.
          txn1 = for {
                   a <- varA.get
                   b <- varB.get
                   _ <- varA.set(a + b)
                 } yield ()
          // The mirror image: reads B and A, writes B. Validated r{A}, w{B}.
          // Each transaction WRITES an id the other READS, so the pair is
          // incompatible in both directions and the scheduler will not overlap
          // them however the lock order would have gone.
          txn2 = for {
                   b <- varB.get
                   a <- varA.get
                   _ <- varB.set(a + b)
                 } yield ()
          _      <- (txn1.commit, txn2.commit).parTupled
          finalA <- varA.get.commit
          finalB <- varB.get.commit
        } yield (finalA, finalB)
      }.asserting { outcome =>
        // From (A=1, B=2):
        //   txn1 then txn2 -> A := 1+2 = 3, then B := 3+2 = 5  ->  (3, 5)
        //   txn2 then txn1 -> B := 2+1 = 3, then A := 1+3 = 4  ->  (4, 3)
        // A lost update — both reading (1, 2) and both publishing — leaves
        // (3, 3), which is in neither, and that is the point of naming them.
        Set((3, 5), (4, 3)) should contain(outcome)
      }
    }

    "five transactions over a shared var set produce a serial outcome" in {
      withRuntime { implicit stm =>
        for {
          vars <- (1 to 5).toList.traverse(i => TxnVar.of(i))
          // Each reads every var and writes ONE of them, so every pair conflicts
          // (the writer's id is in the other's read set) and the whole batch is
          // fully serialized. Which of the 120 orders it picks is up to the
          // scheduler; that it picks ONE of them is the assertion.
          txns = vars.zipWithIndex.map { case (target, _) =>
                   (for {
                     values <- vars.traverse(_.get)
                     sum = values.sum
                     _ <- target.set(sum)
                   } yield ()).commit
                 }
          _       <- txns.parSequence
          results <- vars.traverse(_.get.commit)
        } yield results.toVector
      }.asserting { results =>
        withClue("final state is reproducible by no serial order — a read/write conflict went undetected: ") {
          serialOutcomes(Vector(1, 2, 3, 4, 5)) should contain(results)
        }
      }
    }

    "bidirectional transfers between two accounts converge on the single serial outcome" in {
      withRuntime { implicit stm =>
        for {
          accountA <- TxnVar.of(1000)
          accountB <- TxnVar.of(1000)
          // A -> B.
          txn1 = for {
                   a <- accountA.get
                   b <- accountB.get
                   _ <- accountA.set(a - 10)
                   _ <- accountB.set(b + 10)
                 } yield ()
          // B -> A, taking the accounts in the opposite order. Both write both
          // accounts, so the write sets collide and the pair is incompatible.
          txn2 = for {
                   b <- accountB.get
                   a <- accountA.get
                   _ <- accountB.set(b - 10)
                   _ <- accountA.set(a + 10)
                 } yield ()
          _      <- (txn1.commit, txn2.commit).parTupled
          finalA <- accountA.get.commit
          finalB <- accountB.get.commit
        } yield (finalA, finalB)
      }.asserting { outcome =>
        // Both serial orders undo each other exactly, so there is only ONE
        // reachable state — which is a far tighter claim than the conserved
        // total the old assertion made, and it fails on an interleaving that
        // conserves the total by accident.
        outcome shouldBe ((1000, 1000))
      }
    }
  }
}
