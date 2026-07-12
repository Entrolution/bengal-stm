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
import cats.effect.implicits._
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import bengal.stm.STM
import bengal.stm.model._
import bengal.stm.syntax.all._

/** H2 — commit-lock ordering across two transactional maps.
  *
  * `TxnLogValid.withLock` acquires the write set's `commitLock`s in a sorted order, so that concurrent transactions
  * cannot form a circular wait. For that to work, the sort must order the LOCKS. It used to sort the LOG ENTRIES, which
  * is not the same thing:
  *
  *   - a map entry for a key that does NOT yet exist is logged under the EXISTENTIAL id, hashed from `(mapId, key)`
  *   - but its lock resolves to the MAP's structural `commitLock` (`TxnLogUpdateVarMapEntry.lock`)
  *
  * Two unrelated id spaces. Sorting by the log key therefore ordered the acquisitions by a hash of the KEY while the
  * locks acquired belonged to the MAPS — so two transactions inserting fresh keys into the same two maps could take
  * `{m1.lock, m2.lock}` in opposite orders and deadlock. Their footprints are compatible (disjoint entry ids, no
  * structure id on either side), so the scheduler deliberately runs them concurrently: nothing upstream prevents it.
  *
  * The fix carries each lock's OWNER id alongside it and sorts on that, which is a genuine total order over locks.
  * Modelled and verified in `specs/commit/CommitH2.cfg` (`NoWaitsForCycle`).
  *
  * MAP KEYS ARE NOT AN ARBITRARY CHOICE HERE, and this suite does not overlap `runtime/TxnLockOrderingSpec` — do not
  * merge them. That suite drives plain `TxnVar`s, where the log key and the lock owner are the SAME id, so the
  * divergence H2 turns on cannot arise; and its transactions have incompatible footprints, so the scheduler serializes
  * them and they never enter `withLock` together at all. Fresh map keys are the only shape that puts two
  * footprint-COMPATIBLE transactions into overlapping commit windows while their locks resolve through a different id
  * space. Nothing else in the library reaches H2.
  *
  * FAILURE MODE, measured against the pre-fix tree: this test deadlocked and failed with `TimeoutException: 60 seconds`
  * rather than wedging the JVM — so a reintroduction is a red test, not a hung CI job. The deadlocked fibers themselves
  * cannot be cancelled (`AnalysedTxn.commit` wraps `withLock` in `Async[F].uncancelable` and discards the poll, so a
  * fiber blocked on `Semaphore.permit` is uninterruptible); they simply leak until the run ends. Hence the generous
  * timeout: it must be long enough not to fire spuriously under load, because the cost of it firing is a leaked fiber.
  */
class CommitLockOrderSpec extends AnyFreeSpec with Matchers {

  /** Enough distinct keys that both acquisition orders are near-certain to occur. Each key's order is decided by
    * whether `hash(m1, key) < hash(m2, key)` — a coin flip per key — so the chance that all 24 land on the same side,
    * and the pre-fix defect stays hidden, is about 2^-23.
    */
  private val Keys: List[String] = (1 to 24).map(i => s"k$i").toList

  /** The same 24 keys, pre-paired. Built directly rather than by `grouped(2)` so the match stays total — CI compiles
    * with `-Werror`.
    */
  private val KeyPairs: List[(String, String)] = (1 to 12).map(i => (s"k${2 * i - 1}", s"k${2 * i}")).toList

  /** One fresh key, inserted into BOTH maps. Neither key exists, so both entries fall back to their map's structural
    * lock — which is what puts this transaction on the H2 path at all.
    */
  private def insertIntoBoth(
    m1: TxnVarMap[IO, String, Int],
    m2: TxnVarMap[IO, String, Int],
    key: String
  )(implicit stm: STM[IO]): Txn[Unit] =
    for {
      _ <- m1.set(key, 1)
      _ <- m2.set(key, 2)
    } yield ()

  /** Two fresh keys, both into the SAME map. Neither exists, so BOTH entries resolve to that map's single structural
    * `commitLock` — they alias. `withLock`'s `.distinct` has to collapse them into one acquisition, because a 1-permit
    * `Semaphore` taken twice by the same fiber deadlocks instantly.
    *
    * This is a companion to the ordering test, not a duplicate of it. The H2 fix changed what `.distinct` dedupes on
    * (the `(owner id, Semaphore)` pair rather than the bare `Semaphore`), so the aliasing path needs its own pin —
    * removing the dedup in the model turns this scenario into a deadlock (negative control NC-5).
    */
  private def insertTwoKeysIntoOneMap(
    m: TxnVarMap[IO, String, Int],
    k1: String,
    k2: String
  )(implicit stm: STM[IO]): Txn[Unit] =
    for {
      _ <- m.set(k1, 1)
      _ <- m.set(k2, 2)
    } yield ()

  "two fresh keys inserted into ONE map alias to one lock and must not self-deadlock" in {
    val result = STM
      .runtime[IO]
      .flatMap { implicit stm =>
        for {
          m <- TxnVarMap.of[IO, String, Int](Map.empty)
          _ <- KeyPairs.parTraverse { case (k1, k2) => insertTwoKeysIntoOneMap(m, k1, k2).commit }
          r <- m.get.commit
        } yield r
      }
      .timeout(60.seconds)
      .unsafeRunSync()

    result.keySet shouldBe Keys.toSet
  }

  "H2 regression — concurrent new-key inserts across two maps do not deadlock" in {
    val (finalM1, finalM2) = STM
      .runtime[IO]
      .flatMap { implicit stm =>
        for {
          m1 <- TxnVarMap.of[IO, String, Int](Map.empty)
          m2 <- TxnVarMap.of[IO, String, Int](Map.empty)
          _  <- Keys.parTraverse(k => insertIntoBoth(m1, m2, k).commit)
          r1 <- m1.get.commit
          r2 <- m2.get.commit
        } yield (r1, r2)
      }
      .timeout(60.seconds)
      .unsafeRunSync()

    finalM1 shouldBe Keys.map(_ -> 1).toMap
    finalM2 shouldBe Keys.map(_ -> 2).toMap
  }
}
