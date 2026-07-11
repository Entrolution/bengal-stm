------------------------------ MODULE Footprint ------------------------------
(*
 * Exact TLA+ encoding of bengal-stm's footprint compatibility relation.
 *
 * This module is operators-only (no variables, no behaviour) and is EXTENDed
 * by both Scheduler.tla (Spec B) and CommitProtocol.tla (Spec A) so that
 * "compatible" means precisely the same thing everywhere. Scenario configs
 * must never hand-assign compatibility between transactions — it is always
 * computed by these operators. (The plan's H2 hypothesis was misdiagnosed in
 * an early draft precisely by hand-reasoning compatibility; this module is
 * the guard against repeating that.)
 *
 * Scala correspondence:
 *   - TxnVarRuntimeId(value: Int, parent: Option[TxnVarRuntimeId])
 *     An id is modelled as [val |-> Nat, par |-> Nat or NoParent]. Only the
 *     parent's raw value is ever consulted by the relation, so `par` holds
 *     the parent's value directly rather than a nested record.
 *   - IdFootprint(readIds, updatedIds)
 *     Modelled as [reads |-> set of ids, updates |-> set of ids]. The Scala
 *     `isValidated` boolean is a memoisation flag, not semantics. The
 *     ValidatedIdempotent lemma (FootprintLemmas.tla) covers its
 *     re-application half; the other half is call-site discipline, since
 *     the Scala mutators copy the flag through (see the IdFootprint.scala
 *     anchor).
 *
 * Runtime IDs in the real system are UUID-hash-derived Ints
 * (TxnStateEntity.runtimeId); collisions are possible but are excluded
 * here by construction (distinct model ids). This ASSUMPTION is recorded in
 * specs/README.md.
 *)

EXTENDS Naturals, FiniteSets

CONSTANT NoParent

FP(reads, updates) == [reads |-> reads, updates |-> updates, under |-> FALSE]

(* An UNDER-APPROXIMATED footprint: the static-analysis walker threw, so some
   ids the transaction really touches are missing (IdFootprint.isUnderApproximated).
   Categorically different from an empty footprint — "I touch nothing" is a fact,
   "I do not know what I touch" is not — and conflating the two was H3. *)
FPU(reads, updates) == [reads |-> reads, updates |-> updates, under |-> TRUE]

EmptyFootprint == FP({}, {})

(* combinedIds *)
CombinedIds(f) == f.reads \cup f.updates

(* combinedRawIds *)
CombinedRawIds(f) == { id.val : id \in CombinedIds(f) }

(* updateRawIds *)
UpdateRawIds(f) == { id.val : id \in f.updates }

(* readRawIds *)
ReadRawIds(f) == { id.val : id \in f.reads }

(* mergeWith — incompleteness is contagious *)
MergeWith(f, g) ==
    [reads   |-> f.reads \cup g.reads,
     updates |-> f.updates \cup g.updates,
     under   |-> f.under \/ g.under]

(*
 * asymmetricCompatibleWith:
 *   combinedRawIds.intersect(input.updateRawIds).isEmpty &&
 *     !combinedIds.exists(_.parent.exists(p => input.updateRawIds.contains(p.value))) &&
 *     !updatedIds.exists(_.parent.exists(p => input.readRawIds.contains(p.value)))
 *
 * The third conjunct (added with the H5 fix) tests this side's child
 * WRITES against the other side's parent READS: a structure read observes
 * the key set, so a child-entry write must conflict with it. Together the
 * conjuncts give the full conflict matrix over the two-level hierarchy —
 * raw overlap with writes; any-op-under-written-parent; write-under-read-
 * parent — while parent-read vs child-read stays compatible.
 * FootprintLemmas.tla pins every case.
 *)
AsymCompat(f, g) ==
    /\ CombinedRawIds(f) \cap UpdateRawIds(g) = {}
    /\ ~\E id \in CombinedIds(f) :
            id.par /= NoParent /\ id.par \in UpdateRawIds(g)
    /\ ~\E id \in f.updates :
            id.par /= NoParent /\ id.par \in ReadRawIds(g)

(*
 * isCompatibleWith. THE FIRST CLAUSE IS THE H3 FIX: a footprint that
 * under-approximates its transaction's access set is incompatible with
 * EVERYTHING, because the ids that would have revealed a conflict are exactly
 * the ids that are missing. Any "compatible" verdict would be an inference from
 * absent evidence. Such a transaction is therefore serialized against all
 * others and runs alone, which makes its unvalidated reads trivially safe.
 *)
IsCompatible(f, g) ==
    /\ ~f.under
    /\ ~g.under
    /\ AsymCompat(f, g)
    /\ AsymCompat(g, f)

(*
 * getValidated:
 *   readIds := (readIds -- updatedIds)
 *                .filter(id => id.parent.forall(pid => !updateRawIds.contains(pid.value)))
 * i.e. drop reads that this footprint also updates (structural id equality),
 * and drop reads whose parent's raw value is among this footprint's updates.
 *)
Validated(f) ==
    [reads   |-> { id \in (f.reads \ f.updates) :
                       id.par = NoParent \/ id.par \notin UpdateRawIds(f) },
     updates |-> f.updates,
     under   |-> f.under]   \* preserved, as IdFootprint.getValidated's copy does

===============================================================================
