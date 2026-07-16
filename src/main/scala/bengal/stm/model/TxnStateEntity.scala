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

import cats.effect.std.Semaphore

import bengal.stm.model.runtime._

private[stm] trait TxnStateEntity[F[_]] {
  private[stm] def id: TxnVarId

  // This entity's OWN runtime id, taken directly from its TxnVarId: the key it
  // is logged under, and the id it contributes to a footprint. For a TxnVarMap
  // it doubles as the PARENT of every one of its entries' ids
  // (TxnVarMap.getRuntimeId), which is what makes a whole-map read conflict with
  // a write to any key in it.
  //
  // The TxnVarId comes from the runtime's single global allocator
  // (STM.txnVarIdGen), which also issues every map-key existential id, so
  // runtime ids are unique by construction — no hashing is involved anywhere in
  // the id chain, and the raw-value comparisons in IdFootprint can never alias
  // two distinct entities.
  final private[stm] lazy val runtimeId: TxnVarRuntimeId =
    TxnVarRuntimeId(id)
  private[stm] def commitLock: Semaphore[F]
}
