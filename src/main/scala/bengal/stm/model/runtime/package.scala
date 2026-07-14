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

package object runtime {
  private[stm] type TxnVarId = Long
  private[stm] type TxnId    = Long

  // IMMUTABLE on purpose — the type is the thread-safety guarantee, not a style
  // choice. Three classes of reader touch a map's index with NO lock held and
  // outside Contract C's protection: footprint-COMPATIBLE transactions running
  // concurrently with a structural writer (per-key ops on different keys are
  // compatible BY DESIGN), the static-analysis pass (which runs before the
  // transaction is even scheduled), and the park-path staleness checks (which
  // run after the parker has left activeTransactions). A mutable index mutated
  // in place under a writers-only lock is a data race against all three; an
  // immutable snapshot swapped through the Ref cannot be. Writers still take
  // TxnVarMap.internalStructureLock, but for get-or-create ATOMICITY (two
  // concurrent inserts of one key must not mint two TxnVars), not for reader
  // exclusion.
  private[stm] type VarIndex[F[_], K, V] = Map[K, TxnVar[F, V]]
}
