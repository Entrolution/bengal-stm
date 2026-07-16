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

// SEALED, so an ADT case can only be declared in this file: the interpreters in
// TxnCompilerContext are matches over this hierarchy, and a case they have no
// arm for is a transaction operation that silently does nothing — the exact
// soundness-hole class the footprint machinery exists to close. The cases are
// not final because they nest in a trait: 2.13's -Xlint outer-reference check
// rejects final there.
sealed private[stm] trait TxnAdt[V]

private[stm] trait TxnAdtContext[F[_]] {

  private[stm] case object TxnUnit extends TxnAdt[Unit]

  private[stm] case class TxnDelay[V](thunk: F[V]) extends TxnAdt[V]

  private[stm] case class TxnPure[V](value: V) extends TxnAdt[V]

  private[stm] case class TxnGetVar[V](txnVar: TxnVar[F, V]) extends TxnAdt[V]

  private[stm] case class TxnSetVar[V](
    newValue: F[V],
    txnVar: TxnVar[F, V]
  ) extends TxnAdt[Unit]

  private[stm] case class TxnGetVarMap[K, V](txnVarMap: TxnVarMap[F, K, V]) extends TxnAdt[Map[K, V]]

  private[stm] case class TxnGetVarMapValue[K, V](
    key: F[K],
    txnVarMap: TxnVarMap[F, K, V]
  ) extends TxnAdt[Option[V]]

  private[stm] case class TxnSetVarMap[K, V](
    newMap: F[Map[K, V]],
    txnVarMap: TxnVarMap[F, K, V]
  ) extends TxnAdt[Unit]

  private[stm] case class TxnSetVarMapValue[K, V](
    key: F[K],
    newValue: F[V],
    txnVarMap: TxnVarMap[F, K, V]
  ) extends TxnAdt[Unit]

  private[stm] case class TxnModifyVarMapValue[K, V](
    key: F[K],
    f: V => F[V],
    txnVarMap: TxnVarMap[F, K, V]
  ) extends TxnAdt[Unit]

  private[stm] case class TxnDeleteVarMapValue[K, V](
    key: F[K],
    txnVarMap: TxnVarMap[F, K, V]
  ) extends TxnAdt[Unit]

  private[stm] case class TxnHandleError[V](
    fa: F[Txn[V]],
    f: Throwable => F[Txn[V]]
  ) extends TxnAdt[V]
}
