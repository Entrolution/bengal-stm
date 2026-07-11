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
  isValidated: Boolean = false,
  // SPEC: CommitSnapshotValid — the H3 fix. TRUE when the static-analysis
  // walker could not determine the full access set, so this footprint is an
  // UNDER-APPROXIMATION: some ids the transaction really touches are missing.
  //
  // That is categorically different from an empty footprint. "I touch nothing"
  // is a fact the scheduler can act on; "I don't know what I touch" is not, and
  // treating the two alike is unsound rather than merely imprecise. Reads are
  // never commit-validated and hold no lock, so the scheduler's footprint
  // conflict-avoidance is the ONLY defence against a stale read; a missing id
  // silently switches it off. Unsound in BOTH directions, too: an under-declared
  // transaction reads what a peer overwrites, AND its undeclared writes
  // invalidate a correctly-declared peer's reads (a transaction with no reads at
  // all still breaks its peer). See specs/commit/CommitH3*.cfg.
  //
  // An under-approximated footprint is therefore incompatible with EVERYTHING
  // (isCompatibleWith below), which serializes such a transaction against all
  // others. It then runs alone, so nothing can change under it, so its reads are
  // trivially valid. The cost is throughput on that path only.
  isUnderApproximated: Boolean = false
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
    this.copy(
      readIds    = readIds ++ idScope.readIds,
      updatedIds = updatedIds ++ idScope.updatedIds,
      // Incompleteness is contagious: a merge is only as trustworthy as its
      // least trustworthy half.
      isUnderApproximated = isUnderApproximated || idScope.isUnderApproximated
    )

  // The static analysis could not see the whole access set. Everything after
  // the throw point in the free recursion went unrecorded, so what we hold is a
  // subset of the truth and must not be trusted as if it were the truth.
  private[stm] def markUnderApproximated: IdFootprint =
    this.copy(isUnderApproximated = true)

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

  // SPEC: CommitSnapshotValid — the H3 fix, first clause. A footprint that
  // under-approximates its transaction's access set is incompatible with every
  // other transaction, itself included, because the ids that would have
  // revealed the conflict are precisely the ids that are missing. There is no
  // sound way to compare against a set you know to be incomplete: any
  // "compatible" verdict would be an inference from absent evidence.
  //
  // The transaction is therefore serialized against all others and runs alone,
  // which makes its unvalidated reads trivially safe. The dirty path then
  // refines the footprint from the ACTUAL log (which is complete by
  // construction, being built from real entries), so the retry runs at full
  // concurrency.
  private[stm] def isCompatibleWith(input: IdFootprint): Boolean =
    !isUnderApproximated && !input.isUnderApproximated &&
      asymmetricCompatibleWith(input) && input.asymmetricCompatibleWith(this)

  // SPEC: CommitSnapshotValid — the H6 fix. TRUE iff declaring THIS footprint
  // excluded at least as much concurrency as declaring `actual` would have. If
  // so, Contract C on this footprint implies Contract C on what the transaction
  // really touched, and its scheduling was sound after all
  // (LemmaCoverageIsSound in specs/common/FootprintLemmas.tla checks that
  // exhaustively over every ordered triple of footprints — it is not argued).
  //
  // NOT a subset test on raw ids. That would be unusable as well as wrong: a
  // whole-map READ legitimately expands, in the log, into a read-only entry for
  // EVERY existing key (TxnLogValid.getVarMap), so the actual footprint properly
  // contains ids the static walker never named. Those ids ARE covered — a
  // parent-structure read conflicts with any child write via the relation's
  // third conjunct — and a subset test would abort every whole-map read in the
  // library.
  //
  // Hence the hierarchy, and note its asymmetry: an id is covered if this
  // footprint names it OR names its PARENT, but a parent READ covers only a
  // child READ, whereas a parent WRITE covers child reads and writes alike.
  // Reading a map does not announce that you will write a key in it.
  private[stm] def covers(actual: IdFootprint): Boolean =
    actual.readIds.forall { id =>
      combinedRawIds.contains(id.value) ||
      id.parent.exists(p => combinedRawIds.contains(p.value))
    } && actual.updatedIds.forall { id =>
      updateRawIds.contains(id.value) ||
      id.parent.exists(p => updateRawIds.contains(p.value))
    }
}

private[stm] object IdFootprint {
  private[stm] val empty: IdFootprint = IdFootprint(Set(), Set())
}
