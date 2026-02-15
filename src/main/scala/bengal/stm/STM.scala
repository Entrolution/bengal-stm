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
package bengal.stm

import bengal.stm.api.internal.TxnApiContext
import bengal.stm.model._
import bengal.stm.model.runtime._
import bengal.stm.runtime.{ TxnCompilerContext, TxnLogContext, TxnRuntimeContext }

import cats.effect.Ref
import cats.effect.kernel.Async
import cats.effect.std.Semaphore
import cats.implicits._

/** Software Transactional Memory runtime for Cats Effect.
  *
  * STM provides composable in-memory transactions with automatic concurrency management including locking, retries,
  * semantic blocking, and intelligent scheduling. Create a runtime via [[STM.runtime]] and use the implicit syntax
  * classes to build and commit transactions.
  *
  * {{{
  * for {
  *   stm     <- STM.runtime[IO]
  *   counter <- stm.TxnVar.of(0)
  *   _       <- counter.modify(_ + 1).commit(stm)
  *   value   <- counter.get.commit(stm)
  * } yield value
  * }}}
  *
  * @tparam F
  *   the effect type (must have an `Async` instance)
  */
abstract class STM[F[_]: Async]
    extends AsyncImplicits[F]
    with TxnAdtContext[F]
    with TxnLogContext[F]
    with TxnCompilerContext[F]
    with TxnRuntimeContext[F]
    with TxnApiContext[F] {

  /** Creates a new transactional variable with the given initial value. */
  def allocateTxnVar[V](value: V): F[TxnVar[F, V]]

  /** Creates a new transactional map with the given initial entries. */
  def allocateTxnVarMap[K, V](valueMap: Map[K, V]): F[TxnVarMap[F, K, V]]
  private[stm] def commitTxn[V](txn: Txn[V]): F[V]

  /** Provides transaction operations on `TxnVar` instances. Automatically available when an `STM[F]` is implicit. */
  implicit class TxnVarOps[V](txnVar: TxnVar[F, V]) {

    /** Retrieves the current value within a transaction. */
    def get: Txn[V] =
      getTxnVar(txnVar)

    /** Sets a new value within a transaction. */
    def set(newValue: => V): Txn[Unit] =
      setTxnVar(newValue, txnVar)

    /** Modifies the value by applying a pure function within a transaction. */
    def modify(f: V => V): Txn[Unit] =
      modifyTxnVar(f, txnVar)
  }

  /** Provides transaction operations on `TxnVarMap` instances. Automatically available when an `STM[F]` is implicit. */
  implicit class TxnVarMapOps[K, V](txnVarMap: TxnVarMap[F, K, V]) {

    /** Retrieves an immutable snapshot of the entire map. Prefer per-key access for performance. */
    def get: Txn[Map[K, V]] =
      getTxnVarMap(txnVarMap)

    /** Replaces the entire map state. Creates/deletes keys as needed. Prefer per-key operations for performance. */
    def set(newValueMap: => Map[K, V]): Txn[Unit] =
      setTxnVarMap(newValueMap, txnVarMap)

    /** Modifies the entire map by applying a pure function. */
    def modify(f: Map[K, V] => Map[K, V]): Txn[Unit] =
      modifyTxnVarMap(f, txnVarMap)

    /** Retrieves the value for a key, returning `None` if the key was deleted in this transaction. */
    def get(key: => K): Txn[Option[V]] =
      getTxnVarMapValue(key, txnVarMap)

    /** Upserts a key-value pair. Creates the key if not present. */
    def set(key: => K, newValue: => V): Txn[Unit] =
      setTxnVarMapValue(key, newValue, txnVarMap)

    /** Modifies the value for a key by applying a pure function. Throws if the key is absent. */
    def modify(key: => K, f: V => V): Txn[Unit] =
      modifyTxnVarMapValue(key, f, txnVarMap)

    /** Removes a key-value pair from the map. Throws if the key is absent. */
    def remove(key: => K): Txn[Unit] =
      removeTxnVarMapValue(key, txnVarMap)
  }

  /** Provides `commit` and error-handling operations on `Txn` values. */
  implicit class TxnOps[V](txn: => Txn[V]) {

    /** Commits the transaction, executing it against the STM runtime and lifting the result into `F`. */
    def commit: F[V] =
      commitTxn(txn)

    /** Recovers from transaction errors/aborts by mapping the throwable to a fallback transaction. */
    def handleErrorWith(f: Throwable => Txn[V]): Txn[V] =
      handleErrorWithInternal(txn)(f)
  }
}

object STM {

  /** Summons the implicit `STM[F]` instance. */
  def apply[F[_]](implicit stm: STM[F]): STM[F] =
    stm

  /** Creates a new STM runtime, allocating the internal ID generators and scheduler. This is the main entry point. */
  def runtime[F[_]: Async]: F[STM[F]] =
    for {
      idGenVar              <- Ref.of[F, Long](0)
      idGenTxn              <- Ref.of[F, Long](0)
      graphBuilderSemaphore <- Semaphore[F](1)
      retrySemaphore        <- Semaphore[F](1)
      stm <- Async[F].delay {
               new STM[F] {
                 override val txnVarIdGen: Ref[F, TxnVarId] = idGenVar
                 override val txnIdGen: Ref[F, TxnId]       = idGenTxn

                 val txnRuntime: TxnRuntime = new TxnRuntime {
                   override val scheduler: TxnScheduler =
                     TxnScheduler(graphBuilderSemaphore = graphBuilderSemaphore, retrySemaphore = retrySemaphore)
                 }

                 override def allocateTxnVar[V](value: V): F[TxnVar[F, V]] =
                   TxnVar.of(value)(this, this.asyncF)

                 override def allocateTxnVarMap[K, V](
                   valueMap: Map[K, V]
                 ): F[TxnVarMap[F, K, V]] =
                   TxnVarMap.of(valueMap)(this, this.asyncF)

                 override private[stm] def commitTxn[V](txn: Txn[V]): F[V] =
                   txnRuntime.commit(txn)
               }
             }
    } yield stm
}
