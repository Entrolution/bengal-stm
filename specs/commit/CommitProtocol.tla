--------------------------- MODULE CommitProtocol ---------------------------
(*
 * Spec A: the bengal-stm commit protocol — withLock / isDirty / commit.
 * Scala correspondence: TxnLogContext.scala (TxnLogValid.withLock,
 * TxnLogValid.isDirty, TxnLogValid.commit, TxnLogEntry.lock) and
 * TxnRuntimeContext.scala (AnalysedTxn.commit's dispatch). Actions cite
 * method names, never line numbers — line refs rot and verify_anchors.sh
 * cannot guard them.
 *
 * SCOPE (docs/plans/formal-specs.md §4, §8 phase 3): the log run (read
 * phase), lock resolution, lock acquisition, the commit-time dirty check,
 * publish, release, and the dirty-resubmission refinement. The SCHEDULER is
 * not modelled — it is ASSUMED, as Contract C:
 *
 *     Contract C: no two transactions whose DECLARED footprints are
 *     incompatible (IdFootprint.isCompatibleWith = FALSE) are ever
 *     concurrently inside their execute/commit window.
 *
 * Contract C is an ACTION GUARD here (TxnEnter). It is not an article of
 * faith: Spec B (scheduler/Scheduler.tla) CHECKS it as an invariant, and
 * since the H4 fix it verifies exhaustively. The assume-guarantee split is
 * therefore discharged, not aspirational.
 *
 * THE CENTRAL DESIGN FACT this spec exists to interrogate (plan §3):
 * read-only log entries are NEVER validated at commit time
 * (TxnLogReadOnlyVarEntry.isDirty = pure(false)) and they hold NO locks
 * (lock = None). Commit locks cover the WRITE SET only. The commit protocol
 * alone therefore does not give serializability — the scheduler's
 * footprint-based conflict avoidance is the only defence against stale
 * reads. Footprint ACCURACY is thus a safety precondition (H3), and any gap
 * in the compatibility relation is a direct hole (H5, fixed).
 *
 * ---------------------------------------------------------------------------
 * TWO ID SPACES — the subtlety at the heart of H2
 * ---------------------------------------------------------------------------
 * A map entry has TWO distinct runtime ids, and the code uses different ones
 * for different purposes:
 *
 *   - The EXISTENTIAL id, TxnVarMap.getRuntimeId(key) — hashed from
 *     (mapId, key), carrying the map's structure id as its PARENT. This is
 *     the id the FOOTPRINT always uses (both TxnLogEntry.idFootprint and the
 *     static-analysis walker), for present and absent keys alike.
 *
 *   - The ENTRY VAR's OWN id, txnVar.runtimeId — hashed from a fresh
 *     sequential TxnVarId minted at insert time (TxnVarMap.addOrUpdate ->
 *     TxnVar.of). It has NO parent and is unrelated to the existential id.
 *
 * The LOG is keyed by whichever one the entry resolved to when it was built:
 * the entry var's own id if the key EXISTED, the existential id if it did
 * not (TxnLogValid.writeVarMapValue / getVarMapValueEntry both branch on
 * txnVarMap.getTxnVar(key)). Meanwhile TxnLogUpdateVarMapEntry.lock resolves
 * to the ENTRY's lock if the key exists and THE MAP'S STRUCTURAL LOCK if it
 * does not.
 *
 * H2 WAS the gap between those two facts. withLock used to sort the LOG
 * ENTRIES:
 *
 *     locks <- log.toList.sortBy(_._1.value).traverse(_._2.lock)   // PRE-FIX
 *
 * so a new-key insert acquired the MAP's lock at a sort position derived from
 * a hash of (mapId, key) — ordering the acquisitions by a hash of the KEY
 * while the locks acquired belonged to the MAPS. Two unrelated id spaces, so
 * the sort ordered nothing at all, and two transactions inserting fresh keys
 * into two maps could take {M1.lock, M2.lock} in opposite orders. Their
 * footprints are compatible, so the scheduler ran them concurrently by design.
 *
 * FIXED (2026-07-11). TxnLogEntry.lock now returns the lock PAIRED WITH ITS
 * OWNER'S runtime id, and withLock sorts on the owner:
 *
 *     locks <- log.values.toList.traverse(_.lock)                  // POST-FIX
 *     locks.flatten.distinct.sortBy(_._1.value).map(_._2)...
 *
 * Owner -> lock is injective, so one ascending order over owner ids is a
 * single global total order over locks, and no set of transactions respecting
 * it can form a circular wait. LockOwnerVal below is that sort key; reverting
 * it to the log key is negative control NC-1 and must go RED.
 *
 * The bounds argument that follows is why the H2 config's scenario is the
 * right one, and it still explains why the FIXED protocol is safe: it
 * enumerates every lock two compatible transactions can share.
 *
 * WHY 2 TXNS AND 2 MAPS IS MINIMAL AND SUFFICIENT (the plan's mandatory
 * bounds note). Enumerate every way two COMPATIBLE transactions could share
 * a lock — every lock producer in TxnLogContext:
 *   - a plain var's lock: both must WRITE that var => raw-id overlap =>
 *     conjunct 1 => incompatible => Contract-C-excluded.
 *   - an entry var's lock: both must write that entry. Both sides' footprints
 *     name a map entry by its EXISTENTIAL id regardless of key existence, so
 *     this too is raw-id overlap => conjunct 1.
 *   - a map's structural lock, reached by a structure WRITE on one side: the
 *     peer holds either the same structure id (=> conjunct 1) or a child of
 *     it (=> conjunct 2, any-op-under-a-written-parent). Structure-write vs
 *     structure-write is the conjunct-1 case, not the parent rule.
 *   - a map's structural lock, reached by the NEW-KEY FALLBACK on both sides:
 *     NOT excluded. Distinct fresh keys have distinct existential ids, no
 *     structure id appears in either footprint, and neither side reads the
 *     parent — all three conjuncts pass.
 * So every lock two compatible transactions can share is a map structural
 * lock reached by the new-key fallback. A waits-for CYCLE needs at least two
 * shared locks taken in opposite orders, hence at least two maps. And two
 * suffice — see the H2 config. (A ring of n >= 3 transactions needs only one
 * shared lock per adjacent pair, but more maps still, so it does not
 * undercut minimality.)
 *
 * The dynamic-aliasing escape is closed too: a key DELETED between log-build
 * and lock-resolution would turn an entry lock into a map lock and could in
 * principle manufacture a shared lock — but the deleter's footprint contains
 * either that entry's id or the structure id, so Contract C excludes it.
 *
 * ---------------------------------------------------------------------------
 * GRANULARITY — one model step = one operation on one shared object
 * ---------------------------------------------------------------------------
 * Split in CODE ORDER. The rule learned in Phase 2 (H1) is that collapsing a
 * region can VERIFY A WRONG PROTOCOL CLEAN, so every collapse below carries
 * a written justification and none of them collapses a check-then-act gap.
 *
 *   A1. The read phase is ONE STEP PER ACCESSED ENTITY. The log run reads
 *       live state entity by entity (getVar/getVarMapValue capture `initial`;
 *       so does the first setVar on an unlogged var), so other transactions'
 *       publishes interleave between them. NOT collapsed to one snapshot:
 *       that would hide reads that straddle a concurrent publish.
 *   A2. Lock RESOLUTION is one step per write entry, in sort order —
 *       `traverse` is sequential in F, and each TxnLogUpdateVarMapEntry.lock
 *       performs a LIVE read (txnVarMap.getTxnVar(key)). Lock identity is
 *       therefore state-dependent and resolved BEFORE any acquisition. NOT
 *       collapsed: the identity a transaction resolves can in principle go
 *       stale before it acquires, and the model must be able to show that.
 *   A3. Lock ACQUISITION is one step per DISTINCT lock, in resolved order
 *       (`.distinct` keeps first occurrence — so two entries aliasing to one
 *       map lock acquire it once, which is what stops a 1-permit Semaphore
 *       self-deadlocking). This is where H2's cycle forms.
 *   A4. The dirty check (isDirty) is ONE step. JUSTIFICATION: it reads only
 *       WRITE-SET entities (read-only entries hardcode isDirty = false), and
 *       every write-set entity contributes a lock to withLock. A concurrent
 *       writer to any of them either holds the same lock (excluded) or is
 *       footprint-incompatible (excluded by Contract C in accurate mode). In
 *       FALLBACK mode that justification weakens — but a coarser check can
 *       only HIDE anomalies, never invent them, and the fallback config
 *       already goes red (H3), so the verdict is unaffected.
 *   A5. Publish (log.commit) is ONE step. JUSTIFICATION — and this is the
 *       refinement obligation from plan §3, now DISCHARGED: in accurate mode
 *       no concurrent transaction can read OR write any entity in our write
 *       set. A reader or writer of a written entity has raw-id overlap; a
 *       reader of a written entity's PARENT is caught by the relation's
 *       third conjunct — THE H5 FIX. So the publish is unobservable-as-torn,
 *       which is exactly what licenses Spec B's atomic-commit abstraction.
 *       NOTE THE DEPENDENCY: before the H5 fix a whole-map reader COULD
 *       observe a partial write set, so the two-spec decomposition itself
 *       rested on that fix. Same conservative-abstraction argument as A4 for
 *       fallback mode.
 *   A6. Lock RELEASE is one step (Resource releases in reverse order).
 *       JUSTIFICATION: releasing only ever frees locks. Splitting it adds
 *       states in which strictly fewer locks are held, which cannot create a
 *       waits-for cycle that atomic release avoids, and the publish has
 *       already happened, so no safety property can turn on the order.
 *   A7. Static analysis and AnalysedTxn construction fold into Init (as in
 *       Spec B, reduction R4) — pre-protocol, no shared-state contention.
 *   A1a. Intra-transaction access ORDER is NONDETERMINISTIC, not fixed. The
 *       catalogue records each transaction's access SET, not its program
 *       order, so a fixed order would model ONE program and hide the
 *       interleavings of the others — a false-GREEN risk, the direction that
 *       matters (red verdicts are hand-validated against the code anyway).
 *       Quantifying over all orders makes each clean verdict hold for EVERY
 *       program with that access set. It over-approximates, so a
 *       counterexample must be validated against the code before acceptance.
 *   A8. A whole-map READ is one step. TxnVarMap.get is NOT atomic in the code
 *       (it reads the index Ref, then each entry's Ref in turn), so it can in
 *       principle observe a torn map. Sound here by EXACTLY the A5 argument:
 *       in accurate mode the relation's third conjunct makes any child-entry
 *       writer incompatible with a structure reader, so no one can mutate the
 *       map under a whole-map read. LIKE A5, THIS REDUCTION DEPENDS ON THE H5
 *       FIX; pre-fix it was unsound, and it weakens in fallback mode (where,
 *       as with A4/A5, a coarser model can only hide anomalies, and the
 *       fallback configs already go red).
 *
 * VALUES ARE VERSION COUNTERS. A var's value is abstracted to a monotone
 * commit counter. The code's isDirty compares VALUES, so a write of an
 * unchanged value would be seen as clean where the model sees a version
 * bump. The abstraction therefore over-approximates dirtiness: it can
 * produce spurious dirty retries (safe — a retry re-reads) but never MISSES
 * a real change (unsound would be the other way round). A map STRUCTURE's
 * version bumps whenever any of its entries is written, faithful to
 * TxnVarMap.get returning the whole K -> V map.
 *
 * WHERE VERSIONS ARE NOT ENOUGH, and what this model does and does NOT say
 * about it. For a new-key insert the log entry's `initial` is None, and
 * TxnLogUpdateVarMapEntry.isDirty then asks `oValue.isDefined` — "does the key
 * exist NOW" — not "did the version move". EntryDirty branches on exactly
 * that, so the operator is faithful. BUT THE BRANCH IS NEVER EXERCISED by the
 * current catalogue, and the honest reason is structural: firing it needs two
 * transactions writing the SAME entry id, which is raw-id overlap, hence
 * incompatible, hence serialized by Contract C — so the second one's snapshot
 * always finds the key present and takes the version branch instead. It is
 * reachable only by combining maps WITH under-declaration, which no config
 * does. Nor is an insert-then-delete ABA representable at all: Publish only
 * ever sets keyExists TRUE, and TxnLogUpdateVarMapEntry.commit's delete path
 * (`case (Some(_), None) => txnVarMap.delete(key)`) is not modelled. Deletes
 * and the maps+fallback combination are the obvious next scenarios; until then
 * this paragraph is a scope statement, not a verified claim.
 *
 * OUT OF SCOPE, deliberately:
 *   - The TxnLogRetry re-validation branch, as BEHAVIOUR. Its park/wake
 *     consequences are Spec B's (Phase 2, H1). What Spec A owes it is the
 *     fidelity pin that it is VACUOUS for a pure reader — no locks, never
 *     dirty — and that is the NoLocksWithoutWrites invariant below, which a
 *     read-only transaction in the accurate config makes non-trivial. A
 *     model that "helpfully" re-validated reads before parking would mask
 *     the very gap that forced hasChangedSinceRead into existence.
 *   - TxnVarMap.internalStructureLock (a leaf lock strictly below every
 *     commitLock modelled here) and runtime-id hash collisions (assumed
 *     distinct — see specs/README.md).
 *)

EXTENDS Footprint, Integers, FiniteSets, Sequences

CONSTANTS
    Txns,            \* the scenario's transaction set (catalogue below)
    UnderDeclared,   \* txns whose static analysis fell back to an EMPTY
                     \* footprint (TxnRuntime.commit's handleErrorWith) — the
                     \* "fallback mode" of plan §4, per-transaction
    MaxInc           \* dirty-resubmission bound per txn

---------------------------------------------------------------------------
(* Entities and their runtime ids.

   Runtime ids are UUID-hash-derived Ints (TxnStateEntity.runtimeId,
   TxnVarMap.getRuntimeExistentialId), so their order is pseudorandom
   relative to creation order and EVERY ordering class is realised by some
   program. The values below pin one such class adversarially — which is
   legitimate precisely because nothing in the code constrains the order.

   Ids are [val |-> Nat, par |-> Nat or NoParent], per common/Footprint.tla.
   Map-entry ids are the EXISTENTIAL ids (parent = the map's structure id):
   that is the id space the footprint always uses. *)
---------------------------------------------------------------------------

X   == [val |-> 10, par |-> NoParent]   \* plain var
Y   == [val |-> 20, par |-> NoParent]   \* plain var
M1  == [val |-> 30, par |-> NoParent]   \* map 1, structure id
M2  == [val |-> 40, par |-> NoParent]   \* map 2, structure id

(* Existential entry ids. The four values are chosen so that the two H2
   transactions sort their entries into OPPOSITE map-lock orders — the
   adversarial hash ordering. Nothing else depends on them. *)
M1a == [val |-> 11, par |-> 30]   \* key "a" of map 1
M2a == [val |-> 12, par |-> 40]   \* key "a" of map 2
M2b == [val |-> 13, par |-> 40]   \* key "b" of map 2
M1b == [val |-> 14, par |-> 30]   \* key "b" of map 1
M1c == [val |-> 15, par |-> 30]   \* key "c" of map 1  } the aliasing pair:
M1d == [val |-> 16, par |-> 30]   \* key "d" of map 1  } two fresh keys, ONE map

PlainVars  == {X, Y}
MapStructs == {M1, M2}
MapEntries == {M1a, M1b, M1c, M1d, M2a, M2b}
Entities   == PlainVars \cup MapStructs \cup MapEntries

(* The entry TxnVar's OWN runtimeId value — hashed from a fresh sequential
   TxnVarId, hence unrelated to the existential id above. This is the log key
   for an entry whose key ALREADY EXISTS. Offsetting by 100 keeps it distinct
   from every existential id; any values would do, since the code constrains
   neither space nor their relation. *)
EntryVarVal(e) == e.val + 100

(* The map structure an entry belongs to. *)
ParentStruct(e) == CHOOSE m \in MapStructs : m.val = e.par

---------------------------------------------------------------------------
(* Locks — one commitLock per TxnVar and per TxnVarMap (Semaphore(1)). *)
---------------------------------------------------------------------------

NoLock == "none"

EntryLock(e) ==
    CASE e = M1a -> "LE_M1a" [] e = M1b -> "LE_M1b"
      [] e = M1c -> "LE_M1c" [] e = M1d -> "LE_M1d"
      [] e = M2a -> "LE_M2a" [] e = M2b -> "LE_M2b"

StructLock(m) == IF m = M1 THEN "LM1" ELSE "LM2"

Locks == {"LX", "LY", "LM1", "LM2",
          "LE_M1a", "LE_M1b", "LE_M1c", "LE_M1d", "LE_M2a", "LE_M2b"}

(* TxnLogEntry.lock, resolved against LIVE state (`exists` is read at
   withLock time, not at log-build time):
     - TxnLogUpdateVarEntry.lock                -> txnVar.commitLock
     - TxnLogUpdateVarMapStructureEntry.lock    -> txnVarMap.commitLock
     - TxnLogUpdateVarMapEntry.lock             -> the ENTRY's commitLock if
       the key exists, ELSE THE MAP'S commitLock  <-- the H2 aliasing
   Read-only entries return None and never reach here. *)
LockOfWrite(e, exists) ==
    CASE e = X  -> "LX"
      [] e = Y  -> "LY"
      [] e \in MapStructs -> StructLock(e)
      [] e \in MapEntries -> IF exists THEN EntryLock(e) ELSE StructLock(ParentStruct(e))

---------------------------------------------------------------------------
(* Scenario catalogue — each cfg picks its Txns subset.

     h2a, h2b : H2. Each inserts a FRESH key into BOTH maps. Their declared
                footprints are COMPUTED compatible by common/Footprint.tla
                (never hand-assigned — an early plan draft misdiagnosed H2
                exactly by hand-reasoning this), so Contract C lets them
                overlap; both then hold {LM1, LM2} via the new-key fallback,
                in opposite orders.
     h3a, h3b : H3. The classic write skew: r x / w y against r y / w x.
                With ACCURATE footprints these are incompatible (raw-id
                overlap) and Contract C serializes them. Put them in
                UnderDeclared and the guard evaporates.
     h5a, h5b : the H5 idiom — read the whole map, insert a new key. Post-fix
                the relation's third conjunct makes them incompatible, so
                Contract C serializes them and CommitSnapshotValid HOLDS.
                This is the H5 fix's independent, model-side confirmation.
     wwa, wwb : two under-declared transactions that both WRITE x. The
                contrast case for H3: the fallback IS caught here, by the
                commit locks plus the dirty check, and the loser refines its
                footprint from the actual log and re-runs. Exercises the
                dirty-resubmission path.
     pfa, pfb : H3 AS ORDINARY CODE ACTUALLY REACHES IT — the exact shape of
                StaticAnalysisFallbackSpec. Each writes a fresh scratch key,
                reads it back, and trips over a partial continuation; the
                walker throws AFTER recording the scratch write, so the
                declared footprint is the PARTIAL one
                (StaticAnalysisShortCircuitException), not the empty one. The
                skew (r x / w y against r y / w x) then happens behind it,
                entirely undeclared. These are NOT in UnderDeclared: their
                under-approximation comes from StaticFP, so this config checks
                the footprint the real code produces rather than the
                worst-case idealisation.
     ro       : a pure reader — no writes. Makes NoLocksWithoutWrites
                non-trivial (the TxnLogRetry vacuity pin).
   *)
---------------------------------------------------------------------------

AllTxns == {"h2a", "h2b", "dbl", "h3a", "h3b", "h5a", "h5b", "wwa", "wwb", "pfa", "pfb", "ddw", "ro"}
ASSUME Txns \subseteq AllTxns
ASSUME UnderDeclared \subseteq Txns

(*****************************************************************************)
(* SCENARIO CATALOGUE PRECONDITION — READ BEFORE ADDING A TRANSACTION.       *)
(*                                                                           *)
(* The DECLARED footprint (StaticFP, what the analyser computes) and the     *)
(* LOG footprint (ActualFP, what the transaction really touches) are         *)
(* separate operators, because their divergence IS H3. But ActualFP still    *)
(* does double duty: it is both the log's footprint (what a dirty restart    *)
(* refines to) and — via Writes(t) — the set of log entries that resolve a   *)
(* lock. In the CODE, three operations break that correspondence:            *)
(*                                                                           *)
(*   - setVarMap (whole-map WRITE) declares only the STRUCTURE id            *)
(*     (TxnCompilerContext: TxnSetVarMap -> addWriteId(txnVarMap.runtimeId)) *)
(*     but its LOG holds a structure update entry PLUS a per-key update      *)
(*     entry for every key — so the real transaction takes the map's lock    *)
(*     AND every existing entry's lock, where this model would give it the   *)
(*     map lock alone. A config using setVarMap would get a WRONG LOCK SET,  *)
(*     corrupting exactly the H2-family analysis this spec exists for.       *)
(*   - getVarMap (whole-map READ) on a NON-EMPTY map injects a read-only log *)
(*     entry per existing key, so the log's readIds strictly exceed the      *)
(*     static footprint's.                                                   *)
(*   - getVarMapValue on an ABSENT key creates NO log entry, while the       *)
(*     static analyser DOES record the key's existential read id.            *)
(*                                                                           *)
(* Every transaction below is therefore chosen so that the LOG footprint     *)
(* equals the lock-resolving write set. That holds because both maps start   *)
(* EMPTY (so a whole-map read has no entries to expand into, and no          *)
(* absent-key point read reaches the log — pfa/pfb read a key their own log  *)
(* already holds), and because nothing calls setVarMap.                      *)
(*                                                                           *)
(* ADDING a transaction that violates this — any setVarMap, any whole-map    *)
(* read of a non-empty map, any absent-key point read — requires splitting   *)
(* ActualFP into a log-footprint operator and a lock-set operator FIRST. Do  *)
(* not just add the row.                                                     *)
(*****************************************************************************)

(* What the transaction's LOG will really touch — and, per the precondition
   above, also what it locks. What it DECLARES is StaticFP, below. *)
ActualFP(t) ==
    CASE t = "h2a" -> FP({},   {M1a, M2a})
      [] t = "h2b" -> FP({},   {M1b, M2b})
      [] t = "h3a" -> FP({X},  {Y})
      [] t = "h3b" -> FP({Y},  {X})
      [] t = "h5a" -> FP({M1}, {M1a})
      [] t = "h5b" -> FP({M1}, {M1b})
      [] t = "wwa" -> FP({},   {X})
      [] t = "wwb" -> FP({},   {X})
      \* dbl inserts TWO fresh keys into ONE map. Both entries alias to that
      \* map's single structural commitLock, so withLock's `.distinct` must
      \* collapse them into ONE acquisition — a 1-permit Semaphore taken twice
      \* by the same fiber is an instant self-deadlock. Nothing else in the
      \* catalogue exercises the dedup, and the H2 fix changed how it dedupes.
      [] t = "dbl" -> FP({},   {M1c, M1d})
      \* pfa/pfb: the log holds the scratch-key insert (M1a/M1b) AND the skew
      \* pair the walker never reached. The scratch READ is not a separate log
      \* read — the write entry is already there, so `scratch.get(key)` is
      \* served from the log (and getValidated would drop it anyway).
      [] t = "pfa" -> FP({X},  {Y, M1a})
      [] t = "pfb" -> FP({Y},  {X, M1b})
      \* ddw really writes X, though it declared Y (see StaticFP).
      [] t = "ddw" -> FP({},   {X})
      [] t = "ro"  -> FP({X},  {})

(* What the STATIC ANALYSER declares — which is NOT always the actual access
   set. TxnRuntime.commit's handleErrorWith has TWO under-approximating
   branches, and they are not interchangeable:

     - `case _ => IdFootprint.empty` — the EMPTY footprint. Fires when an
       error escapes the walker's own handlers. Compatible with everything, so
       the scheduler's entire defence is switched off. Modelled by the
       UnderDeclared constant: the extreme point of the class.
     - `case StaticAnalysisShortCircuitException(idFootprint) => idFootprint`
       — a PARTIAL footprint: whatever the walker had accumulated when it
       threw. This is the branch ORDINARY CODE actually reaches (see pfa/pfb
       and StaticAnalysisFallbackSpec), and modelling only the empty branch
       would mean the model never checks the footprint the real defect
       produces.
   Both are under-approximations, and an under-approximation is unsound
   whatever its size. That is H3's premise. *)
StaticFP(t) ==
    \* pfa/pfb: the walker threw AFTER recording the scratch write, so it holds a
    \* PARTIAL footprint. Post-fix that partial footprint is FLAGGED (FPU) --
    \* TxnRuntime.commit calls .markUnderApproximated on it. Under-approximation
    \* is unsound whatever its size, so the flag, not the content, is what counts.
    CASE t = "pfa" -> FPU({}, {M1a})
      [] t = "pfb" -> FPU({}, {M1b})
      \* ddw DECLARES a write to Y but ACTUALLY writes X. Its static analysis
      \* COMPLETED -- nothing threw -- so it is NOT flagged and the scheduler
      \* trusts it. This is the data-dependent-footprint divergence recorded in
      \* plan section 10: a key or target computed from a value read BEFORE the
      \* transaction was submitted, which then changed. Post-H3-fix it is the ONLY
      \* remaining way declared can differ from actual, and therefore the only way
      \* a transaction can still go DIRTY -- see CommitDirty.cfg.
      [] t = "ddw" -> FP({}, {Y})
      [] OTHER     -> ActualFP(t)

\* The H3 fix: an under-approximated footprint is FLAGGED, not merely empty, and
\* the relation makes it incompatible with everything (common/Footprint.tla).
DeclaredInit(t) == IF t \in UnderDeclared THEN FPU({}, {}) ELSE StaticFP(t)

Writes(t)   == ActualFP(t).updates
Accessed(t) == CombinedIds(ActualFP(t))

(* The read set CommitSnapshotValid ranges over: reads that are not also
   writes (those are covered by isDirty) and are not under a parent this
   transaction itself writes — exactly IdFootprint.getValidated. *)
ReadSet(t)  == Validated(ActualFP(t)).reads

TxnRank(t) ==
    CASE t = "h2a" -> 1 [] t = "h2b" -> 2 [] t = "h3a" -> 3 [] t = "h3b" -> 4
      [] t = "h5a" -> 5 [] t = "h5b" -> 6 [] t = "wwa" -> 7 [] t = "wwb" -> 8
      [] t = "pfa" -> 9 [] t = "pfb" -> 10 [] t = "ro" -> 11 [] t = "dbl" -> 12
      [] t = "ddw" -> 13

---------------------------------------------------------------------------
(* State *)
---------------------------------------------------------------------------

VARIABLES
    pc,           \* [Txns -> phase] position in the commit pipeline
    inc,          \* [Txns -> Nat] incarnation (dirty resubmissions)
    declared,     \* [Txns -> footprint] this incarnation's DECLARED footprint
    readPend,     \* [Txns -> SUBSET Entities] entities the log run has yet to touch
    snapVer,      \* [Txns -> [Entities -> Nat]] version captured at first access
    snapEx,       \* [Txns -> [Entities -> BOOLEAN]] key existence captured at first
                  \*   access — the log entry's `initial` being Some/None
    writeSeq,     \* [Txns -> Seq(Entities)] write entries in LOG-KEY sort order
    resIdx,       \* [Txns -> Nat] how many write entries have had their lock resolved
    lockSeq,      \* [Txns -> Seq(Locks)] resolved locks, deduped, in acquisition order
    lockIdx,      \* [Txns -> Nat] how many locks are acquired
    lockHolder,   \* [Locks -> Txns \cup {"none"}] commitLock holders
    version,      \* [Entities -> Nat] commit counter (values abstracted away)
    keyExists,    \* [MapEntries -> BOOLEAN] live key presence in the map
    \* --- ghosts ---
    readValid,    \* [Txns -> "na"/"ok"/"bad"] were this txn's reads still at their
                  \*   snapshot AT ITS PUBLISH? (CommitSnapshotValid)
    pubCount,     \* [Txns -> Nat] publishes (PublishedExactlyOnce)
    truncated     \* an incarnation bound bound — the verdict would be horizon-clipped

vars == <<pc, inc, declared, readPend, snapVer, snapEx, writeSeq, resIdx,
          lockSeq, lockIdx, lockHolder, version, keyExists,
          readValid, pubCount, truncated>>

(* The execute/commit window of Contract C: the code's window is
   [admitForExecution succeeded, registerCompletion fired], which spans the
   WHOLE of AnalysedTxn.commit — the log run included. So the read phase is
   inside the window, and that is what makes reads safe in accurate mode. *)
Phases     == {"start", "read", "resolve", "acquire", "validate",
               "publish", "release", "done"}
WindowPcs  == {"read", "resolve", "acquire", "validate", "publish", "release"}
InWindow(t) == pc[t] \in WindowPcs

---------------------------------------------------------------------------
(* Helpers *)
---------------------------------------------------------------------------

Range(s) == { s[i] : i \in 1..Len(s) }

(* THE H2 FIX. withLock now sorts by the id of THE ENTITY THAT OWNS THE LOCK,
   carried alongside the lock itself (TxnLogEntry.lock returns
   (TxnVarRuntimeId, Semaphore)). For a map entry the owner is the entry's own
   TxnVar if the key exists, and otherwise THE MAP — because that is whose lock
   the fallback actually takes.

   Since owner -> lock is injective, one ascending order over owner ids is a
   single global total order over locks, so no set of transactions respecting
   it can form a circular wait.

   BEFORE THE FIX this operator returned the LOG KEY: `e.val` (the existential
   id hashed from (mapId, key)) for an absent key. That ordered acquisitions by
   a hash of the KEY while the locks acquired belonged to the MAPS — two
   unrelated id spaces — and TLC found the resulting cycle. Reverting this one
   operator to the log key is negative control NC-1, and it must go RED. *)
LockOwnerVal(t, e) ==
    IF e \in MapEntries
    THEN IF snapEx[t][e] THEN EntryVarVal(e) ELSE ParentStruct(e).val
    ELSE e.val

(* The lock-contributing entries, ordered by their LOCK'S OWNER. Read-only
   entries return lock = None and are dropped by `.flatten`, so they cannot
   affect the acquisition order at all — omitting them is exact, not a
   reduction. Sorting on the owner is what makes this a total order over locks;
   the code reaches the same sequence via `.sortBy(_._1.value)` after resolving
   every lock. *)
SortedWrites(t) ==
    LET S == Writes(t)
        n == Cardinality(S)
    IN  CHOOSE sq \in [1..n -> S] :
            /\ \A i, j \in 1..n : i /= j => sq[i] /= sq[j]
            /\ \A i \in 1..(n-1) : LockOwnerVal(t, sq[i]) <= LockOwnerVal(t, sq[i+1])

(* isDirty, per entry, exactly as TxnLogContext has it:
     - TxnLogUpdateVarEntry / ...StructureEntry: live value /= initial
     - TxnLogUpdateVarMapEntry with initial = None (a NEW-KEY insert):
         oValue.isDefined — "does the key exist now", NOT "did the version
         move". An insert-then-delete since our snapshot therefore reads
         CLEAN, and the model shows that rather than hiding it.
     - read-only entries: pure(false). Never consulted — Writes(t) only. *)
EntryDirty(t, e) ==
    IF e \in MapEntries /\ ~snapEx[t][e]
    THEN keyExists[e]
    ELSE version[e] /= snapVer[t][e]

IsDirty(t) == \E e \in Writes(t) : EntryDirty(t, e)

(* A structure read observes the whole K -> V map (TxnVarMap.get), so any
   write to any child bumps the structure's version. *)
BumpedBy(t, e) ==
    \/ e \in Writes(t)
    \/ /\ e \in MapStructs
       /\ \E c \in Writes(t) : c \in MapEntries /\ c.par = e.val

ReadsStillValid(t) == \A e \in ReadSet(t) : version[e] = snapVer[t][e]

HeldBy(t) == { l \in Locks : lockHolder[l] = t }

---------------------------------------------------------------------------
(* Init — reduction A7: static analysis and AnalysedTxn construction folded
   in. Every transaction is ready to enter its window (concurrent commits).
   Both maps start EMPTY: this is the SerializabilityOracleSpec H5 probe's
   setup ("both txns observed the empty map"), and it is what makes every
   map write in the catalogue a NEW-KEY insert — i.e. what puts the lock
   fallback on the path. *)
---------------------------------------------------------------------------

Init ==
    /\ pc         = [t \in Txns |-> "start"]
    /\ inc        = [t \in Txns |-> 0]
    /\ declared   = [t \in Txns |-> Validated(DeclaredInit(t))]
    /\ readPend   = [t \in Txns |-> {}]
    /\ snapVer    = [t \in Txns |-> [e \in Entities |-> 0]]
    /\ snapEx     = [t \in Txns |-> [e \in Entities |-> FALSE]]
    /\ writeSeq   = [t \in Txns |-> << >>]
    /\ resIdx     = [t \in Txns |-> 0]
    /\ lockSeq    = [t \in Txns |-> << >>]
    /\ lockIdx    = [t \in Txns |-> 0]
    /\ lockHolder = [l \in Locks |-> "none"]
    /\ version    = [e \in Entities |-> 0]
    /\ keyExists  = [e \in MapEntries |-> FALSE]
    /\ readValid  = [t \in Txns |-> "na"]
    /\ pubCount   = [t \in Txns |-> 0]
    /\ truncated  = FALSE

---------------------------------------------------------------------------
(* CONTRACT C — the assumed scheduler guarantee, as an action guard.
   Spec B checks this; here we assume it. In ACCURATE mode declared = actual
   and the guard really excludes conflictors. In FALLBACK mode the declared
   footprints are empty, the guard evaporates, and TLC explores exactly the
   concurrency the real scheduler would permit. ONE guard, two behaviours —
   the mode lives entirely in the footprints, never in the guard. *)
---------------------------------------------------------------------------

TxnEnter(t) ==
    /\ pc[t] = "start"
    /\ \A u \in Txns \ {t} :
           InWindow(u) => IsCompatible(declared[t], declared[u])
    /\ pc'       = [pc       EXCEPT ![t] = "read"]
    /\ readPend' = [readPend EXCEPT ![t] = Accessed(t)]
    /\ UNCHANGED <<inc, declared, snapVer, snapEx, writeSeq, resIdx, lockSeq,
                   lockIdx, lockHolder, version, keyExists, readValid,
                   pubCount, truncated>>

---------------------------------------------------------------------------
(* Read phase — getTxnLogResult. One step per accessed entity (A1): the log
   run touches live state entity by entity, capturing each entry's `initial`.
   A write to an unlogged var ALSO captures an initial (setVar does
   `v <- txnVar.get` before building the update entry), so writes snapshot
   too.

   The ORDER is NONDETERMINISTIC — any not-yet-touched entity may go next —
   rather than a fixed order (A1a). The scenario catalogue records each
   transaction's access SET, not its program order, so a fixed order would
   model ONE program and could HIDE the interleavings of the others: that is
   a false-GREEN risk on the clean configs, which is the direction that
   matters, since the red verdicts are hand-validated against the code
   anyway. Quantifying over all orders instead makes every clean verdict
   hold for EVERY program with that access set. It over-approximates (it
   admits orders no single program takes), so a counterexample must be
   validated line-by-line against the code before acceptance — the standing
   discipline for every counterexample in this repo. *)
---------------------------------------------------------------------------

ReadOne(t, e) ==
    /\ pc[t] = "read"
    /\ e \in readPend[t]
    /\ snapVer'  = [snapVer  EXCEPT ![t][e] = version[e]]
    /\ snapEx'   = [snapEx   EXCEPT ![t][e] =
                        IF e \in MapEntries THEN keyExists[e] ELSE TRUE]
    /\ readPend' = [readPend EXCEPT ![t] = readPend[t] \ {e}]
    /\ UNCHANGED <<pc, inc, declared, writeSeq, resIdx, lockSeq, lockIdx,
                   lockHolder, version, keyExists, readValid, pubCount,
                   truncated>>

(* Log run complete -> enter withLock. The write entries are now sorted by
   their LOG KEY, which depends on the existence captured above. *)
ReadDone(t) ==
    /\ pc[t] = "read"
    /\ readPend[t] = {}
    /\ pc'       = [pc       EXCEPT ![t] = "resolve"]
    /\ writeSeq' = [writeSeq EXCEPT ![t] = SortedWrites(t)]
    /\ resIdx'   = [resIdx   EXCEPT ![t] = 0]
    /\ lockSeq'  = [lockSeq  EXCEPT ![t] = << >>]
    /\ lockIdx'  = [lockIdx  EXCEPT ![t] = 0]
    /\ UNCHANGED <<inc, declared, readPend, snapVer, snapEx, lockHolder,
                   version, keyExists, readValid, pubCount, truncated>>

---------------------------------------------------------------------------
(* withLock, part 1: RESOLVE. `traverse(_._2.lock)` is sequential in F and
   each map-entry resolution is a LIVE read of txnVarMap.getTxnVar(key), so
   this is one step per write entry (A2), in sort order, BEFORE any lock is
   taken. `.distinct` then keeps first occurrences — which is what lets two
   entries aliasing to one map lock acquire it ONCE instead of self-
   deadlocking on a 1-permit Semaphore. *)
---------------------------------------------------------------------------

ResolveOne(t) ==
    /\ pc[t] = "resolve"
    /\ resIdx[t] < Len(writeSeq[t])
    /\ LET e == writeSeq[t][resIdx[t] + 1]
           l == LockOfWrite(e, IF e \in MapEntries THEN keyExists[e] ELSE TRUE)
       IN  lockSeq' = [lockSeq EXCEPT ![t] =
                          IF l \in Range(lockSeq[t]) THEN lockSeq[t]
                                                     ELSE Append(lockSeq[t], l)]
    /\ resIdx' = [resIdx EXCEPT ![t] = resIdx[t] + 1]
    /\ UNCHANGED <<pc, inc, declared, readPend, snapVer, snapEx, writeSeq,
                   lockIdx, lockHolder, version, keyExists, readValid,
                   pubCount, truncated>>

ResolveDone(t) ==
    /\ pc[t] = "resolve"
    /\ resIdx[t] = Len(writeSeq[t])
    /\ pc' = [pc EXCEPT ![t] = "acquire"]
    /\ UNCHANGED <<inc, declared, readPend, snapVer, snapEx, writeSeq, resIdx,
                   lockSeq, lockIdx, lockHolder, version, keyExists, readValid,
                   pubCount, truncated>>

---------------------------------------------------------------------------
(* withLock, part 2: ACQUIRE. One blocking step per distinct lock, in
   resolved order (A3). A transaction sitting here whose next lock is held by
   another transaction is WAITING — that is the waits-for edge, and H2's
   cycle forms right here. *)
---------------------------------------------------------------------------

NextLock(t) ==
    IF pc[t] = "acquire" /\ lockIdx[t] < Len(lockSeq[t])
    THEN lockSeq[t][lockIdx[t] + 1]
    ELSE NoLock

AcquireOne(t) ==
    /\ pc[t] = "acquire"
    /\ lockIdx[t] < Len(lockSeq[t])
    /\ LET l == lockSeq[t][lockIdx[t] + 1] IN
        /\ lockHolder[l] = "none"                  \* Semaphore(1).permit blocks
        /\ lockHolder' = [lockHolder EXCEPT ![l] = t]
    /\ lockIdx' = [lockIdx EXCEPT ![t] = lockIdx[t] + 1]
    /\ UNCHANGED <<pc, inc, declared, readPend, snapVer, snapEx, writeSeq,
                   resIdx, lockSeq, version, keyExists, readValid, pubCount,
                   truncated>>

AcquireDone(t) ==
    /\ pc[t] = "acquire"
    /\ lockIdx[t] = Len(lockSeq[t])
    /\ pc' = [pc EXCEPT ![t] = "validate"]
    /\ UNCHANGED <<inc, declared, readPend, snapVer, snapEx, writeSeq, resIdx,
                   lockSeq, lockIdx, lockHolder, version, keyExists, readValid,
                   pubCount, truncated>>

---------------------------------------------------------------------------
(* Inside the lock region: ifM(log.isDirty)(None, log.commit.as(logValue)).
   The dirty check ranges over the WRITE SET ONLY (A4) — read-only entries
   hardcode isDirty = false. That is the hole H3 walks through. *)
---------------------------------------------------------------------------

(* CLEAN -> publish. readValid is stamped against the PRE-publish versions,
   so a transaction's own writes never invalidate its own reads (the code's
   `initial` for a structure entry is the pre-txn map, and log-local writes
   are not visible in txnVarMap.get). *)
Publish(t) ==
    /\ pc[t] = "validate"
    /\ ~IsDirty(t)
    /\ readValid' = [readValid EXCEPT ![t] =
                        IF ReadsStillValid(t) THEN "ok" ELSE "bad"]
    /\ version'   = [e \in Entities |->
                        IF BumpedBy(t, e) THEN version[e] + 1 ELSE version[e]]
    /\ keyExists' = [e \in MapEntries |->
                        IF e \in Writes(t) THEN TRUE ELSE keyExists[e]]
    /\ pubCount'  = [pubCount EXCEPT ![t] = pubCount[t] + 1]
    /\ pc'        = [pc EXCEPT ![t] = "release"]
    /\ UNCHANGED <<inc, declared, readPend, snapVer, snapEx, writeSeq, resIdx,
                   lockSeq, lockIdx, lockHolder, truncated>>

(* DIRTY -> TxnResultLogDirty: release the locks, refine the declared
   footprint from the ACTUAL log (freshIncarnation(refinement.getValidated)),
   and re-run via submitTxnForImmediateRetry. This is where an under-declared
   transaction self-corrects — but only ever on its WRITE set, because that
   is all isDirty looks at. *)
DirtyRestart(t) ==
    /\ pc[t] = "validate"
    /\ IsDirty(t)
    /\ inc[t] < MaxInc
    /\ lockHolder' = [l \in Locks |->
                        IF lockHolder[l] = t THEN "none" ELSE lockHolder[l]]
    /\ inc'        = [inc      EXCEPT ![t] = inc[t] + 1]
    \* Refined from the ACTUAL log, which is complete by construction (it is built
    \* from real log entries), so the refinement is NOT under-approximated. The
    \* transaction therefore pays the serialization cost once and then runs at
    \* full concurrency.
    /\ declared'   = [declared EXCEPT ![t] = Validated(ActualFP(t))]
    /\ pc'         = [pc       EXCEPT ![t] = "start"]
    /\ lockSeq'    = [lockSeq  EXCEPT ![t] = << >>]
    /\ lockIdx'    = [lockIdx  EXCEPT ![t] = 0]
    /\ writeSeq'   = [writeSeq EXCEPT ![t] = << >>]
    /\ resIdx'     = [resIdx   EXCEPT ![t] = 0]
    /\ snapVer'    = [snapVer  EXCEPT ![t] = [e \in Entities |-> 0]]
    /\ snapEx'     = [snapEx   EXCEPT ![t] = [e \in Entities |-> FALSE]]
    /\ UNCHANGED <<readPend, version, keyExists, readValid, pubCount, truncated>>

(* At the incarnation bound the fiber retires. The ghost makes the truncation
   VISIBLE — a clipped exploration horizon must never masquerade as a clean
   verdict (BoundsNeverBind). *)
DirtyTruncate(t) ==
    /\ pc[t] = "validate"
    /\ IsDirty(t)
    /\ inc[t] = MaxInc
    /\ lockHolder' = [l \in Locks |->
                        IF lockHolder[l] = t THEN "none" ELSE lockHolder[l]]
    /\ lockSeq'   = [lockSeq EXCEPT ![t] = << >>]
    /\ lockIdx'   = [lockIdx EXCEPT ![t] = 0]
    /\ pc'        = [pc EXCEPT ![t] = "done"]
    /\ truncated' = TRUE
    /\ UNCHANGED <<inc, declared, readPend, snapVer, snapEx, writeSeq, resIdx,
                   version, keyExists, readValid, pubCount>>

(* Resource release, reverse order — collapsed to one step (A6): releasing
   only frees locks and the publish has already happened. The lock list is
   cleared with them: the withLock Resource scope has exited, so there is no
   longer a held prefix (LocksHeldConsistent pins that correspondence). *)
Release(t) ==
    /\ pc[t] = "release"
    /\ lockHolder' = [l \in Locks |->
                        IF lockHolder[l] = t THEN "none" ELSE lockHolder[l]]
    /\ lockSeq' = [lockSeq EXCEPT ![t] = << >>]
    /\ lockIdx' = [lockIdx EXCEPT ![t] = 0]
    /\ pc' = [pc EXCEPT ![t] = "done"]
    /\ UNCHANGED <<inc, declared, readPend, snapVer, snapEx, writeSeq, resIdx,
                   version, keyExists, readValid, pubCount, truncated>>

