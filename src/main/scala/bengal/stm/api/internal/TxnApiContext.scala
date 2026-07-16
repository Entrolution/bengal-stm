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
package bengal.stm.api.internal

import scala.util.{ Failure, Success, Try }

import cats.effect.kernel.Async
import cats.free.Free

import bengal.stm.model.TxnErratum._
import bengal.stm.model._

private[stm] trait TxnApiContext[F[_]] {
  this: AsyncImplicits[F] with TxnAdtContext[F] =>

  private def liftSuccess[V](txnAdt: TxnAdt[V]): Txn[V] =
    Free.liftF[TxnOrErr, V](Right(txnAdt))

  private def liftFailure(txnErr: TxnErratum): Txn[Unit] =
    Free.liftF[TxnOrErr, Unit](Left(txnErr))

  /** The unit transaction. Touches nothing, so it contributes nothing to the footprint. */
  val unit: Txn[Unit] =
    liftSuccess(TxnUnit)

  /** Lifts an `F[V]` into a transaction.
    *
    * THE EFFECT RUNS TWICE PER EXECUTED COMMIT ATTEMPT that reaches it, and again on every retry. `TxnDelay` is
    * interpreted by BOTH compilers — once by the static-analysis pass that computes the footprint, once by the log run
    * that actually commits — so `spec` must be idempotent and free of observable side effects. A flow that has already
    * terminated earlier — at a `waitFor` retry or an `abort` — reaches it in NEITHER pass: both walkers stop at the
    * first terminal erratum, so an effect positioned after one runs zero times for that attempt. This is the only way
    * to lift an arbitrary effect into a `Txn`, which makes it the sharpest edge in the public API.
    *
    * It is also EAGERLY FORCED during the analysis pass, unlike the value argument to `set`/`modify`. A `spec` that
    * throws there under-approximates the footprint, and an under-approximated transaction is treated as conflicting
    * with everything and runs alone. See the README's "Static analysis and transaction footprints".
    */
  def fromF[V](spec: F[V]): Txn[V] =
    liftSuccess(TxnDelay(spec))

  /** Lifts a by-name computation into a transaction. Same contract as [[fromF]]: the thunk runs in BOTH the analysis
    * pass and the real run when the flow reaches it (and in neither pass after an earlier `waitFor` retry or `abort`),
    * so it must not carry side effects, and if it throws it under-approximates the footprint.
    */
  def delay[V](thunk: => V): Txn[V] =
    liftSuccess(TxnDelay(Async[F].delay(thunk)))

  /** Lifts an already-computed value into a transaction. Never re-evaluated, never forced. */
  def pure[V](value: V): Txn[V] =
    liftSuccess(TxnPure(value))

  /** Semantically blocks the transaction until `predicate` holds. No thread is blocked.
    *
    * WHAT WAKES A PARKED TRANSACTION IS ITS READ SET, NOT THIS PREDICATE. The predicate is evaluated eagerly, here,
    * while the `Txn` value is being built — there is no ADT node for it, so the runtime never sees it and cannot
    * re-check it. It collapses immediately to one of three constants: `unit`, a retry, or an abort. A parked
    * transaction is woken when some other transaction commits a write its FOOTPRINT conflicts with; it then re-runs
    * from the top, which rebuilds this node against freshly-read values.
    *
    * The rule that follows is load-bearing, and breaking it parks a transaction permanently:
    *
    * EVERYTHING THE PREDICATE DEPENDS ON MUST BE READ FROM A `TxnVar`/`TxnVarMap` INSIDE THE SAME TRANSACTION, BEFORE
    * THIS CALL. Hoist the read out and the transaction has an empty read set, nothing can ever conflict with it, no
    * wake ever fires, and it sleeps forever. A parked transaction's footprint is exactly its pre-`waitFor` read set
    * (the analysis stops here on a retry), so reads positioned after the `waitFor` contribute nothing to the wake —
    * they never could legitimately, since the predicate cannot depend on them.
    *
    * A predicate that THROWS aborts the transaction rather than retrying it (the `Failure` branch below). And an
    * enclosing `handleErrorWith` does not absorb the retry — a blocked transaction stays blocked.
    */
  def waitFor(predicate: => Boolean): Txn[Unit] =
    Try(predicate) match {
      case Success(true) =>
        unit
      case Success(_) =>
        liftFailure(TxnRetry)
      case Failure(exception) =>
        abort(exception)
    }

  /** Aborts the transaction with the given throwable. No writes are published. It surfaces as a failed `F` from
    * `commit` and is recoverable with `handleErrorWith`.
    */
  def abort(ex: Throwable): Txn[Unit] =
    liftFailure(TxnError(ex))

  private[stm] def handleErrorWithInternal[V](fa: => Txn[V])(
    f: Throwable => Txn[V]
  ): Txn[V] =
    handleErrorWithInternalF(fa)(ex => Async[F].delay(f(ex)))

  private[stm] def handleErrorWithInternalF[V](fa: => Txn[V])(
    f: Throwable => F[Txn[V]]
  ): Txn[V] =
    liftSuccess(TxnHandleError(Async[F].delay(fa), f))

  private[stm] def getTxnVar[V](txnVar: TxnVar[F, V]): Txn[V] =
    liftSuccess(TxnGetVar(txnVar))

  private[stm] def setTxnVar[V](
    newValue: => V,
    txnVar: TxnVar[F, V]
  ): Txn[Unit] =
    setTxnVarF(Async[F].delay(newValue), txnVar)

  private[stm] def setTxnVarF[V](
    newValue: F[V],
    txnVar: TxnVar[F, V]
  ): Txn[Unit] =
    liftSuccess(TxnSetVar(newValue, txnVar))

  private[stm] def modifyTxnVar[V](f: V => V, txnVar: TxnVar[F, V]): Txn[Unit] =
    modifyTxnVarF((v: V) => Async[F].delay(f(v)), txnVar)

  private[stm] def modifyTxnVarF[V](
    f: V => F[V],
    txnVar: TxnVar[F, V]
  ): Txn[Unit] =
    for {
      value <- getTxnVar(txnVar)
      _     <- setTxnVarF(f(value), txnVar)
    } yield ()

  private[stm] def getTxnVarMap[K, V](
    txnVarMap: TxnVarMap[F, K, V]
  ): Txn[Map[K, V]] =
    liftSuccess(TxnGetVarMap(txnVarMap))

  private[stm] def setTxnVarMap[K, V](
    newValueMap: => Map[K, V],
    txnVarMap: TxnVarMap[F, K, V]
  ): Txn[Unit] =
    setTxnVarMapF(Async[F].delay(newValueMap), txnVarMap)

  private[stm] def setTxnVarMapF[K, V](
    newValueMap: F[Map[K, V]],
    txnVarMap: TxnVarMap[F, K, V]
  ): Txn[Unit] =
    liftSuccess(TxnSetVarMap(newValueMap, txnVarMap))

  private[stm] def modifyTxnVarMap[K, V](
    f: Map[K, V] => Map[K, V],
    txnVarMap: TxnVarMap[F, K, V]
  ): Txn[Unit] =
    modifyTxnVarMapF((m: Map[K, V]) => Async[F].delay(f(m)), txnVarMap)

  private[stm] def modifyTxnVarMapF[K, V](
    f: Map[K, V] => F[Map[K, V]],
    txnVarMap: TxnVarMap[F, K, V]
  ): Txn[Unit] =
    for {
      value <- getTxnVarMap(txnVarMap)
      _     <- setTxnVarMapF(f(value), txnVarMap)
    } yield ()

  private[stm] def getTxnVarMapValue[K, V](
    key: => K,
    txnVarMap: TxnVarMap[F, K, V]
  ): Txn[Option[V]] =
    liftSuccess(TxnGetVarMapValue(Async[F].delay(key), txnVarMap))

  private[stm] def setTxnVarMapValue[K, V](
    key: => K,
    newValue: => V,
    txnVarMap: TxnVarMap[F, K, V]
  ): Txn[Unit] =
    setTxnVarMapValueF(key, Async[F].delay(newValue), txnVarMap)

  private[stm] def setTxnVarMapValueF[K, V](
    key: => K,
    newValue: F[V],
    txnVarMap: TxnVarMap[F, K, V]
  ): Txn[Unit] =
    liftSuccess(
      TxnSetVarMapValue(Async[F].delay(key), newValue, txnVarMap)
    )

  private[stm] def modifyTxnVarMapValue[K, V](
    key: => K,
    f: V => V,
    txnVarMap: TxnVarMap[F, K, V]
  ): Txn[Unit] =
    modifyTxnVarMapValueF(key, (v: V) => Async[F].delay(f(v)), txnVarMap)

  private[stm] def modifyTxnVarMapValueF[K, V](
    key: => K,
    f: V => F[V],
    txnVarMap: TxnVarMap[F, K, V]
  ): Txn[Unit] =
    liftSuccess(
      TxnModifyVarMapValue(Async[F].delay(key), f, txnVarMap)
    )

  private[stm] def removeTxnVarMapValue[K, V](
    key: => K,
    txnVarMap: TxnVarMap[F, K, V]
  ): Txn[Unit] =
    liftSuccess(TxnDeleteVarMapValue(Async[F].delay(key), txnVarMap))
}
