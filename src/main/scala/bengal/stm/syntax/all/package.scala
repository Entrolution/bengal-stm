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
package bengal.stm.syntax

import bengal.stm._
import bengal.stm.model._

/** Syntax extensions for STM transactions, including F-variant methods for effectful arguments.
  *
  * Import `bengal.stm.syntax.all._` to bring these implicit classes into scope. The F-variant methods (`setF`,
  * `modifyF`, `handleErrorWithF`) accept arguments wrapped in `F[_]`. '''Important:''' the `F[_]` arguments must not
  * encapsulate side effects, as they may be evaluated multiple times during transaction retries.
  */
package object all {

  /** Syntax for `TxnVar` with F-variant operations. */
  implicit class TxnVarOps[F[_]: STM, V](txnVar: TxnVar[F, V]) {

    /** Retrieves the current value within a transaction. */
    def get: Txn[V] =
      STM[F].getTxnVar(txnVar)

    /** Sets a new value within a transaction. */
    def set(newValue: => V): Txn[Unit] =
      STM[F].setTxnVar(newValue, txnVar)

    /** Sets a new value provided by an effect `F[V]`. The effect must not encapsulate side effects. */
    def setF(newValue: F[V]): Txn[Unit] =
      STM[F].setTxnVarF(newValue, txnVar)

    /** Modifies the value by applying a pure function. */
    def modify(f: V => V): Txn[Unit] =
      STM[F].modifyTxnVar(f, txnVar)

    /** Modifies the value by applying an effectful function. The function must not encapsulate side effects. */
    def modifyF(f: V => F[V]): Txn[Unit] =
      STM[F].modifyTxnVarF(f, txnVar)
  }

  /** Syntax for `TxnVarMap` with F-variant operations. */
  implicit class TxnVarMapOps[F[_]: STM, K, V](txnVarMap: TxnVarMap[F, K, V]) {

    /** Retrieves an immutable snapshot of the entire map. Prefer per-key access for performance. */
    def get: Txn[Map[K, V]] =
      STM[F].getTxnVarMap(txnVarMap)

    /** Replaces the entire map state. Creates/deletes keys as needed. */
    def set(newValueMap: => Map[K, V]): Txn[Unit] =
      STM[F].setTxnVarMap(newValueMap, txnVarMap)

    /** Replaces the entire map state via an effect. The effect must not encapsulate side effects. */
    def setF(newValueMap: F[Map[K, V]]): Txn[Unit] =
      STM[F].setTxnVarMapF(newValueMap, txnVarMap)

    /** Modifies the entire map by applying a pure function. */
    def modify(f: Map[K, V] => Map[K, V]): Txn[Unit] =
      STM[F].modifyTxnVarMap(f, txnVarMap)

    /** Modifies the entire map by applying an effectful function. The function must not encapsulate side effects. */
    def modifyF(f: Map[K, V] => F[Map[K, V]]): Txn[Unit] =
      STM[F].modifyTxnVarMapF(f, txnVarMap)

    /** Retrieves the value for a key. Returns `None` when the key does not exist — whether it was never created, or was
      * deleted earlier in this transaction. It does not fail.
      */
    def get(key: => K): Txn[Option[V]] =
      STM[F].getTxnVarMapValue(key, txnVarMap)

    /** Upserts a key-value pair. Creates the key if not present. */
    def set(key: => K, newValue: => V): Txn[Unit] =
      STM[F].setTxnVarMapValue(key, newValue, txnVarMap)

    /** Upserts a key-value pair via an effect. Creates the key if not present. The effect must not encapsulate side
      * effects.
      */
    def setF(key: => K, newValue: F[V]): Txn[Unit] =
      STM[F].setTxnVarMapValueF(key, newValue, txnVarMap)

    /** Modifies the value for a key by applying a pure function. FAILS THE TRANSACTION if the key is absent: nothing is
      * thrown at `Txn`-construction time, the resulting `F` from `commit` fails, no writes are published, and it is
      * recoverable with `handleErrorWith`.
      */
    def modify(key: => K, f: V => V): Txn[Unit] =
      STM[F].modifyTxnVarMapValue(key, f, txnVarMap)

    /** Modifies the value for a key by applying an effectful function. FAILS THE TRANSACTION if the key is absent, on
      * the same terms as `modify(key, f)` above — a failed `F`, not a thrown exception, and recoverable. The function
      * must not encapsulate side effects.
      */
    def modifyF(key: => K, f: V => F[V]): Txn[Unit] =
      STM[F].modifyTxnVarMapValueF(key, f, txnVarMap)

    /** Removes a key-value pair from the map. FAILS THE TRANSACTION if the key is absent, on the same terms as
      * `modify(key, f)` above — a failed `F`, not a thrown exception, and recoverable.
      */
    def remove(key: => K): Txn[Unit] =
      STM[F].removeTxnVarMapValue(key, txnVarMap)
  }

  /** Syntax for `Txn` with `commit`, error handling, and F-variant error recovery. */
  implicit class TxnOps[F[_]: STM, V](txn: => Txn[V]) {

    /** Commits the transaction, executing it against the STM runtime and lifting the result into `F`.
      *
      * CANCELLATION ABANDONS THE TRANSACTION. Cancelling the returned `F` (a `timeout`, a lost `race`, supervisor
      * shutdown) is safe and prompt: once the cancellation completes, the transaction never begins executing again,
      * never parks or retries, and holds no scheduler state — a parked `waitFor` transaction in particular can never be
      * woken by a later commit and run after its caller has gone. One carve-out: the atomic commit window itself is
      * uncancelable, so a window already executing when cancellation arrives runs to completion and its writes may be
      * published; cancellation does not interrupt a window in flight — it prevents every future one, without waiting on
      * the in-flight one.
      */
    def commit: F[V] =
      STM[F].commitTxn(txn)

    /** Recovers from transaction errors and aborts by mapping the throwable to a fallback transaction.
      *
      * It does NOT absorb a `waitFor` retry. A retry is re-raised past this handler by design, so wrapping a blocking
      * transaction in `handleErrorWith` does not make it stop blocking.
      */
    def handleErrorWith(f: Throwable => Txn[V]): Txn[V] =
      STM[F].handleErrorWithInternal(txn)(f)

    /** Recovers from transaction errors/aborts via an effectful handler. The effect must not encapsulate side effects.
      */
    def handleErrorWithF(f: Throwable => F[Txn[V]]): Txn[V] =
      STM[F].handleErrorWithInternalF(txn)(f)
  }
}
