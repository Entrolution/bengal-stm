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

/** A mutable transactional variable holding a single value of type `T`.
  *
  * `TxnVar` instances are created outside the `Txn` monad via [[TxnVar.of]] and then read/written within transactions
  * using the syntax extensions provided by the `STM` implicit class or `bengal.stm.syntax.all._`.
  *
  * @tparam F
  *   the effect type
  * @tparam T
  *   the value type
  */
case class TxnVar[F[_], T](
  private[stm] val id: TxnVarId,
  protected val value: Ref[F, T],
  private[stm] val commitLock: Semaphore[F]
) extends TxnStateEntity[F, T] {

  private[stm] lazy val get: F[T] = value.get

  private[stm] def set(newValue: T): F[Unit] = value.set(newValue)
}

object TxnVar {

  /** Creates a new `TxnVar` with the given initial value. Requires an implicit `STM[F]` runtime. */
  def of[F[_]: STM: Async, T](value: T): F[TxnVar[F, T]] =
    for {
      id       <- STM[F].txnVarIdGen.updateAndGet(_ + 1)
      valueRef <- Async[F].ref(value)
      lock     <- Semaphore[F](1)
    } yield TxnVar(id, valueRef, lock)
}