---------------------------------------------------------------------------
(* Next / Spec *)
---------------------------------------------------------------------------

(* Legitimate terminal states stutter, so that TLC's deadlock detection stays
   ENABLED and any reported deadlock is a REAL protocol deadlock (the house
   rule from Spec B). An H2 lock cycle is NOT covered by this: its
   transactions are not done. *)
Terminating ==
    /\ \A t \in Txns : pc[t] = "done"
    /\ UNCHANGED vars

Next ==
    \/ \E t \in Txns :
        \/ TxnEnter(t)
        \/ ReadDone(t)
        \/ ResolveOne(t)  \/ ResolveDone(t)
        \/ AcquireOne(t)  \/ AcquireDone(t)
        \/ Publish(t)     \/ DirtyRestart(t) \/ DirtyTruncate(t)
        \/ Release(t)
    \/ \E t \in Txns, e \in Entities : ReadOne(t, e)
    \/ Terminating

Spec == Init /\ [][Next]_vars

---------------------------------------------------------------------------
(* State constraint (finiteness backstop) *)
---------------------------------------------------------------------------

StateConstraint ==
    \A e \in Entities : version[e] <= 4

---------------------------------------------------------------------------
(* Invariants (plan §4 property table) *)
---------------------------------------------------------------------------

TypeOK ==
    /\ pc \in [Txns -> Phases]
    /\ lockHolder \in [Locks -> Txns \cup {"none"}]
    /\ readValid \in [Txns -> {"na", "ok", "bad"}]
    /\ \A t \in Txns : inc[t] >= 0 /\ inc[t] <= MaxInc

