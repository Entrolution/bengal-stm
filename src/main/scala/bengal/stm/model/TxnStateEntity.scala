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

import java.util.UUID

import cats.effect.Ref
import cats.effect.std.Semaphore

import bengal.stm.model.runtime._

private[stm] trait TxnStateEntity[F[_], V] {
  private[stm] def id: TxnVarId

  // This entity's OWN runtime id, derived from its TxnVarId: the key it is
  // logged under, and the id it contributes to a footprint. For a TxnVarMap it
  // doubles as the PARENT of every one of its entries' ids
  // (TxnVarMap.getRuntimeId), which is what makes a whole-map read conflict with
  // a write to any key in it.
  //
  // Note: We run this through a deterministic UUID mapping
  // to mitigate the chance of increment-based IDs colliding
  // with bare hash codes
  final private[stm] lazy val runtimeId: TxnVarRuntimeId =
    TxnVarRuntimeId(UUID.nameUUIDFromBytes(id.toString.getBytes).hashCode())
  protected def value: Ref[F, V]
  private[stm] def commitLock: Semaphore[F]
}
