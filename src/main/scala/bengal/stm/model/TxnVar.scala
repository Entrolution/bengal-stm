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

import cats.effect.Ref
import cats.effect.kernel.Async
import cats.effect.std.Semaphore
import cats.syntax.all._

import bengal.stm.STM
import bengal.stm.model.runtime._

/** A mutable transactional variable holding a single value of type `T`.
  *
  * `TxnVar` instances are created outside the `Txn` monad via [[TxnVar.of]] and then read/written within transactions
  * using the syntax extensions provided by the `STM` implicit class or `bengal.stm.syntax.all._`.
  *
  * Construction is factory-only: [[TxnVar.of]] draws the variable's identity from the runtime's allocator, and the
  * sealed constructor is what makes that identity trustworthy — user-built or copied instances could alias one backing
  * `Ref` under two runtime ids, or two `Ref`s under one, and the scheduler's conflict detection is sound only because
  * neither can exist.
  *
  * @tparam F
  *   the effect type
  * @tparam T
  *   the value type
  */
final class TxnVar[F[_], T] private[stm] (
  private[stm] val id: TxnVarId,
  protected val value: Ref[F, T],
  private[stm] val commitLock: Semaphore[F]
) extends TxnStateEntity[F] {

  private[stm] lazy val get: F[T] = value.get

  private[stm] def set(newValue: T): F[Unit] = value.set(newValue)
}

object TxnVar {

  /** Creates a new `TxnVar` with the given initial value. Requires an implicit `STM[F]` runtime.
    *
    * The variable belongs to that runtime: all transactions touching it must be committed through the same `STM[F]`
    * instance. Use under a different runtime is undefined — see [[bengal.stm.STM.runtime]].
    */
  def of[F[_]: STM: Async, T](value: T): F[TxnVar[F, T]] =
    for {
      id       <- STM[F].txnVarIdGen.updateAndGet(_ + 1)
      valueRef <- Async[F].ref(value)
      lock     <- Semaphore[F](1)
    } yield new TxnVar(id, valueRef, lock)
}
