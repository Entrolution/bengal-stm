--------------------------- MODULE FootprintLemmas ---------------------------
(*
 * Lemma checks over the footprint compatibility relation (Footprint.tla),
 * evaluated exhaustively by TLC over a small concrete id universe as named
 * ASSUMEs. TLC verifies every ASSUME at startup; on failure it reports the
 * assumption's LOCATION (line/column), not its name — the names are for
 * reading and grep.
 *
 * The universe: one plain var, one map structure id, and two entry ids
 * parented to that structure — the minimal shape that exercises every branch
 * of the relation (raw intersection, parent rule, both asymmetric directions).
 *
 *   PV1 : plain TxnVar
 *   S   : TxnVarMap structural id       (TxnStateEntity.runtimeId)
 *   E1  : map entry, parent = S         (TxnVarMap.getRuntimeId)
 *   E2  : map entry, parent = S, E2 /= E1
 *
 * Quantified lemmas range over all 2^4 x 2^4 = 256 footprints on this
 * universe (65,536 ordered pairs) — exhaustive and fast.
 *
 * TWO KINDS OF LEMMA LIVE HERE — read the labels carefully:
 *
 *   Lemma*    — properties the relation SHOULD have and does. A failure
 *               means the Footprint.tla encoding drifted from intent (or a
 *               code change broke the relation's algebra).
 *
 *   Documents* — pins of CURRENT behaviour, including behaviour suspected
 *               to be defective. In particular DocumentsReadGapH5 asserts
 *               that a parent-structure READ is judged compatible with a
 *               child-entry WRITE — the hypothesised phantom-write-skew hole
 *               (plan §6, H5). If isCompatibleWith is ever fixed to close
 *               that gap, this ASSUME goes RED on purpose: update the lemma,
 *               Footprint.tla, the verdict table in specs/README.md, and the
 *               H5 row together.
 *)

EXTENDS Footprint

PV1 == [val |-> 1, par |-> NoParent]
S   == [val |-> 3, par |-> NoParent]
E1  == [val |-> 4, par |-> 3]
E2  == [val |-> 5, par |-> 3]

Ids == {PV1, S, E1, E2}

Footprints == [reads : SUBSET Ids, updates : SUBSET Ids]

-------------------------------------------------------------------------------
(* Algebraic properties of the relation as encoded *)
-------------------------------------------------------------------------------

(* isCompatibleWith is symmetric by construction — a conjunction of both
   asymmetric directions *)
ASSUME LemmaCompatSymmetric ==
    \A f \in Footprints, g \in Footprints :
        IsCompatible(f, g) <=> IsCompatible(g, f)

(* getValidated is a fixpoint after one application. This covers the
   re-application half of the isValidated memoisation flag's soundness;
   the other half is call-site discipline — addReadId/addWriteId/mergeWith
   copy the flag through without resetting it, so validated footprints
   must not be mutated afterwards (see the anchor in IdFootprint.scala). *)
ASSUME LemmaValidatedIdempotent ==
    \A f \in Footprints :
        Validated(Validated(f)) = Validated(f)

(* Validation never touches the update set *)
ASSUME LemmaValidatedPreservesUpdates ==
    \A f \in Footprints :
        Validated(f).updates = f.updates

(* Validation only removes reads, so it can only relax the relation:
   compatible footprints stay compatible after validation. The scheduler
   compares getValidated footprints everywhere (TxnRuntime.commit and the
   dirty-path refinement), so this direction is the one the runtime relies
   on. *)
ASSUME LemmaValidatedMonotoneCompat ==
    \A f \in Footprints, g \in Footprints :
        IsCompatible(f, g) => IsCompatible(Validated(f), Validated(g))

(* A footprint that updates anything conflicts with itself — relevant to the
   retry map, which compares parked footprints against submitted ones *)
ASSUME LemmaWriterSelfIncompatible ==
    \A f \in Footprints :
        f.updates /= {} => ~IsCompatible(f, f)

-------------------------------------------------------------------------------
(* Positive controls: conflicts the relation DOES catch *)
-------------------------------------------------------------------------------

(* Plain write-write on the same id *)
ASSUME DocumentsWriteWriteCaught ==
    ~IsCompatible(FP({}, {PV1}), FP({}, {PV1}))

(* Plain read-write on the same id *)
ASSUME DocumentsReadWriteCaught ==
    ~IsCompatible(FP({PV1}, {}), FP({}, {PV1}))

(* Read-read is compatible *)
ASSUME DocumentsReadReadCompatible ==
    IsCompatible(FP({PV1}, {}), FP({PV1}, {}))

(* Child-entry WRITE vs parent-structure WRITE is caught by the parent rule
   (the child side's parent value hits the structure side's update set) *)
ASSUME DocumentsParentWriteChildWriteCaught ==
    ~IsCompatible(FP({}, {S}), FP({}, {E1}))

(* Child-entry READ vs parent-structure WRITE is caught likewise *)
ASSUME DocumentsParentWriteChildReadCaught ==
    ~IsCompatible(FP({E1}, {}), FP({}, {S}))

-------------------------------------------------------------------------------
(* Pins of current behaviour with protocol-level consequences *)
-------------------------------------------------------------------------------

(*
 * H5 (plan §6): parent-structure READ vs child-entry WRITE is judged
 * COMPATIBLE. The parent rule tests ids only against the other side's
 * UPDATE set, so E1.par = S.val is never compared against a mere read of S.
 * Consequence: "read whole map" and "insert new key" can run concurrently
 * under fully accurate footprints, and since structure reads are never
 * commit-validated (TxnLogReadOnlyVarMapStructureEntry), phantom write skew is
 * hypothesised reachable. Spec A (Phase 3) carries the behavioural check;
 * this lemma pins the relation-level fact.
 *
 * IF THIS GOES RED: the relation was fixed. Flip the H5 verdict row and
 * rewrite this lemma to assert the incompatibility.
 *)
ASSUME DocumentsReadGapH5 ==
    IsCompatible(FP({S}, {}), FP({}, {E1}))

(*
 * Two new-key inserts into the SAME map (distinct keys) are compatible —
 * neither writes the structure id, and the entry ids are distinct. This is
 * what allows two such transactions to run concurrently and contend on the
 * map's structural commitLock via the new-key lock fallback
 * (TxnLogUpdateVarMapEntry.lock). With TWO maps this becomes H2's
 * accurate-mode deadlock candidate (plan §6). Compatibility here is
 * correct (the writes genuinely don't conflict); the hazard lives in the
 * lock aliasing, which Spec A models.
 *)
ASSUME DocumentsSiblingInsertsCompatible ==
    IsCompatible(FP({}, {E1}), FP({}, {E2}))

-------------------------------------------------------------------------------
(* Trivial behaviour so TLC has a spec to run; the ASSUMEs are the payload *)
-------------------------------------------------------------------------------

VARIABLE unused

Init == unused = TRUE
Next == UNCHANGED unused

===============================================================================
