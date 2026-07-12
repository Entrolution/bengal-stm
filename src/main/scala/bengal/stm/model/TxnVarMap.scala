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

import java.util.UUID

import scala.collection.mutable.{ Map => MutableMap }

import cats.effect.Ref
import cats.effect.kernel.Async
import cats.effect.std.Semaphore
import cats.syntax.all._

import bengal.stm.STM
import bengal.stm.model.runtime._

/** A mutable transactional map from keys of type `K` to values of type `V`.
  *
  * Unlike wrapping a `Map` in a `TxnVar`, `TxnVarMap` tracks individual keys as separate transactional entities,
  * enabling finer-grained conflict detection and better concurrency. Create instances via [[TxnVarMap.of]].
  *
  * @tparam F
  *   the effect type
  * @tparam K
  *   the key type
  * @tparam V
  *   the value type
  */
case class TxnVarMap[F[_]: STM: Async, K, V](
  private[stm] val id: TxnVarId,
  protected val value: Ref[F, VarIndex[F, K, V]],
  private[stm] val commitLock: Semaphore[F],
  private val internalStructureLock: Semaphore[F]
) extends TxnStateEntity[F, VarIndex[F, K, V]] {

  private def withLock[A](semaphore: Semaphore[F])(fa: F[A]): F[A] = semaphore.permit.use(_ => fa)

  private[stm] lazy val get: F[Map[K, V]] =
    for {
      txnVarMap <- value.get
      valueMap <- txnVarMap.toList.traverse { kv =>
                    kv._2.get.map(v => kv._1 -> v)
                  }
    } yield valueMap.toMap

  private[stm] def getTxnVar(key: K): F[Option[TxnVar[F, V]]] =
    for {
      txnVarMap <- value.get
    } yield txnVarMap.get(key)

  private[stm] def get(key: K): F[Option[V]] =
    for {
      oTxnVar <- getTxnVar(key)
      result <- oTxnVar match {
                  case Some(txnVar) =>
                    txnVar.get.map(v => Some(v))
                  case _ =>
                    Async[F].pure(None)
                }
    } yield result

  // EXISTENTIAL because it does not need the key to exist. Hashed from
  // (mapId, key) rather than taken from a TxnVar, it names a slot that may hold
  // nothing at all — which is the only reason an ABSENT-key read is visible to
  // the scheduler. Reading a missing key is still a read: it observes that the
  // key is missing, and a transaction that inserts it must conflict with the
  // reader. Without a stable id for the absent slot there would be nothing to
  // declare and nothing to compare, and every waitFor on a key-not-yet-created
  // would be invisible to the conflict relation.
  //
  // It is also a SECOND ID SPACE, and that has bitten twice. This id is what the
  // entry is LOGGED under, but an absent key has no TxnVar, so the entry LOCKS
  // the map's structural lock instead — owner and log key are different
  // entities, which is H2 (see TxnLogContext.withLock). And an absent-key read
  // sat in the declared footprint while recording no log entry at all, which is
  // the absent-key lost wakeup: Contract C and the wake sweeps could see the
  // read, and the park-time staleness check — which walks the LOG — could not.
  private def getRuntimeExistentialId(key: K): TxnVarRuntimeId =
    TxnVarRuntimeId(UUID.nameUUIDFromBytes((id, key).toString.getBytes).hashCode())

  // The parent link, and the whole of the id hierarchy the conflict relation
  // reasons over: an entry's id carries the MAP's id as its parent. That is what
  // lets IdFootprint conflict a whole-map read against a write to any single key
  // (its third conjunct, the H5 fix) and lets a declared map-level id COVER the
  // per-key ids a whole-map read expands into at run time (its coverage check,
  // the H6 fix). Both tests are one hop; see TxnVarRuntimeId.
  private[stm] def getRuntimeId(
    key: K
  ): F[TxnVarRuntimeId] =
    Async[F].delay(getRuntimeExistentialId(key).addParent(runtimeId))

  private[stm] def addOrUpdate(key: K, newValue: V): F[Unit] =
    withLock(internalStructureLock) {
      for {
        txnVarMap <- value.get
        _ <- txnVarMap.get(key) match {
               case Some(tVar) =>
                 tVar.set(newValue)
               case None =>
                 for {
                   newTxnVar <- TxnVar.of(newValue)
                   _         <- value.update(_ += (key -> newTxnVar))
                 } yield ()
             }
      } yield ()
    }

  private[stm] def delete(key: K): F[Unit] =
    withLock(internalStructureLock) {
      for {
        txnVarMap <- value.get
        _ <- txnVarMap.get(key) match {
               case Some(_) =>
                 value.update(_ -= key)
               case None =>
                 Async[F].unit
             }
      } yield ()
    }
}

object TxnVarMap {

  /** Creates a new `TxnVarMap` with the given initial entries. Requires an implicit `STM[F]` runtime. */
  def of[F[_]: STM: Async, K, V](valueMap: Map[K, V]): F[TxnVarMap[F, K, V]] =
    for {
      id <- STM[F].txnVarIdGen.updateAndGet(_ + 1)
      values <- valueMap.toList.traverse { kv =>
                  TxnVar.of(kv._2).map(txv => kv._1 -> txv)
                }
      valuesRef             <- Async[F].ref(MutableMap(values: _*))
      lock                  <- Semaphore[F](1)
      internalStructureLock <- Semaphore[F](1)
    } yield TxnVarMap(id, valuesRef, lock, internalStructureLock)
}
