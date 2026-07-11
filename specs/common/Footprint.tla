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

FP(reads, updates) == [reads |-> reads, updates |-> updates]

EmptyFootprint == FP({}, {})

(* combinedIds *)
CombinedIds(f) == f.reads \cup f.updates

(* combinedRawIds *)
CombinedRawIds(f) == { id.val : id \in CombinedIds(f) }

(* updateRawIds *)
UpdateRawIds(f) == { id.val : id \in f.updates }

(* mergeWith *)
MergeWith(f, g) ==
    FP(f.reads \cup g.reads, f.updates \cup g.updates)

(*
 * asymmetricCompatibleWith:
 *   combinedRawIds.intersect(input.updateRawIds).isEmpty &&
 *     !combinedIds.exists(_.parent.exists(p => input.updateRawIds.contains(p.value)))
 *
 * Note the known asymmetry gap (hypothesis H5): each side's ids — reads
 * included — are tested against the OTHER side's UPDATE ids only. A child
 * WRITE on this side is caught against a parent WRITE on the other side
 * (second conjunct, evaluated from the child's side), but a child WRITE is
 * never tested against a parent READ. FootprintLemmas.tla pins this down.
 *)
AsymCompat(f, g) ==
    /\ CombinedRawIds(f) \cap UpdateRawIds(g) = {}
    /\ ~\E id \in CombinedIds(f) :
            id.par /= NoParent /\ id.par \in UpdateRawIds(g)

(* isCompatibleWith *)
IsCompatible(f, g) == AsymCompat(f, g) /\ AsymCompat(g, f)

(*
 * getValidated:
 *   readIds := (readIds -- updatedIds)
 *                .filter(id => id.parent.forall(pid => !updateRawIds.contains(pid.value)))
 * i.e. drop reads that this footprint also updates (structural id equality),
 * and drop reads whose parent's raw value is among this footprint's updates.
 *)
Validated(f) ==
    FP({ id \in (f.reads \ f.updates) :
            id.par = NoParent \/ id.par \notin UpdateRawIds(f) },
       f.updates)

===============================================================================
