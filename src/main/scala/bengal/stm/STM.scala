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

import cats.effect.Ref
import cats.effect.kernel.Async
import cats.effect.std.Semaphore
import cats.implicits._

import bengal.stm.api.internal.TxnApiContext
import bengal.stm.model._
import bengal.stm.model.runtime._
import bengal.stm.runtime.{ TxnCompilerContext, TxnLogContext, TxnRuntimeContext }

/** Software Transactional Memory runtime for Cats Effect.
  *
  * STM provides composable in-memory transactions with automatic concurrency management including locking, retries,
  * semantic blocking, and intelligent scheduling. Create a runtime via [[STM.runtime]].
  *
  * BOTH IMPORTS ARE REQUIRED. An implicit `STM[F]` in scope does not on its own make `.get`/`.set`/`.modify`/`.commit`
  * available — a member of an implicit value is not an implicit-conversion candidate, so the syntax has to be imported.
  * Omitting it gives a confusing error rather than a missing-import one: the runtime's own `private[stm]` members
  * shadow the extension methods, so `counter.get` reports "cannot be accessed" instead of "not a member".
  *
  * {{{
  * import ai.entrolution.bengal.stm.STM
  * import ai.entrolution.bengal.stm.model.TxnVar
  * import ai.entrolution.bengal.stm.syntax.all._
  *
  * STM.runtime[IO].flatMap { implicit stm =>
  *   for {
  *     counter <- TxnVar.of[IO, Int](0)
  *     _       <- counter.modify(_ + 1).commit
  *     value   <- counter.get.commit
  *   } yield value
  * }
  * }}}
  *
  * A transaction runs TWICE per commit attempt: once in a static-analysis pass that computes its footprint (the
  * variables and keys it touches), and once for real. Both passes stop at the first `waitFor` retry or `abort`, so a
  * step positioned after one runs in neither pass for that attempt. The scheduler uses the footprint to run only
  * transactions that cannot conflict. Two consequences reach callers, and the README covers both: `delay`/`fromF`
  * thunks are evaluated in both passes when the flow reaches them, so they must not carry side effects; and a
  * transaction whose analysis pass THROWS gets an under-approximated footprint, which conflicts with everything and
  * therefore runs alone.
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

  /** Transaction operations on `TxnVar`. Requires `import ai.entrolution.bengal.stm.syntax.all._` — an implicit
    * `STM[F]` alone does not bring these into scope. (`import stm._` on an instance exposes the same base operations
    * but NOT the F-variants `setF`/`modifyF`/`handleErrorWithF`, which exist only in `syntax.all`.)
    */
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

  /** Transaction operations on `TxnVarMap`. Requires `import ai.entrolution.bengal.stm.syntax.all._` — an implicit
    * `STM[F]` alone does not bring these into scope. (`import stm._` on an instance exposes the same base operations
    * but NOT the F-variants `setF`/`modifyF`/`handleErrorWithF`, which exist only in `syntax.all`.)
    */
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

    /** Retrieves the value for a key. Returns `None` when the key does not exist — whether it was never created, or was
      * deleted earlier in this transaction. It does not fail.
      */
    def get(key: => K): Txn[Option[V]] =
      getTxnVarMapValue(key, txnVarMap)

    /** Upserts a key-value pair. Creates the key if not present. */
    def set(key: => K, newValue: => V): Txn[Unit] =
      setTxnVarMapValue(key, newValue, txnVarMap)

    /** Modifies the value for a key by applying a pure function. FAILS THE TRANSACTION if the key is absent: nothing is
      * thrown at `Txn`-construction time, the resulting `F` from `commit` fails, no writes are published, and it is
      * recoverable with `handleErrorWith`.
      */
    def modify(key: => K, f: V => V): Txn[Unit] =
      modifyTxnVarMapValue(key, f, txnVarMap)

    /** Removes a key-value pair from the map. FAILS THE TRANSACTION if the key is absent, on the same terms as
      * `modify(key, f)` above — a failed `F`, not a thrown exception, and recoverable.
      */
    def remove(key: => K): Txn[Unit] =
      removeTxnVarMapValue(key, txnVarMap)
  }

  /** Provides `commit` and error-handling operations on `Txn` values. */
  implicit class TxnOps[V](txn: => Txn[V]) {

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
      commitTxn(txn)

    /** Recovers from transaction errors and aborts by mapping the throwable to a fallback transaction.
      *
      * It does NOT absorb a `waitFor` retry. A retry is re-raised past this handler by design, so wrapping a blocking
      * transaction in `handleErrorWith` does not make it stop blocking.
      */
    def handleErrorWith(f: Throwable => Txn[V]): Txn[V] =
      handleErrorWithInternal(txn)(f)
  }
}

object STM {

  /** Summons the implicit `STM[F]` instance. */
  def apply[F[_]](implicit stm: STM[F]): STM[F] =
    stm

  /** Creates a new STM runtime, allocating the internal ID generators and scheduler. This is the main entry point.
    *
    * ONE RUNTIME PER SET OF TRANSACTIONAL VARIABLES. Every `TxnVar`/`TxnVarMap` belongs to the runtime in scope when it
    * was created, and all transactions touching it must run through that runtime. Sharing a variable across two
    * runtimes is undefined: each scheduler enforces its conflict guarantees only over its own transactions, so two
    * conflicting transactions committed under different runtimes can interleave unchecked — stale reads and lost
    * updates with no error raised.
    */
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
