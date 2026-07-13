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
  *   - a map entry for a key that does NOT yet exist is logged under the EXISTENTIAL id, allocated for `(map, key)` on
  *     the key's first reference
  *   - but its lock resolves to the MAP's structural `commitLock` (`TxnLogUpdateVarMapEntry.lock`)
  *
  * Two unrelated id spaces. Sorting by the log key therefore ordered the acquisitions by the KEYS' existential ids
  * while the locks acquired belonged to the MAPS — so two transactions inserting fresh keys into the same two maps
  * could take `{m1.lock, m2.lock}` in opposite orders and deadlock. Their footprints are compatible (disjoint entry
  * ids, no structure id on either side), so the scheduler deliberately runs them concurrently: nothing upstream
  * prevents it.
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
  * FAILURE MODE, re-measured with a locally reverted sort (log-key order restored in `withLock`, under allocator-issued
  * ids and the pre-touch below): this test deadlocked and failed with `TimeoutException: 60 seconds` rather than
  * wedging the JVM — so a reintroduction is a red test, not a hung CI job. The deadlocked fibers themselves cannot be
  * cancelled (`AnalysedTxn.commit` wraps `withLock` in `Async[F].uncancelable` and discards the poll, so a fiber
  * blocked on `Semaphore.permit` is uninterruptible); they simply leak until the run ends. Hence the generous timeout:
  * it must be long enough not to fire spuriously under load, because the cost of it firing is a leaked fiber.
  * `KeyIdentitySpec` pins the first-touch allocation-order property the pre-touch relies on.
  */
class CommitLockOrderSpec extends AnyFreeSpec with Matchers {

  /** 24 keys give the racing transactions plenty of overlapping commit windows. Which acquisition ORDER a key produces
    * under the pre-fix sort is not left to chance, though: existential ids are allocated in first-touch order, and a
    * transaction touches `m1` before `m2`, so without intervention EVERY key's ids would sort m1-before-m2 and a revert
    * of the H2 fix could never invert and deadlock. The setup phase below pre-touches ODD keys through `m2` first,
    * deterministically giving half the keys the opposite id order.
    */
  private val Keys: List[String] = (1 to 24).map(i => s"k$i").toList

  /** Odd keys get their `(m2, key)` existential id allocated BEFORE their `(m1, key)` one — an absent-key read through
    * `m2` in the setup phase is enough, since ids are issued on first reference and are stable thereafter. Under the
    * pre-fix sort (by LOG KEY, i.e. by these existential ids) odd-key transactions then acquire `m2.lock` before
    * `m1.lock` while even-key transactions acquire m1-before-m2 — the circular-wait shape the fix exists to prevent.
    * Under the fixed sort (by lock OWNER id) the pre-touch is inert: every transaction orders by the maps' own ids.
    */
  private val PreTouchViaM2: List[String] = Keys.zipWithIndex.collect { case (k, i) if i % 2 == 0 => k }

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
          // Sequential, before the race: allocate odd keys' (m2, key) ids first so the
          // pre-fix log-key sort would order their lock acquisitions m2-before-m1.
          _  <- PreTouchViaM2.traverse(k => m2.get(k).commit)
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
