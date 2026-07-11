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

private[stm] case class IdFootprint(
  readIds: Set[TxnVarRuntimeId],
  updatedIds: Set[TxnVarRuntimeId],
  isValidated: Boolean = false
) {

  // SPEC: LemmaValidatedIdempotent — one application of this transform is a
  // fixpoint (FootprintLemmas.tla checks it exhaustively), which makes the
  // isValidated short-circuit sound ON RE-APPLICATION. The flag's full
  // soundness also needs call-site discipline: addReadId/addWriteId/mergeWith
  // copy isValidated through unchanged, so a validated footprint that is
  // subsequently mutated would skip re-validation (today's call sites never
  // do that — validation happens last, in the runtime).
  private[stm] lazy val getValidated =
    if (isValidated) {
      this
    } else {
      this.copy(
        readIds     = (readIds -- updatedIds).filter(id => id.parent.forall(pid => !updateRawIds.contains(pid.value))),
        isValidated = true
      )
    }

  private[stm] lazy val combinedIds: Set[TxnVarRuntimeId] =
    readIds ++ updatedIds

  private[stm] lazy val combinedRawIds: Set[Int] = combinedIds.map(_.value)

  private[stm] lazy val updateRawIds: Set[Int] = updatedIds.map(_.value)

  private[stm] lazy val readRawIds: Set[Int] = readIds.map(_.value)

  private[stm] def addReadId(id: TxnVarRuntimeId): IdFootprint =
    this.copy(readIds = readIds + id)

  private[stm] def addWriteId(id: TxnVarRuntimeId): IdFootprint =
    this.copy(updatedIds = updatedIds + id)

  private[stm] def mergeWith(idScope: IdFootprint): IdFootprint =
    this.copy(readIds = readIds ++ idScope.readIds, updatedIds = updatedIds ++ idScope.updatedIds)

  // SPEC: DocumentsParentReadChildWriteCaught — the conflict matrix over the
  // parent hierarchy is: raw-id overlap with the other side's writes (first
  // conjunct); my ids — reads included — under a parent the other side
  // WRITES (second conjunct); and my WRITES under a parent the other side
  // READS (third conjunct). The third closes the H5 phantom-write-skew gap
  // (whole-map read vs new-key insert; docs/plans/formal-specs.md §6):
  // a structure read observes the key set, so any child-entry write must
  // conflict with it. Parent-read vs child-read stays compatible.
  // ASSUMES a 2-level id hierarchy (parents are never themselves parented —
  // true by construction today): all parent checks here and in getValidated
  // are one-hop, and deeper nesting would need multi-hop coverage.
  private def asymmetricCompatibleWith(input: IdFootprint): Boolean =
    combinedRawIds.intersect(input.updateRawIds).isEmpty && !combinedIds.exists(
      _.parent.exists(p => input.updateRawIds.contains(p.value))
    ) && !updatedIds.exists(
      _.parent.exists(p => input.readRawIds.contains(p.value))
    )

  private[stm] def isCompatibleWith(input: IdFootprint): Boolean =
    asymmetricCompatibleWith(input) && input.asymmetricCompatibleWith(this)
}

private[stm] object IdFootprint {
  private[stm] val empty: IdFootprint = IdFootprint(Set(), Set())
}
