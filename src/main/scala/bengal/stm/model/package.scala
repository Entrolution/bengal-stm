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

import cats.free.Free

/** Core model types for Bengal STM. */
package object model {

  /** A composable transaction that produces a value of type `V` when committed. Built using a free monad over the STM
    * algebra, enabling static analysis of transaction variable domains before execution.
    */
  type Txn[V]                   = Free[TxnOrErr, V]
  private[stm] type TxnOrErr[V] = Either[TxnErratum, TxnAdt[V]]
}
