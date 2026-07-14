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
 * Quantified lemmas range over all 2^4 x 2^4 x 2 = 512 footprints on this
 * universe, hence 262,144 ordered pairs. The trailing factor of 2 is the H3
 * under-approximation flag, which doubled the space: every algebraic lemma
 * below is now checked over untrustworthy footprints as well as trustworthy
 * ones. The pair lemmas are near-instant; LemmaCoverageIsSound is a TRIPLE and
 * dominates the runtime, which is why it is restricted to CompleteFootprints
 * below.
 *
 * TWO KINDS OF LEMMA LIVE HERE — read the labels carefully:
 *
 *   Lemma*    — properties the relation SHOULD have and does. A failure
 *               means the Footprint.tla encoding drifted from intent (or a
 *               code change broke the relation's algebra).
 *
 *   Documents* — pins of current behaviour (positive controls and
 *               consequence-laden compatibilities). Historical note: until
 *               the H5 fix, DocumentsReadGapH5 pinned the phantom-write-skew
 *               gap here — a parent-structure READ was judged compatible
 *               with a child-entry WRITE. The relation now catches that
 *               conflict (DocumentsParentReadChildWriteCaught below), and
 *               the behavioural regression test lives in
 *               SerializabilityOracleSpec.
 *)

EXTENDS Footprint

PV1 == [val |-> 1, par |-> NoParent]
S   == [val |-> 3, par |-> NoParent]
E1  == [val |-> 4, par |-> 3]
E2  == [val |-> 5, par |-> 3]

Ids == {PV1, S, E1, E2}

(* The full footprint universe: the H3 under-approximation flag is part of it,
   so a footprint here can be untrustworthy as well as empty or full. *)
Footprints == [reads : SUBSET Ids, updates : SUBSET Ids, under : BOOLEAN]

(* The COMPLETE footprints — those the walker fully determined. LemmaCoverageIsSound
   quantifies over these rather than over the whole universe, and EVERY TRIPLE THE
   RESTRICTION DROPS IS ONE THAT WOULD HAVE DISCHARGED VACUOUSLY. An
   under-approximated footprint is incompatible with everything
   (LemmaUnderApproximatedIncompatibleWithAll), so an under-approximated `declared`
   or `g` falsifies both sides of the implication; and `actual` is a log footprint,
   which is complete by construction, since no single log entry can be
   under-approximated. The restriction is therefore exact, and it cuts the check
   8x — which on a triple is the difference between 16.7M evaluations and 134M.
   The negative control in specs/README.md (break Covers, watch
   LemmaCoverageIsSound fail) confirms the restricted lemma still has teeth. *)
CompleteFootprints == [reads : SUBSET Ids, updates : SUBSET Ids, under : {FALSE}]

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
   the other half is structural — addReadId/addWriteId/mergeWith reset the
   flag, so a footprint mutated after validation re-validates rather than
   trusting a stale dedup (see the anchor in IdFootprint.scala). *)
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

(* THE H3 FIX, as an algebraic property. An UNDER-APPROXIMATED footprint is
   incompatible with EVERY footprint — the empty one, a pure reader, itself,
   everything. The ids that would reveal a conflict are precisely the ids that
   are missing, so no "compatible" verdict is available from the evidence; any
   such verdict would be an inference from absent information.

   This is what serializes a transaction whose static analysis threw, which in
   turn makes its unvalidated reads trivially safe (it runs alone, so nothing
   can change under it). Before the fix, an empty-because-unknown footprint was
   treated exactly like an empty-because-it-touches-nothing one, and was
   therefore compatible with everything — the scheduler's entire defence
   switched off for that transaction. *)
ASSUME LemmaUnderApproximatedIncompatibleWithAll ==
    \A f \in Footprints, g \in Footprints :
        (f.under \/ g.under) => ~IsCompatible(f, g)

(* Validation must PRESERVE the flag. IdFootprint.getValidated is a `copy`, so
   it carries isUnderApproximated through — if it ever reset it, a validated
   footprint would silently regain the scheduler's trust, and since the runtime
   compares getValidated footprints everywhere, the fix would be a no-op. *)
