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

import bengal.stm.STM
import bengal.stm.model.runtime._

import cats.effect.Ref
import cats.effect.kernel.Async
import cats.effect.std.Semaphore
import cats.syntax.all._

import java.util.UUID
import scala.collection.mutable.{ Map => MutableMap }

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

  private def getRuntimeExistentialId(key: K): TxnVarRuntimeId =
    TxnVarRuntimeId(UUID.nameUUIDFromBytes((id, key).toString.getBytes).hashCode())

  private[stm] def getRuntimeId(
    key: K
  ): F[TxnVarRuntimeId] =
    Async[F].delay(getRuntimeExistentialId(key).addParent(runtimeId))

  // Only called when key is known to not exist
  private def add(newKey: K, newValue: V): F[Unit] =
    for {
      newTxnVar <- TxnVar.of(newValue)
      _ <- withLock(internalStructureLock)(
             value.update(_ += (newKey -> newTxnVar))
           )
    } yield ()

  private[stm] def addOrUpdate(key: K, newValue: V): F[Unit] =
    for {
      txnVarMap <- value.get
      _ <- txnVarMap.get(key) match {
             case Some(tVar) =>
               withLock(internalStructureLock)(
                 tVar.set(newValue)
               )
             case None =>
               add(key, newValue)
           }
    } yield ()

  private[stm] def delete(key: K): F[Unit] =
    for {
      txnVarMap <- value.get
      _ <- txnVarMap.get(key) match {
             case Some(_) =>
               withLock(internalStructureLock)(value.update(_ -= key))
             case None =>
               Async[F].unit
           }
    } yield ()
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
