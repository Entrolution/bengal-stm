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
package bengal.stm.model

import scala.collection.concurrent.TrieMap

import cats.effect.Ref
import cats.effect.kernel.Async
import cats.effect.std.Semaphore
import cats.syntax.all._

import bengal.stm.model.runtime._

/** A mutable transactional map from keys of type `K` to values of type `V`.
  *
  * Unlike wrapping a `Map` in a `TxnVar`, `TxnVarMap` tracks individual keys as separate transactional entities,
  * enabling finer-grained conflict detection and better concurrency. Create instances via [[TxnVarMap.of]].
  *
  * Keys must have stable `equals`/`hashCode` for as long as they are used with the map (the usual `Map` key contract):
  * conflict detection identifies a key by that equality. Each distinct key ever referenced — read, written, deleted, or
  * merely probed — retains one internal id-registry entry (including a strong reference to the key object) for the
  * lifetime of this `TxnVarMap`. Workloads streaming unboundedly many distinct keys through one map will grow that
  * registry without bound; prefer bounded key spaces, or fresh maps per epoch.
  *
  * Construction is factory-only: [[TxnVarMap.of]] draws the map's identity from the runtime's allocator, and the sealed
  * constructor is what makes that identity trustworthy. It also makes a second handle over this map's state
  * unrepresentable in user code — the map's internal key-id registry is per-instance, so a copied handle would disagree
  * with the original about key identity in ways that silently break conflict detection.
  *
  * @tparam F
  *   the effect type
  * @tparam K
  *   the key type
  * @tparam V
  *   the value type
  */