ASSUME LemmaValidatedPreservesUnderApproximation ==
    \A f \in Footprints :
        Validated(f).under = f.under

(* Incompleteness is contagious under merge: IdFootprint.mergeWith ORs the flag,
   so a merge is only as trustworthy as its least trustworthy half. *)
ASSUME LemmaMergePropagatesUnderApproximation ==
    \A f \in Footprints, g \in Footprints :
        MergeWith(f, g).under = (f.under \/ g.under)

(*
 * THE H6 FIX, and the reason it is safe to act on.
 *
 * If the DECLARED footprint covers the ACTUAL one, then every transaction the
 * scheduler judged compatible with the declared footprint really is compatible
 * with what the transaction actually touched. Contract C on `declared` therefore
 * implies Contract C on `actual`, and the scheduling was sound after all.
 *
 * That is the entire justification for the commit-time coverage check: pass it
 * and the run can publish; fail it and the transaction was scheduled on a
 * footprint that did not describe it, so it must abort BEFORE publishing and
 * re-run with the refined footprint. Checked here over every ordered TRIPLE of
 * footprints in the universe, not argued.
 *
 * `actual` is required complete (~a.under) because it is: the log footprint is
 * built by merging real log entries, and no entry can be under-approximated.
 *)
ASSUME LemmaCoverageIsSound ==
    \A d \in CompleteFootprints, a \in CompleteFootprints, g \in CompleteFootprints :
        (Covers(d, a) /\ IsCompatible(d, g)) => IsCompatible(a, g)

