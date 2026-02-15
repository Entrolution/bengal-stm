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
    def set(newValueMap: F[Map[K, V]]): Txn[Unit] =
      STM[F].setTxnVarMapF(newValueMap, txnVarMap)

    /** Modifies the entire map by applying a pure function. */
    def modify(f: Map[K, V] => Map[K, V]): Txn[Unit] =
      STM[F].modifyTxnVarMap(f, txnVarMap)

    /** Modifies the entire map by applying an effectful function. The function must not encapsulate side effects. */
    def modifyF(f: Map[K, V] => F[Map[K, V]]): Txn[Unit] =
      STM[F].modifyTxnVarMapF(f, txnVarMap)

    /** Retrieves the value for a key, returning `None` if the key was deleted in this transaction. */
    def get(key: => K): Txn[Option[V]] =
      STM[F].getTxnVarMapValue(key, txnVarMap)

    /** Upserts a key-value pair. Creates the key if not present. */
    def set(key: => K, newValue: => V): Txn[Unit] =
      STM[F].setTxnVarMapValue(key, newValue, txnVarMap)

    /** Upserts a key-value pair via an effect. The effect must not encapsulate side effects. */
    def setF(key: => K, newValue: F[V]): Txn[Unit] =
      STM[F].setTxnVarMapValueF(key, newValue, txnVarMap)

    /** Modifies the value for a key by applying a pure function. Throws if the key is absent. */
    def modify(key: => K, f: V => V): Txn[Unit] =
      STM[F].modifyTxnVarMapValue(key, f, txnVarMap)

    /** Modifies the value for a key by applying an effectful function. The function must not encapsulate side effects.
      */
    def modifyF(key: => K, f: V => F[V]): Txn[Unit] =
      STM[F].modifyTxnVarMapValueF(key, f, txnVarMap)

    /** Removes a key-value pair from the map. Throws if the key is absent. */
    def remove(key: => K): Txn[Unit] =
      STM[F].removeTxnVarMapValue(key, txnVarMap)
  }

  /** Syntax for `Txn` with `commit`, error handling, and F-variant error recovery. */
  implicit class TxnOps[F[_]: STM, V](txn: => Txn[V]) {

    /** Commits the transaction, executing it against the STM runtime and lifting the result into `F`. */
    def commit: F[V] =
      STM[F].commitTxn(txn)

    /** Recovers from transaction errors/aborts by mapping the throwable to a fallback transaction. */
    def handleErrorWith(f: Throwable => Txn[V]): Txn[V] =
      STM[F].handleErrorWithInternal(txn)(f)

    /** Recovers from transaction errors/aborts via an effectful handler. The effect must not encapsulate side effects.
      */
    def handleErrorWithF(f: Throwable => F[Txn[V]]): Txn[V] =
      STM[F].handleErrorWithInternalF(txn)(f)
  }
}