final class TxnVarMap[F[_]: TxnIdAllocator: Async, K, V] private[stm] (
  private[stm] val id: TxnVarId,
  protected val value: Ref[F, VarIndex[F, K, V]],
  private[stm] val commitLock: Semaphore[F],
  private val internalStructureLock: Semaphore[F]
) extends TxnStateEntity[F] {

  private def withStructureLock[A](fa: F[A]): F[A] = internalStructureLock.permit.use(_ => fa)

  private[stm] lazy val get: F[Map[K, V]] =
    for {
      txnVarMap <- value.get
      valueMap <- txnVarMap.toList.traverse { case (k, txnVar) =>
                    txnVar.get.map(v => k -> v)
                  }
    } yield valueMap.toMap

  private[stm] def getTxnVar(key: K): F[Option[TxnVar[F, V]]] =
    for {
      txnVarMap <- value.get
    } yield txnVarMap.get(key)

  private[stm] def get(key: K): F[Option[V]] =
    getTxnVar(key).flatMap(_.traverse(_.get))

  // The EXISTENTIAL id registry: one stable id per distinct key, whether or not
  // the key exists. An id that does not need a TxnVar names a slot that may hold
  // nothing at all — which is the only reason an ABSENT-key read is visible to
  // the scheduler. Reading a missing key is still a read: it observes that the
  // key is missing, and a transaction that inserts it must conflict with the
  // reader. Without a stable id for the absent slot there would be nothing to
  // declare and nothing to compare, and every waitFor on a key-not-yet-created
  // would be invisible to the conflict relation.
  //
  // Ids are issued by the runtime's ONE global allocator (the TxnIdAllocator
  // counter this map holds), the same counter that numbers TxnVars and maps,
  // so a key id can never alias an entity id — IdFootprint compares raw values
  // with the parent stripped, so global uniqueness is load-bearing, not
  // cosmetic. Identity is the key's own
  // equals/hashCode (the TrieMap's), i.e. exactly the equality the value store
  // uses — never a rendering like toString, whose equivalence classes need not
  // match equality in either direction.
  //
  // ENTRIES ARE NEVER EVICTED, deletes included. A parked transaction's
  // footprint holds the key's id; evict-and-reallocate would issue a DIFFERENT
  // id for the same key, and the wake sweep and coverage check would compare the
  // two as unrelated slots — a lost wakeup by construction. Delete-then-reinsert
  // must keep the same existential id for the same reason. The price is one
  // registry entry (and a strong reference to the key) per distinct key ever
  // referenced, for the lifetime of the map; the class scaladoc states it.
  //
  // Per-instance on purpose (it shares the map's lifetime and GCs with it). A
  // second handle sharing the state Ref but not this registry would disagree
  // about key identity — which is why the constructor is sealed and copy no
  // longer exists: that handle is now unrepresentable (class scaladoc).
  private val existentialIds: TrieMap[K, TxnVarRuntimeId] =
    TrieMap.empty[K, TxnVarRuntimeId]

  // It is also a SECOND ID SPACE, and that has bitten twice. This id is what the
  // entry is LOGGED under, but an absent key has no TxnVar, so the entry LOCKS
  // the map's structural lock instead — owner and log key are different
  // entities, which is H2 (see TxnLogContext.withLock). And an absent-key read
  // sat in the declared footprint while recording no log entry at all, which is
  // the absent-key lost wakeup: Contract C and the wake sweeps could see the
  // read, and the park-time staleness check — which walks the LOG — could not.
  //
  // The parent link, and the whole of the id hierarchy the conflict relation
  // reasons over: an entry's id carries the MAP's id as its parent. That is what
  // lets IdFootprint conflict a whole-map read against a write to any single key
  // (its third conjunct, the H5 fix) and lets a declared map-level id COVER the
  // per-key ids a whole-map read expands into at run time (its coverage check,
  // the H6 fix). Both tests are one hop; see TxnVarRuntimeId.
  //
  // First registration is a one-time allocate-and-publish per key — lock-free
  // on the registry, a plain lookup ever after. When it happens varies by
  // path: a keyed op normally registers during the static-analysis pass,
  // before any lock (a throwing key thunk or an earlier erratum defers it to
  // the run); a key reached only through whole-map ops registers during the
  // log run if it is fresh (resolveMapKey) or at commit under withLock if it
  // already exists (the entry's idFootprint). A putIfAbsent race between two
  // fibers burns one counter value; gaps in the sequence are meaningless.
  private[stm] def getRuntimeId(
    key: K
  ): F[TxnVarRuntimeId] =
    Async[F].delay(existentialIds.get(key)).flatMap {
      case Some(rid) =>
        Async[F].pure(rid)
      case None =>
        TxnIdAllocator[F].txnVarIdGen.updateAndGet(_ + 1).map { newId =>
          val candidate = TxnVarRuntimeId(newId, Some(runtimeId))
          existentialIds.putIfAbsent(key, candidate).getOrElse(candidate)
        }
    }

  private[stm] def addOrUpdate(key: K, newValue: V): F[Unit] =
    withStructureLock {
      for {
        txnVarMap <- value.get
        _ <- txnVarMap.get(key) match {
               case Some(tVar) =>
                 tVar.set(newValue)
               case None =>
                 for {
                   newTxnVar <- TxnVar.of(newValue)
                   _         <- value.update(_ + (key -> newTxnVar))
                 } yield ()
             }
      } yield ()
    }

  private[stm] def delete(key: K): F[Unit] =
    withStructureLock {
      for {
        txnVarMap <- value.get
        _ <- txnVarMap.get(key) match {
               case Some(_) =>
                 value.update(_ - key)
               case None =>
                 Async[F].unit
             }
      } yield ()
    }
}

object TxnVarMap {

  /** Creates a new `TxnVarMap` with the given initial entries. Requires an implicit `STM[F]` runtime — the runtime is
    * the allocator the [[bengal.stm.model.runtime.TxnIdAllocator]] bound asks for.
    *
    * The map belongs to that runtime: all transactions touching it must be committed through the same `STM[F]`
    * instance. Use under a different runtime is undefined — see [[bengal.stm.STM.runtime]].
    */
  def of[F[_]: TxnIdAllocator: Async, K, V](valueMap: Map[K, V]): F[TxnVarMap[F, K, V]] =
    for {
      id <- TxnIdAllocator[F].txnVarIdGen.updateAndGet(_ + 1)
      values <- valueMap.toList.traverse { case (k, v) =>
                  TxnVar.of(v).map(txv => k -> txv)
                }
      valuesRef             <- Async[F].ref(values.toMap: VarIndex[F, K, V])
      lock                  <- Semaphore[F](1)
      internalStructureLock <- Semaphore[F](1)
    } yield new TxnVarMap(id, valuesRef, lock, internalStructureLock)
}