(* --- H2: NoWaitsForCycle ------------------------------------------------
   The waits-for graph over lock holders and waiters. NOT TLC's built-in
   deadlock detection: that also trips on legitimate terminals, and it names
   the symptom rather than the defect. This names the defect and fires at the
   exact state the cycle closes. *)

WaitsFor ==
    { <<t, u>> \in Txns \X Txns :
        /\ t /= u
        /\ NextLock(t) /= NoLock
        /\ lockHolder[NextLock(t)] = u }

RECURSIVE TCRec(_, _)
TCRec(R, n) ==
    IF n <= 0 THEN R
    ELSE LET R2 == R \cup { <<a, c>> \in Txns \X Txns :
                               \E b \in Txns : <<a, b>> \in R /\ <<b, c>> \in R }
         IN  IF R2 = R THEN R ELSE TCRec(R2, n - 1)

NoWaitsForCycle ==
    LET TC == TCRec(WaitsFor, Cardinality(Txns))
    IN  \A t \in Txns : <<t, t>> \notin TC

(* --- H3 / H5: CommitSnapshotValid ---------------------------------------
   At its publish, every entity a transaction READ was still at the version
   it read. This is the strong (snapshot) form of strict serializability:
   sufficient, not necessary. If it fails in ACCURATE mode by some mechanism
   other than a known one, fall back to an explicit history-based
   serializability check before declaring an anomaly (plan §4).

   FALLBACK mode: expected RED (H3 — reads are never commit-validated and
   there are no read locks, so r x/w y against r y/w x both publish).
   ACCURATE mode: expected to HOLD, INCLUDING for the H5 idiom (whole-map
   read + new-key insert) — the relation's third conjunct makes that pair
   incompatible and Contract C serializes it. That is the H5 fix, confirmed
   from the model side. *)