(* Coverage is reflexive: an accurate footprint always covers itself, so a
   transaction with a statically-known access set NEVER trips the check. This is
   what bounds the fix's cost to the data-dependent path. *)
ASSUME LemmaCoverageReflexive ==
    \A f \in Footprints : Covers(f, f)

(* A parent-structure READ covers a child-entry READ (the whole-map-read case:
   the log expands it into a read-only entry per key, and every one of those is
   covered) -- but a parent READ does NOT cover a child WRITE, because reading a
   map does not announce that you will write a key in it. This asymmetry is why
   the check cannot be a plain subset test. *)
ASSUME DocumentsParentReadCoversChildRead ==
    /\ Covers(FP({S}, {}), FP({E1}, {}))
    /\ ~Covers(FP({S}, {}), FP({}, {E1}))

(* A parent-structure WRITE covers child-entry writes AND reads -- the setVarMap
   case, where the log expands a structure write into a per-key update entry. *)
ASSUME DocumentsParentWriteCoversChildren ==
    /\ Covers(FP({}, {S}), FP({}, {E1}))
    /\ Covers(FP({}, {S}), FP({E1}, {}))

(* And the H6 defect itself: declaring a write to one id does NOT cover writing a
   DIFFERENT one. This is the case the check exists to catch. *)
ASSUME DocumentsWrongIdIsNotCovered ==
    ~Covers(FP({}, {PV1}), FP({}, {E1}))

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

(* Child-entry WRITE vs parent-structure READ is caught by the third
   conjunct (the H5 fix): a structure read observes the key set, so a
   new-key insert must conflict with it. Before the fix this pair was
   judged compatible — the phantom-write-skew hole confirmed behaviourally
   at ~98% of contended whole-map-read + insert reps — the SerializabilityOracleSpec
   probe, now a regression test. *)
ASSUME DocumentsParentReadChildWriteCaught ==
    ~IsCompatible(FP({S}, {}), FP({}, {E1}))

(* Parent-structure READ vs child-entry READ remains compatible — the fix
   must not serialize pure readers *)
ASSUME DocumentsParentReadChildReadCompatible ==
    IsCompatible(FP({S}, {}), FP({E1}, {}))

-------------------------------------------------------------------------------
(* Pins of current behaviour with protocol-level consequences *)
-------------------------------------------------------------------------------

(*
 * Two new-key inserts into the SAME map (distinct keys) are compatible —
 * neither writes the structure id, and the entry ids are distinct. This is
 * what allows two such transactions to run concurrently and contend on the
 * map's structural commitLock via the new-key lock fallback
 * (TxnLogUpdateVarMapEntry.lock). With TWO maps this becomes H2's
 * accurate-mode deadlock candidate. Compatibility here is
 * correct (the writes genuinely don't conflict); the hazard lives in the
 * lock aliasing, which Spec A models.
 *)
ASSUME DocumentsSiblingInsertsCompatible ==
    IsCompatible(FP({}, {E1}), FP({}, {E2}))

(*
 * The H3 fix's two edges, pinned as concrete cases.
 *
 * "I do not know what I touch" (FPU) must NOT be compatible with a transaction
 * that touches nothing — that exact conflation is what H3 exploited, since
 * TxnRuntime.commit's `case _ => IdFootprint.empty` branch produced an EMPTY
 * footprint meaning UNKNOWN, and the relation read it as meaning NOTHING.
 *)
ASSUME DocumentsUnknownIsNotTheSameAsEmpty ==
    /\ ~IsCompatible(FPU({}, {}), EmptyFootprint)
    /\ IsCompatible(EmptyFootprint, EmptyFootprint)

(*
 * ...and the flag must not serialize anything it should not: two COMPLETE,
 * genuinely disjoint footprints stay compatible. The fix costs throughput only
 * on the path that actually threw.
 *)
ASSUME DocumentsCompleteFootprintsStillCompatible ==
    IsCompatible(FP({PV1}, {}), FP({}, {E1}))

-------------------------------------------------------------------------------
(* Trivial behaviour so TLC has a spec to run; the ASSUMEs are the payload *)
-------------------------------------------------------------------------------

VARIABLE unused

Init == unused = TRUE
Next == UNCHANGED unused

(* ---------------------------------------------------------------------------
   NC-8: DOES H6'S COVERAGE CHECK SUBSUME THE COMMIT-TIME DIRTY CHECK?

   Deleting the dirty check outright (IsDirty(t) == FALSE) changes no verdict in
   any of the seven commit configs. That is a fact, and on its own it means
   nothing: it is equally consistent with "the check is redundant" and with "the
   catalogue is too small to exercise it". A model cannot tell those apart by
   staying green.

   So prove it instead. The argument runs:

     1. For my write set to move, some peer must PUBLISH to an entity e I write.
     2. Since the H6 fix, a peer only publishes if ITS coverage holds -- so e, or
        e's parent, is in that peer's DECLARED updates.
     3. If MY coverage holds, e or e's parent is in MY declared updates.
     4. So we BOTH declare a write to e ... and therefore our declared footprints
        are INCOMPATIBLE.                                    <-- THIS STEP
     5. Contract C then keeps us out of each other's execute windows, so it cannot
        have published inside mine. My write set cannot have moved. Not dirty.

   Step 4 is the load-bearing one and it is a claim about the RELATION, so it can
   be settled here, exhaustively, rather than argued. Steps 1-3 are the H6 fix and
   the definition of Covers; step 5 is Contract C, which Spec B discharges.

   If this holds, CoverageOk => ~IsDirty, and the dirty check is dead code.
   CommitProtocol.tla asserts exactly that as CoverageSubsumesDirty. *)
WriteCovered(e, f) ==
    \/ e.val \in UpdateRawIds(f)
    \/ (e.par /= NoParent /\ e.par \in UpdateRawIds(f))

ASSUME LemmaCoWriteImpliesIncompatible ==
    \A f, g \in CompleteFootprints :
      \A e \in Ids :
        (WriteCovered(e, f) /\ WriteCovered(e, g)) => ~IsCompatible(f, g)

(* And the control for it: coverage is what makes step 2 and step 3 true. Without
   coverage, two transactions CAN really write the same entity while their declared
   footprints look compatible -- which is exactly H6, and exactly why the dirty
   check used to be load-bearing. Stated so nobody reads the lemma above as saying
   more than it does. *)
ASSUME DocumentsWithoutCoverageCoWritersLookCompatible ==
    \E f, g \in CompleteFootprints :
      \E e \in Ids :
        /\ IsCompatible(f, g)
        /\ ~WriteCovered(e, f)
        /\ ~WriteCovered(e, g)

===============================================================================
