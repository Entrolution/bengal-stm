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
package bengal.stm.model.runtime

import cats.effect.Ref

/** The id-allocation capability [[bengal.stm.model.TxnVar.of]] and [[bengal.stm.model.TxnVarMap.of]] require.
  *
  * An `STM[F]` runtime is a `TxnIdAllocator[F]`, so user code never names this trait: the implicit runtime already in
  * scope satisfies the bound. It exists so the model does not depend on the runtime cake — a `TxnVar` needs one
  * counter, not a scheduler — which is also what makes the model unit-testable against a stub.
  *
  * The member is `private[stm]`, so the trait cannot be usefully implemented outside the library: a deferred
  * `private[stm]` member is not overridable from outside the package. Every allocator a user can obtain is therefore an
  * `STM` runtime, and id uniqueness keeps its single-source guarantee (one global counter per runtime — TxnVarMap's
  * id-registry comment explains why that uniqueness is load-bearing).
  */
trait TxnIdAllocator[F[_]] {
  private[stm] val txnVarIdGen: Ref[F, TxnVarId]
}

private[stm] object TxnIdAllocator {

  private[stm] def apply[F[_]](implicit ev: TxnIdAllocator[F]): TxnIdAllocator[F] =
    ev
}