CommitSnapshotValid == \A t \in Txns : readValid[t] /= "bad"

(* A transaction's writes are published at most once per run. *)
PublishedExactlyOnce == \A t \in Txns : pubCount[t] <= 1

(* --- Fidelity pin (plan §4) ---------------------------------------------
   Only UPDATE entries contribute a lock (TxnLogEntry.lock = None on every
   read-only entry), and only update entries can be dirty. A pure reader
   therefore acquires NO locks and can NEVER report dirty — which is exactly
   why the TxnLogRetry re-validation before a park is vacuous, and why the H1
   fix had to introduce hasChangedSinceRead (a read-inclusive comparison)
   instead of leaning on isDirty. Pinned here so the model can never quietly
   "improve" the protocol by validating reads. *)
NoLocksWithoutWrites ==
    \A t \in Txns : Writes(t) = {} => lockSeq[t] = << >>

(* Bookkeeping sanity: a lock is held by t iff t has acquired it and not yet
   released. Catches model bugs, not protocol bugs. *)
LocksHeldConsistent ==
    \A t \in Txns :
        HeldBy(t) = { lockSeq[t][i] : i \in 1..lockIdx[t] }

(* FIDELITY PIN for the H2 fix's sort key.
   The CODE resolves a map entry's lock owner from a LIVE read at withLock time
   (TxnLogUpdateVarMapEntry.lock does txnVarMap.getTxnVar(key)). This MODEL
   computes the sort key from snapEx — the key's existence when the LOG ENTRY
   was built. Those agree only if nothing can flip the existence of an entity we
   WRITE while we are inside our window, and Contract C is why: anyone who could
   insert or delete that key must write it, which is raw-id overlap with our own
   write, hence incompatible, hence excluded from our window.

   That argument is sound but it is an argument, so it is machine-checked here
   rather than assumed. If a future scenario breaks it — a fallback config that
   lets two transactions write one map entry, say — this goes RED and says so,
   instead of the model silently sorting on a key the code never uses.

   Scoped to the RESOLVE and ACQUIRE phases, which is exactly where the owner id
   is consulted. Beyond them the check would be vacuously self-violating: a
   transaction's OWN publish flips keyExists for its own writes while it is
   still in its window (at "release"), and that is not interference. *)
LockOwnerStable ==
    \A t \in Txns :
        pc[t] \in {"resolve", "acquire"} =>
            \A e \in Writes(t) :
                e \in MapEntries => (snapEx[t][e] = keyExists[e])

(* Contract C is ASSUMED here (TxnEnter's guard) and CHECKED by Spec B.
   Asserting it costs nothing and pins the assumption in place — if a later
   edit ever weakens the guard, this goes red instead of silently widening
   the model's concurrency. *)
ContractCHolds ==
    \A t, u \in Txns :
        (t /= u /\ InWindow(t) /\ InWindow(u))
            => IsCompatible(declared[t], declared[u])

(* The exploration horizon never bound: no verdict here is clipped. *)
BoundsNeverBind == ~truncated

===============================================================================
