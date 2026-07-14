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
  // isValidated short-circuit sound ON RE-APPLICATION. The other half is
  // structural rather than disciplinary: addReadId/addWriteId/mergeWith reset
  // isValidated, so a footprint mutated after validation re-validates instead
  // of trusting a stale dedup. (Today's call sites validate last anyway; the
  // reset is what makes that an invariant instead of a convention.)
  //
  // The copy below is also the only reason isUnderApproximated survives
  // validation, and the runtime compares VALIDATED footprints everywhere. Build
  // the result with a constructor call instead of a copy and the field defaults
  // back to false, every under-approximated footprint silently regains the
  // scheduler's trust, and the H3 fix is defeated without a single call site
  // changing. LemmaValidatedPreservesUnderApproximation exists to catch exactly
  // that.
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

  private[stm] lazy val combinedRawIds: Set[Long] = combinedIds.map(_.value)

  private[stm] lazy val updateRawIds: Set[Long] = updatedIds.map(_.value)

  private[stm] lazy val readRawIds: Set[Long] = readIds.map(_.value)

  // Content changes reset the validation memo: new ids may overlap the write
  // set, so the result must earn its isValidated short-circuit through a fresh
  // getValidated, not inherit it.
  private[stm] def addReadId(id: TxnVarRuntimeId): IdFootprint =
    this.copy(readIds = readIds + id, isValidated = false)

  private[stm] def addWriteId(id: TxnVarRuntimeId): IdFootprint =
    this.copy(updatedIds = updatedIds + id, isValidated = false)

  private[stm] def mergeWith(idScope: IdFootprint): IdFootprint =
    this.copy(
      readIds     = readIds ++ idScope.readIds,
      updatedIds  = updatedIds ++ idScope.updatedIds,
      isValidated = false,
      // Incompleteness is contagious: a merge is only as trustworthy as its
      // least trustworthy half.
      isUnderApproximated = isUnderApproximated || idScope.isUnderApproximated
    )

  // The static analysis could not see the whole access set. Everything after
  // the throw point in the free recursion went unrecorded, so what we hold is a
  // subset of the truth and must not be trusted as if it were the truth.
  //
  // Alone among the mutators this PRESERVES isValidated: it changes no read or
  // write content, and validation only dedupes reads and carries this flag
  // through — a validated footprint is still a fixpoint after marking.
  private[stm] def markUnderApproximated: IdFootprint =
    this.copy(isUnderApproximated = true)

  // SPEC: DocumentsParentReadChildWriteCaught — the conflict matrix over the
  // parent hierarchy is: raw-id overlap with the other side's writes (first
  // conjunct); my ids — reads included — under a parent the other side
  // WRITES (second conjunct); and my WRITES under a parent the other side
  // READS (third conjunct). The third closes the H5 phantom-write-skew gap
  // (whole-map read vs new-key insert; see specs/README.md): a structure read
  // observes the key set, so any child-entry write must conflict with it.
  // Parent-read vs child-read stays compatible.
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
  // which makes its unvalidated reads trivially safe.
  //
  // It keeps running alone, on every attempt: this is a STEADY-STATE cost, not a
  // first-attempt one that a refinement recovers. Coverage cannot force a
  // refinement, because coversActualFootprint short-circuits to true on this
  // flag (checking it would send every such transaction round again for no
  // gain). The only other trigger is a dirty log — and having run alone, nothing
  // changed underneath it, so it is not dirty. It commits under-approximated,
  // and the same Txn under-approximates again on the next commit. The measured
  // price of that is in the README; it is confined to this path, and it is what
  // the path costs to be correct.
  private[stm] def isCompatibleWith(input: IdFootprint): Boolean =
    !isUnderApproximated && !input.isUnderApproximated &&
      asymmetricCompatibleWith(input) && input.asymmetricCompatibleWith(this)

  // SPEC: CommitSnapshotValid — the H6 fix. TRUE iff declaring THIS footprint
  // excluded at least as much concurrency as declaring `actual` would have. If
  // so, Contract C on this footprint implies Contract C on what the transaction
  // really touched, and its scheduling was sound after all.
  //
  // SPEC: LemmaCoverageIsSound — and that implication is machine-checked rather
  // than argued: specs/common/FootprintLemmas.tla quantifies over every ordered
  // TRIPLE of complete footprints in the 4-id universe and confirms that if
  // `declared` covers `actual`, then any peer compatible with `declared` is
  // compatible with `actual` too. Two thirds of the soundness argument rest on
  // that lemma. The third does not, and is worth stating plainly: it assumes
  // `actual` is a LOG footprint and therefore complete by construction. Nothing
  // in TLC checks that assumption — it is a claim about this Scala.
  //
  // Contract C — the property the scheduler owes the commit protocol: two
  // transactions whose DECLARED footprints are incompatible never overlap in
  // their execute windows. The commit protocol assumes it, the scheduler is
  // checked against it, and specs/README.md documents both halves.
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
