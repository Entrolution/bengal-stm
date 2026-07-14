------------------------------ MODULE Scheduler ------------------------------
(*
 * Spec B: the bengal-stm transaction scheduler protocol.
 * Scala correspondence: TxnRuntimeContext.scala. Actions cite method names
 * (submitTxn, subscribeDownstreamDependency, ...) rather than line numbers —
 * line references rot on every comment edit and verify_anchors.sh cannot
 * guard them; symbol names it can grep.
 *
 * SCOPE: submission and graph building, dependency tallies, execution
 * statuses, unsubscribe cascades, the dirty-resubmission path
 * (submitTxnForImmediateRetry), the error recovery branches, and the retry
 * map / park / wake machinery (TxnResultRetry — where H1 lived). The COMMIT
 * itself is not modelled here: Spec B abstracts it to an atomic version bump,
 * and specs/commit/CommitProtocol.tla is what discharges that abstraction.
 *
 * GRANULARITY: one model step = one operation on one shared mutable object
 * (Ref get/set/update, TrieMap read/insert/remove).
 * Semaphore-guarded regions are NOT atomic — steps of detached fibers
 * (unsubscribe cascades) interleave with in-region steps. Deliberate
 * reductions, each argued in specs/README.md:
 *   R1. The per-target subscribe fibers inside a submit scan (parTraverse of
 *       .start'ed fibers, joined before region exit) are modelled as a
 *       sequential loop in a fixed target order. Their steps touch disjoint
 *       cells apart from monotone counter increments, so inter-fiber
 *       orderings commute; interleavings with EXTERNAL steps are preserved
 *       between every pair of loop steps.
 *   R2. A subscribe's three writes (tally increment, unsubSpecs insert,
 *       hasDownstream set) collapse to one step. External racers read only
 *       the tally cell, and single-op interleavings before/after the triple
 *       are preserved; the code's contains-check stays a SEPARATE step,
 *       which is where the H4 staleness lives.
 *   R3. Semaphore acquire/act/release collapses to one guarded step for
 *       single-operation regions (admitForExecution, registerCompletion's
 *       remove).
 *   R4. Static analysis, AnalysedTxn construction, and Deferred creation
 *       collapse into Init.
 *   R5. One submit workflow per txn, which serializes the inline `imm`
 *       submissions two dirty fibers could in principle run at once. Argued
 *       in full at ExecResubInit, because the argument is that the H4 fix
 *       makes the concurrency it hides NON-EXISTENT — so R5 is vacuous, and
 *       reading it anywhere but next to the admission gate invites the wrong
 *       conclusion.
 *   R6. A cascade spawns with its edge set preloaded: the cascadeFired
 *       gate serializes cascades per incarnation and the drained map is
 *       frozen by spawn time, so the code's gate/nonEmpty/values steps
 *       collapse soundly (pre-fix these were kept separate because
 *       double-spawned cascades could race a clear()).
 *
 * TWO REDUCTIONS ARE DELIBERATELY *NOT* TAKEN in the retry machinery, and
 * both were tried and rejected during the H1 work:
 *   - Sweeps are NOT skipped when the retry map is empty at spawn time. A
 *     sweep reads the map when it ACQUIRES the retry semaphore, so one
 *     spawned while a transaction is mid-park blocks and then rescues it.
 *     Skipping empty-map sweeps deletes that path and makes the model
 *     unsound (it would report a fixed protocol as deadlocking).
 *   - The park region is NOT collapsed into one atomic step. The retry
 *     semaphore serializes it against sweeps but NOT against commits or
 *     completions, so a conflictor can move between its two checks — which
 *     is exactly where the ordering defect lives (see ExecParkScan).
 *
 * THE H4 FIX IS MODELLED (matching the code): admitForExecution admits at
 * most one fiber per incarnation into the commit window (status CAS +
 * tally==0 under the graph semaphore — ExecAdmit); every resubmission is a
 * freshIncarnation with new tally/unsubs/status/cascade refs (edges are
 * incarnation-tagged, and drains/clears against re-incarnated targets
 * no-op, modelling decrements of dead refs); triggerUnsub fires exactly
 * once per incarnation (cascadeFired).
 *
 * Commit outcomes are TRUTHFUL rather than nondeterministic: a transaction
 * retries iff its predicate fails against this fiber's snapshot (refining
 * instead of parking when its coverage gate fails); otherwise it publishes
 * iff its declared footprint covers its LOGGED one (the code's coverage
 * gate — the commit-time dirty check is gone), and refines otherwise, with
 * the per-arm under-flag asymmetry mirrored from the code at ExecCommit. A
 * nondeterministic
 * failure can strike the commit (before publish — a mid-publish throw is
 * inside the atomic-commit abstraction and out of scope here) or the dispatch
 * of a dirty resubmission, modelling `execute`'s handleErrorWith: it runs
 * registerCompletion a SECOND time — idempotent on activeTransactions — and
 * re-enters triggerUnsub, where PRE-FIX it spawned a second cascade over the
 * same shared unsubSpecs, racing the first one's clear(). The cascadeFired
 * gate now closes that (ExecErrSpawnUnsub), so the second call finds the gate
 * shut and does nothing. A plain submission can also abort, modelling the
 * submit wrapper's handleErrorWith in TxnRuntime.commit, which completes the
 * signal and leaves the transaction wherever it was.
 *
 * NOT MODELLED: ABANDONMENT (caller-cancelled commit). TxnScheduler.abandon
 * removes a parked entry under the retry semaphore, demotes a Scheduled
 * incarnation to NotScheduled under the graph semaphore (the ONE status
 * demotion in the code) and drains its cascade, and an abandoned flag —
 * re-checked inside the same semaphore regions the protocol already holds —
 * stops every re-entry (park, resubmit, admission). The omission is sound
 * for the properties checked here because abandonment only REMOVES
 * behaviour: it cannot create an execute-window overlap (ContractC), and a
 * lost wakeup for an ABANDONED transaction is the intended outcome, not a
 * defect — NoLostWakeup speaks for callers still waiting. Behavioural and
 * protocol-level pins live in spec/CancellationSpec and
 * runtime/AbandonProtocolSpec.
 *)

EXTENDS Footprint, Integers, FiniteSets, Sequences

CONSTANTS
    Txns,            \* the scenario's transaction set (see the catalogue below)
    MaxInc,          \* incarnation bound per txn — consumed by every
                     \* resubmission road (refinement, retry-with-downstream,
                     \* sweep wakes); action-level truncation at
                     \* ExecResubTruncate and SweepWake's truncatedWake
    MaxVer,          \* version bound per var (CONSTRAINT backstop)
    AbortsEnabled    \* enable the nondeterministic failure injections

---------------------------------------------------------------------------
(* Scenario catalogue — each cfg picks its Txns subset. Runtime ids are
   arbitrary distinct naturals; allocator-issued ids in the real system follow
   creation/first-touch order, so every ordering class is reachable by some
   program (TxnStateEntity.runtimeId).

     t1, t2, t3 : the H4 scenario (t1 under-declared, t2 conflicting
                  writer, t3 reader-writer bystander) — safety configs
     tr         : a waitFor transaction — reads V1, retries until
                  version[V1] >= 1, then writes V2 (retry/park configs)
     tw         : the writer whose commit satisfies tr's predicate
     tm         : tr, with its read UNLOGGED — a waitFor on an ABSENT map
                  key. Identical to tr in every other respect. See LoggedFP.
   *)
---------------------------------------------------------------------------

AllTxns == {"t1", "t2", "t3", "tr", "tw", "tm"}
ASSUME Txns \subseteq AllTxns

V1 == [val |-> 1, par |-> NoParent]
V2 == [val |-> 2, par |-> NoParent]
VarIds == {V1, V2}

(* Actual access sets: what the transaction TOUCHES. *)
ActualFP(t) ==
    CASE t = "t1" -> FP({}, {V1})
      [] t = "t2" -> FP({}, {V1})
      [] t = "t3" -> FP({V1}, {V2})
      [] t = "tr" -> FP({V1}, {V2})
      [] t = "tw" -> FP({}, {V1})
      [] t = "tm" -> FP({V1}, {V2})

(* What the transaction's LOG RECORDS — which is NOT the same thing, and the
   model conflated the two until now.

   Reading a map key that is ABSENT records no log entry at all: the
   key-absent branch of TxnLogContext.getVarMapValue returns the log
   UNCHANGED. The read is still in the DECLARED footprint — the static
   analysis walker registers the key's existential id unconditionally — so
   Contract C still keeps conflicting writers out of the execute window, and
   checkRetryQueue's sweep still matches on it. But every fold over the LOG
   is blind to it, and there are two:

     TxnLogValid.anyReadChangedSinceRead  — the H1 park-time staleness check
     TxnLogValid.idFootprint              — the dirty path's refinement source

   Modelling the staleness check over ActualFP therefore checked a STRONGER
   guard than the code implements, which is why this spec did not catch it.

   tm differs from tr in exactly one respect: its read of V1 is not logged.
   Everything else — declared footprint, retry predicate, write set — is
   identical, so anything TLC finds here is caused by the missing log entry
   and by nothing else. *)
LoggedFP(t) ==
    CASE t = "tm" -> FP({}, {V2})
      [] OTHER    -> ActualFP(t)

(* Declared base footprints. t1's declared footprint is EMPTY while it really
   writes V1, so t1 and t2 are judged compatible and run concurrently; t1's
   footprint then fails the coverage gate at commit and it refines and
   resubmits — which is what opens the H4 window. tr/tw declare accurately.

   WHAT t1 MODELS CHANGED WITH THE H3 FIX (2026-07-11), and the distinction
   matters. It used to model the static-analysis FALLBACK: the walker threw and
   TxnRuntime.commit scheduled on whatever partial footprint it had. That path
   is now FLAGGED (IdFootprint.isUnderApproximated) and the relation makes it
   incompatible with everything, so a transaction that took it is serialized and
   can never run concurrently with t2 at all.

   t1 therefore now models the OTHER way declared can differ from actual: a
   DATA-DEPENDENT footprint. The walker completed — nothing threw, so nothing is
   flagged and the scheduler trusts the result — but the access set was computed
   from values read BEFORE submission, and they changed underneath it. Post-H3-fix
   this DATA-DEPENDENT divergence is the ONLY remaining source of declared/actual
   disagreement: the only thing that can still drive a transaction down the
   dirty-resubmission path, and the only thing left that can still under-declare a
   READ. Spec A hunts its read-side consequence (H6) and fixes it with the
   commit-time coverage check; see specs/commit/CommitH6.cfg and
   specs/commit/CommitDirty.cfg, which had to be rebuilt on the same realisation.

   The H4 machinery this config verifies is therefore still live and still
   needs to be correct — but it is now reached by a different road, and this
   comment exists so nobody reads the old one and concludes the scenario is
   dead. *)
DeclaredBase(t) ==
    CASE t = "t1" -> EmptyFootprint
      [] t = "t2" -> FP({}, {V1})
      [] t = "t3" -> FP({V1}, {V2})
      [] t = "tr" -> FP({V1}, {V2})
      [] t = "tw" -> FP({}, {V1})
      \* tm DECLARES the absent key's read accurately — the walker registers
      \* its existential id whether or not the key exists. Only the log misses it.
      [] t = "tm" -> FP({V1}, {V2})

(* Retry transactions and their predicates over versions. A non-retry
   transaction's predicate is vacuously TRUE. The predicate is evaluated
   against the fiber's SNAPSHOT (the log's read values), exactly as the
   code's log run decides TxnLogRetry — a write committed after the
   snapshot does not rescue a stale decision (that gap is H1's home). *)
IsRetryTxn(t) == t \in {"tr", "tm"}
RetryPred(t, versionsFn) ==
    IF t \in {"tr", "tm"} THEN versionsFn[V1] >= 1 ELSE TRUE

\* Writes always record a log entry, so ActualFP and LoggedFP agree on updates.
Writes(t) == ActualFP(t).updates
TxnRank(t) ==
    CASE t = "t1" -> 1 [] t = "t2" -> 2 [] t = "t3" -> 3
      [] t = "tr" -> 4 [] t = "tw" -> 5 [] t = "tm" -> 6

---------------------------------------------------------------------------
(* State *)
---------------------------------------------------------------------------

VARIABLES
    \* --- per-txn protocol state (the AnalysedTxn refs). PRE-H4-FIX these were
    \*     SHARED across incarnations; the fix gives every incarnation FRESH
    \*     tally/unsubs/status/cascade refs, so old cascades drain DEAD refs —
    \*     modelled by incarnation-tagged edges whose drains no-op on a
    \*     mismatch. completionSignal is the one that stays shared, and must:
    \*     it has to complete the ORIGINAL caller. ---
    status,          \* executionStatus Ref — fresh per incarnation
    tally,           \* dependencyTally Ref (fresh per incarnation)
    unsubs,          \* unsubSpecs TrieMap: t |-> set of <<downstreamId, itsIncAtSubscribe>>
                     \* edges (fresh map per incarnation)
    hasDown,         \* hasDownstream Ref (fresh per incarnation)
    completedCount,  \* completionSignal completions observed (Deferred — SHARED
                     \* across incarnations; it must complete the original caller)
    inc,             \* incarnation counter (0 = first submission)
    declared,        \* current incarnation's declared (validated) footprint
    publishedInc,    \* ghost: this incarnation already published
    doublePub,       \* ghost: a publish happened twice in one incarnation
    cascadeFired,    \* triggerUnsub's exactly-once gate (fresh per incarnation)
    droppedSpawn,    \* ghost: a spawn found no free slot (masking detector —
                     \* if TRUE the two-slot bound truncated a real behaviour)
    snap,            \* PER-FIBER version snapshot taken at execute start
                     \* (each execute run builds a private log in the code,
                     \* so concurrent fibers of one txn snapshot independently)
    \* --- submit workflow (one per txn; resubmission reuses it) ---
    submitPc,        \* "idle","reset1","reset2","acq","setSched","insAct",
                     \* "scanPick","scanCheck","subCheck","subApply","ready","rel"
    submitMode,      \* "plain" (submitTxn) | "imm" (submitTxnForImmediateRetry)
    scanPending,     \* targets not yet scanned
    curTarget,       \* target being scanned ("none" between targets)
    curDir,          \* subscribe direction: "down" (target -> me) | "up" (me -> target)
    curContained,    \* result of the unsubSpecs contains-check
    \* --- execute fibers: two slots per txn (double-spawn is representable
    \*     because detecting it is the point) ---
    exec,            \* [Txns \X {"A","B"} -> [pc |-> ..., out |-> ...]]
    \* --- triggerUnsub cascades: two slots per owner (error path spawns two) ---
    unsubW,          \* [Txns \X {"u1","u2"} -> [ph |-> ..., pend |-> SUBSET Txns]]
    \* --- scheduler-wide ---
    active,          \* activeTransactions keys
    graphSem,        \* graphBuilderSemaphore holder: txn id or "none"
    version,         \* var id -> commit counter (values abstracted away)
    \* --- retry machinery (park / wake — H1 territory) ---
    parked,          \* retryMap membership: txns parked awaiting a wake
                     \* (keyed by footprint in code; compat is computed from
                     \* declared[t], which is stable while parked)
    retrySem,        \* retrySemaphore holder: [kind |-> "park"/"sweep",
                     \* t |-> txn] or "none"
    sweep,           \* checkRetryQueue fibers, two slots per submitting txn:
                     \* [ph |-> "none"/"acq"/"scan", pend |-> SUBSET Txns,
                     \*  fp |-> the submitted footprint]. Spawned on EVERY
                     \* submission — a sweep evaluates the retry map only
                     \* when it ACQUIRES the semaphore, so one spawned while
                     \* a parker holds it blocks, then finds the parker and
                     \* wakes it. That rescue path is load-bearing (see the
                     \* park region), so sweeps must not be optimised away.
    droppedSweep,    \* ghost: a sweep spawn found its slot busy
    truncatedWake,   \* ghost: a wake was dropped at the MaxInc bound
    parkChk          \* per-fiber park-region check results: [cf, st]

txnVars    == <<status, tally, unsubs, hasDown, completedCount, inc, declared,
                publishedInc, doublePub, cascadeFired, droppedSpawn, snap>>
submitVars == <<submitPc, submitMode, scanPending, curTarget, curDir, curContained>>
schedVars  == <<active, graphSem, version>>
retryVars  == <<parked, retrySem, sweep, droppedSweep, truncatedWake, parkChk>>
vars       == <<txnVars, submitVars, exec, unsubW, schedVars, retryVars>>

ExecSlots  == Txns \X {"A", "B"}
UnsubSlots == Txns \X {"u1", "u2"}

SweepSlots == Txns \X {"s1", "s2", "s3"}  \* one per possible submission (MaxInc + 1)
NoSweep    == [ph |-> "none", pend |-> {}, fp |-> EmptyFootprint]

NoExec  == [pc |-> "none", out |-> "none", fInc |-> 0]
NoUnsub == [ph |-> "none", pend |-> {}, ownerInc |-> 0]
NoHolder == [kind |-> "idle", t |-> "none"]

(* Exec pcs that constitute the execute/commit window. "regRun" (parked at
   the admission gate) is deliberately NOT in the window: multiple fibers
   parked at admission are legal and harmless — at most one wins. *)
ExecWindowPcs == {"snap", "commit", "regComp", "spawnU"}

---------------------------------------------------------------------------
(* Helpers *)
---------------------------------------------------------------------------

(* execute(scheduler).start — from checkExecutionReadiness or
   unsubscribeUpstreamDependency's zero-test. Spawned fibers carry the
   incarnation they were spawned for (fInc): in the code a fiber closes
   over one AnalysedTxn incarnation and gates against ITS status ref, so a
   stale fiber can never admit against a newer incarnation. If both slots
   are busy a third spawn is dropped; every spawn site records the drop in
   the droppedSpawn ghost so the NoDroppedSpawn invariant surfaces the
   truncation instead of letting it silently mask deeper behaviours. *)
ExecSlotsFull(ex, t) ==
    ex[<<t, "A">>].pc /= "none" /\ ex[<<t, "B">>].pc /= "none"

SpawnExec(ex, t, i) ==
    IF ex[<<t, "A">>].pc = "none"
        THEN [ex EXCEPT ![<<t, "A">>] = [pc |-> "regRun", out |-> "none", fInc |-> i]]
    ELSE IF ex[<<t, "B">>].pc = "none"
        THEN [ex EXCEPT ![<<t, "B">>] = [pc |-> "regRun", out |-> "none", fInc |-> i]]
    ELSE ex

UnsubSlotsFull(uw, t) ==
    uw[<<t, "u1">>].ph /= "none" /\ uw[<<t, "u2">>].ph /= "none"

(* triggerUnsub.start, post-fix: the getAndSet(cascadeFired) gate admits
   exactly one cascade per incarnation, and the drained map is FROZEN by
   then (the owner left activeTransactions before the spawn, so no new
   edges can arrive). Reduction R6: gate + nonEmpty check + values
   snapshot collapse into spawn-with-preloaded-edges — sound because the
   map is frozen and the gate serializes cascades. *)
SpawnUnsub(uw, t, edges, ownInc) ==
    IF edges = {}
        THEN uw
    ELSE IF uw[<<t, "u1">>].ph = "none"
        THEN [uw EXCEPT ![<<t, "u1">>] = [ph |-> "drain", pend |-> edges, ownerInc |-> ownInc]]
    ELSE IF uw[<<t, "u2">>].ph = "none"
        THEN [uw EXCEPT ![<<t, "u2">>] = [ph |-> "drain", pend |-> edges, ownerInc |-> ownInc]]
    ELSE uw

(* Fixed scan order (reduction R1) *)
PickTarget(S) == CHOOSE d \in S : \A e \in S : TxnRank(d) <= TxnRank(e)

---------------------------------------------------------------------------
(* Init — reduction R4: static analysis + AnalysedTxn construction folded
   in; every txn's submitTxn fiber is ready to run (concurrent commits). *)
---------------------------------------------------------------------------

Init ==
    /\ status         = [t \in Txns |-> "NotScheduled"]
    /\ tally          = [t \in Txns |-> 0]
    /\ unsubs         = [t \in Txns |-> {}]
    /\ cascadeFired   = [t \in Txns |-> FALSE]
    /\ hasDown        = [t \in Txns |-> FALSE]
    /\ completedCount = [t \in Txns |-> 0]
    /\ inc            = [t \in Txns |-> 0]
    /\ declared       = [t \in Txns |-> Validated(DeclaredBase(t))]
    /\ publishedInc   = [t \in Txns |-> FALSE]
    /\ doublePub      = [t \in Txns |-> FALSE]
    /\ droppedSpawn   = FALSE
    /\ snap           = [s \in ExecSlots |-> [v \in VarIds |-> 0]]
    /\ submitPc       = [t \in Txns |-> "reset1"]
    /\ submitMode     = [t \in Txns |-> "plain"]
    /\ scanPending    = [t \in Txns |-> {}]
    /\ curTarget      = [t \in Txns |-> "none"]
    /\ curDir         = [t \in Txns |-> "none"]
    /\ curContained   = [t \in Txns |-> FALSE]
    /\ exec           = [s \in ExecSlots |-> NoExec]
    /\ unsubW         = [s \in UnsubSlots |-> NoUnsub]
    /\ active         = {}
    /\ graphSem       = "none"
    /\ version        = [v \in VarIds |-> 0]
    /\ parked         = {}
    /\ retrySem       = NoHolder
    /\ sweep          = [sl \in SweepSlots |-> NoSweep]
    /\ droppedSweep   = FALSE
    /\ truncatedWake  = FALSE
    /\ parkChk        = [sl \in ExecSlots |-> [cf |-> FALSE, st |-> FALSE]]

---------------------------------------------------------------------------
(* Submit workflow — submitTxn and
   submitTxnForImmediateRetry. Code order is: reset tally,
   reset hasDownstream (both OUTSIDE the semaphore), acquire, start scan
   fibers over a snapshot of activeTransactions, set Scheduled, insert into
   activeTransactions, JOIN the scan fibers, readiness check, release. The
   scan loop here therefore runs AFTER setSched/insAct, exactly as the scan
   fibers do in the code. *)
---------------------------------------------------------------------------

(* resetDependencyTally: two Ref writes, outside the
   semaphore — they race with in-flight unsubscribe cascades, deliberately. *)
SubmitReset1(t) ==
    /\ submitPc[t] = "reset1"
    /\ tally'    = [tally EXCEPT ![t] = 0]
    /\ submitPc' = [submitPc EXCEPT ![t] = "reset2"]
    /\ UNCHANGED <<status, unsubs, hasDown, completedCount, inc, declared,
                   publishedInc, doublePub, cascadeFired, droppedSpawn, snap, submitMode,
                   scanPending, curTarget, curDir, curContained, exec,
                   unsubW, schedVars, retryVars>>

SubmitReset2(t) ==
    /\ submitPc[t] = "reset2"
    /\ hasDown'  = [hasDown EXCEPT ![t] = FALSE]
    /\ submitPc' = [submitPc EXCEPT ![t] = "acq"]
    /\ UNCHANGED <<status, tally, unsubs, completedCount, inc, declared,
                   publishedInc, doublePub, cascadeFired, droppedSpawn, snap, submitMode,
                   scanPending, curTarget, curDir, curContained, exec,
                   unsubW, schedVars, retryVars>>

(* graphBuilderSemaphore.permit acquire + activeTransactions.values snapshot
   (the snapshot is taken before this txn is inserted, so the
   scan never sees self). active cannot change while the semaphore is held. *)
SubmitAcquire(t) ==
    /\ submitPc[t] = "acq"
    /\ graphSem = "none"
    /\ graphSem'    = t
    /\ scanPending' = [scanPending EXCEPT ![t] = active]
    /\ submitPc'    = [submitPc EXCEPT ![t] = "setSched"]
    /\ UNCHANGED <<txnVars, submitMode, curTarget, curDir, curContained,
                   exec, unsubW, active, version, retryVars>>

(* executionStatus.set(Scheduled) *)
SubmitSetScheduled(t) ==
    /\ submitPc[t] = "setSched"
    /\ status'   = [status EXCEPT ![t] = "Scheduled"]
    /\ submitPc' = [submitPc EXCEPT ![t] = "insAct"]
    /\ UNCHANGED <<tally, unsubs, hasDown, completedCount, inc, declared,
                   publishedInc, doublePub, cascadeFired, droppedSpawn, snap, submitMode,
                   scanPending, curTarget, curDir, curContained, exec,
                   unsubW, schedVars, retryVars>>

(* activeTransactions.addOne *)
SubmitInsertActive(t) ==
    /\ submitPc[t] = "insAct"
    /\ active'   = active \cup {t}
    /\ submitPc' = [submitPc EXCEPT ![t] = "scanPick"]
    /\ UNCHANGED <<txnVars, submitMode, scanPending, curTarget, curDir,
                   curContained, exec, unsubW, graphSem, version, retryVars>>

(* Scan loop head; empty pending = all scan fibers joined (joinWithNever) *)
SubmitScanPick(t) ==
    /\ submitPc[t] = "scanPick"
    /\ IF scanPending[t] = {}
       THEN /\ submitPc'  = [submitPc EXCEPT ![t] = "ready"]
            /\ UNCHANGED <<scanPending, curTarget>>
       ELSE /\ curTarget'   = [curTarget EXCEPT ![t] = PickTarget(scanPending[t])]
            /\ scanPending' = [scanPending EXCEPT ![t] = @ \ {PickTarget(scanPending[t])}]
            /\ submitPc'    = [submitPc EXCEPT ![t] = "scanCheck"]
    /\ UNCHANGED <<txnVars, submitMode, curDir, curContained, exec, unsubW,
                   schedVars, retryVars>>

(* Per-target compatibility/status dispatch.
   plain (submitTxn): incompatible -> target.subscribeDownstreamDependency(me).
   imm (submitTxnForImmediateRetry): status Running -> as plain; status Scheduled ->
   me.subscribeDownstreamDependency(target) — the REVERSED edge; the status
   read is stable while the semaphore is held (admitForExecution also
   takes it), so reading and branching in one step is sound. *)
SubmitScanCheck(t) ==
    /\ submitPc[t] = "scanCheck"
    /\ LET a      == curTarget[t]
           compat == IsCompatible(declared[t], declared[a])
       IN IF submitMode[t] = "plain"
          THEN IF compat
               THEN /\ submitPc' = [submitPc EXCEPT ![t] = "scanPick"]
                    /\ UNCHANGED curDir
               ELSE /\ curDir'   = [curDir EXCEPT ![t] = "down"]
                    /\ submitPc' = [submitPc EXCEPT ![t] = "subCheck"]
          ELSE CASE status[a] = "Running" /\ ~compat ->
                        /\ curDir'   = [curDir EXCEPT ![t] = "down"]
                        /\ submitPc' = [submitPc EXCEPT ![t] = "subCheck"]
                 [] status[a] = "Scheduled" /\ ~compat ->
                        /\ curDir'   = [curDir EXCEPT ![t] = "up"]
                        /\ submitPc' = [submitPc EXCEPT ![t] = "subCheck"]
                 [] OTHER ->
                        /\ submitPc' = [submitPc EXCEPT ![t] = "scanPick"]
                        /\ UNCHANGED curDir
    /\ UNCHANGED <<txnVars, submitMode, scanPending, curTarget, curContained,
                   exec, unsubW, schedVars, retryVars>>

(* subscribeDownstreamDependency's contains-check — its OWN step:
   the gap between this read and the apply below is where H4's staleness
   lives. down: unsubs[target] ∋ me; up: unsubs[me] ∋ target. *)
SubmitSubCheck(t) ==
    /\ submitPc[t] = "subCheck"
    /\ LET a == curTarget[t]
       IN curContained' = [curContained EXCEPT ![t] =
                              IF curDir[t] = "down"
                              THEN \E i \in 0..MaxInc : <<t, i>> \in unsubs[a]
                              ELSE \E i \in 0..MaxInc : <<a, i>> \in unsubs[t]]
    /\ submitPc' = [submitPc EXCEPT ![t] = "subApply"]
    /\ UNCHANGED <<status, tally, unsubs, hasDown, completedCount, inc,
                   declared, publishedInc, doublePub, cascadeFired,
                   droppedSpawn, snap, submitMode, scanPending, curTarget,
                   curDir, exec, unsubW, schedVars, retryVars>>

(* The subscribe triple, reduction R2: upstream-tally increment
   (subscribeUpstreamDependency) + unsubSpecs insert + hasDownstream set.
   Edges are recorded with the downstream's CURRENT incarnation, modelling
   the closure over that incarnation's dependencyTally ref: a drain
   delivered after the downstream re-incarnated decrements a dead ref,
   i.e. no-ops (see UnsubDrain). Skipped when the contains-check found the
   edge — with fresh unsubSpecs per incarnation the check can no longer be
   stale across incarnations. *)
SubmitSubApply(t) ==
    /\ submitPc[t] = "subApply"
    /\ LET a == curTarget[t]
       IN IF curContained[t]
          THEN UNCHANGED <<tally, unsubs, hasDown>>
          ELSE IF curDir[t] = "down"
               THEN /\ tally'   = [tally EXCEPT ![t] = @ + 1]
                    /\ unsubs'  = [unsubs EXCEPT ![a] = @ \cup {<<t, inc[t]>>}]
                    /\ hasDown' = [hasDown EXCEPT ![a] = TRUE]
               ELSE /\ tally'   = [tally EXCEPT ![curTarget[t]] = @ + 1]
                    /\ unsubs'  = [unsubs EXCEPT ![t] = @ \cup {<<a, inc[a]>>}]
                    /\ hasDown' = [hasDown EXCEPT ![t] = TRUE]
    /\ submitPc' = [submitPc EXCEPT ![t] = "scanPick"]
    /\ UNCHANGED <<status, completedCount, inc, declared, publishedInc,
                   doublePub, cascadeFired, droppedSpawn, snap, submitMode,
                   scanPending, curTarget, curDir, curContained, exec,
                   unsubW, schedVars, retryVars>>

(* checkExecutionReadiness: tally read + spawn,
   still inside the region *)
SubmitReadiness(t) ==
    /\ submitPc[t] = "ready"
    /\ exec' = IF tally[t] = 0 THEN SpawnExec(exec, t, inc[t]) ELSE exec
    /\ droppedSpawn' = (droppedSpawn \/ (tally[t] = 0 /\ ExecSlotsFull(exec, t)))
    /\ submitPc' = [submitPc EXCEPT ![t] = "rel"]
    /\ UNCHANGED <<status, tally, unsubs, hasDown, completedCount, inc,
                   declared, publishedInc, doublePub, cascadeFired, snap, submitMode,
                   scanPending, curTarget, curDir, curContained, unsubW,
                   schedVars, retryVars>>

(* permit release, then checkRetryQueue(footprint).start — a fire-and-forget
   sweep that wakes parked txns whose footprints conflict with the one just
   submitted. One sweep slot per submitter; a busy slot drops the spawn and
   sets the droppedSweep ghost (NoDroppedSweep detects the truncation). *)
SubmitRelease(t) ==
    /\ submitPc[t] = "rel"
    /\ graphSem = t
    /\ graphSem'  = "none"
    /\ submitPc'  = [submitPc EXCEPT ![t] = "idle"]
    /\ curTarget' = [curTarget EXCEPT ![t] = "none"]
    \* checkRetryQueue(footprint).start — spawned unconditionally, exactly
    \* as the code does: the map is read at ACQUIRE time, not at spawn
    \* time, so a sweep spawned while a parker holds the retry semaphore
    \* blocks and then rescues that parker.
    /\ IF \E k \in {"s1", "s2", "s3"} : sweep[<<t, k>>].ph = "none"
       THEN /\ sweep' = [sweep EXCEPT ![<<t,
                            (CHOOSE k \in {"s1", "s2", "s3"} : sweep[<<t, k>>].ph = "none")>>] =
                            [ph |-> "acq", pend |-> {}, fp |-> declared[t]]]
            /\ UNCHANGED droppedSweep
       ELSE /\ droppedSweep' = TRUE
            /\ UNCHANGED sweep
    /\ UNCHANGED <<txnVars, submitMode, scanPending, curDir, curContained,
                   exec, unsubW, active, version, parked, retrySem,
                   truncatedWake, parkChk>>

(* The submit wrapper's handleErrorWith (TxnRuntime.commit): an exception
   anywhere in a PLAIN submission completes the signal with the error and
   does nothing else — the txn stays wherever the partial workflow left it
   (possibly a zombie in activeTransactions). For an IMM submission the
   exception propagates into execute's dispatch instead — modelled at the
   exec fiber (ExecResubAbort below). permit.use releases on error. *)
SubmitAbort(t) ==
    /\ AbortsEnabled
    /\ submitMode[t] = "plain"
    /\ submitPc[t] \notin {"idle"}
    /\ graphSem' = IF graphSem = t THEN "none" ELSE graphSem
    /\ submitPc' = [submitPc EXCEPT ![t] = "idle"]
    /\ completedCount' = [completedCount EXCEPT ![t] = @ + 1]
    /\ UNCHANGED <<status, tally, unsubs, hasDown, inc, declared,
                   publishedInc, doublePub, cascadeFired, droppedSpawn, snap, submitMode,
                   scanPending, curTarget, curDir, curContained, exec,
                   unsubW, active, version, retryVars>>

---------------------------------------------------------------------------
(* Execute fibers — execute with the atomic-commit
   abstraction that specs/commit/CommitProtocol.tla refines. *)
---------------------------------------------------------------------------

(* admitForExecution: semaphore-guarded admission gate (reduction R3 —
   single region). A fiber proceeds only if it was spawned for the CURRENT
   incarnation (fInc — in the code a stale fiber CASes its own dead status
   ref), the incarnation is still Scheduled, and its dependency tally is
   zero. Losers retire their slot without side effects. *)
ExecAdmit(t, s) ==
    /\ exec[<<t, s>>].pc = "regRun"
    /\ graphSem = "none"
    /\ IF exec[<<t, s>>].fInc = inc[t] /\ status[t] = "Scheduled" /\ tally[t] = 0
       THEN /\ status' = [status EXCEPT ![t] = "Running"]
            /\ exec'   = [exec EXCEPT ![<<t, s>>].pc = "snap"]
       ELSE /\ exec'   = [exec EXCEPT ![<<t, s>>] = NoExec]
            /\ UNCHANGED status
    /\ UNCHANGED <<tally, unsubs, hasDown, completedCount, inc, declared,
                   publishedInc, doublePub, cascadeFired, droppedSpawn,
                   snap, submitVars, unsubW, schedVars, retryVars>>

(* Log construction abstracted to one snapshot of the footprint's versions,
   PER FIBER — each execute run builds a private log in the code, so
   concurrent fibers of one txn capture independent initial values. Spec A
   refines read-time granularity; Spec B needs the snapshot for the retry
   decision (RetryPred evaluates against it) and the park-time staleness
   check — the coverage gate itself is snapshot-free. *)
ExecSnapshot(t, s) ==
    /\ exec[<<t, s>>].pc = "snap"
    /\ snap' = [snap EXCEPT ![<<t, s>>] = [v \in VarIds |-> version[v]]]
    /\ exec' = [exec EXCEPT ![<<t, s>>].pc = "commit"]
    /\ UNCHANGED <<status, tally, unsubs, hasDown, completedCount, inc,
                   declared, publishedInc, doublePub, cascadeFired,
                   droppedSpawn, submitVars, unsubW, schedVars, retryVars>>

(* The atomic commit, at Spec B's granularity: COVERAGE decides refinement,
   mirroring AnalysedTxn.commit — the commit-time dirty check is gone from
   the code, and a transaction publishes iff its declared footprint covers
   what the run really LOGGED. Both of the code's arms fold over the log
   (log.idFootprint), so the check is modelled over LoggedFP per the
   standing rule (specs/README.md): a check that mirrors a fold over the
   log must read what the log RECORDS, not what the transaction touches.

   THE UNDER-APPROXIMATION FLAG POINTS OPPOSITE WAYS ON THE TWO ARMS,
   faithfully to the code: on the commit arm the flag SHORT-CIRCUITS TO
   PUBLISH (coversActualFootprint — a flagged transaction ran alone, so
   nothing moved under it and a coverage lap could teach it nothing); on
   the retry arm the flag FORCES REFINEMENT (the TxnLogRetry arm — a
   flagged park is incompatible with everything and would be woken by
   every commit in the system). Neither `.under` disjunct is exercised by
   the current catalogue — no Spec B transaction is under-approximated —
   they are written out so the asymmetry is stated rather than left
   discoverable. In fact the WHOLE refine-instead-of-park arm never fires
   with this catalogue: both retry transactions declare footprints that
   cover their logs (tr exactly; tm from above — declared strictly wider
   than logged), so, like ExecResubTruncate, that branch has zero action
   coverage today. The missing witness is a retry transaction whose
   declared footprint misses a logged read. One fidelity note for that
   future scenario: the code's retry arm folds the log AS OF THE RETRY
   THROW — a prefix of the full run — while LoggedFP here describes the
   full-run log. Covers is monotone in the actual side, so this model can
   only refine MORE eagerly than the code, never less — the unsafe
   direction for a park check (a model that refines where the code parks
   never explores those parked interleavings); a retry scenario with
   post-retry-point accesses must add a prefix-log operator
   (RetryLoggedFP) rather than reuse LoggedFP.

   The refine outcome keeps the historical out-label "dirty": every
   downstream consumer routes on that string, and the routing (deregister,
   refine declared from Validated(LoggedFP), resubmit immediately) is
   exactly what the code's TxnResultLogDirty does regardless of which arm
   produced it. Publish = bump every written var. *)
ExecCommit(t, s) ==
    /\ exec[<<t, s>>].pc = "commit"
    /\ LET covered  == Covers(declared[t], Validated(LoggedFP(t)))
           retryNow == ~RetryPred(t, snap[<<t, s>>])
           refineOnCommit == ~declared[t].under /\ ~covered
           refineOnRetry  == declared[t].under \/ ~covered
       IN IF retryNow
          THEN IF refineOnRetry
               \* TxnLogRetry, refine-instead-of-park: a parked transaction
               \* whose declared footprint does not cover its logged reads
               \* can be left asleep by a conflictor the scheduler never
               \* matched it against — so it refines and re-runs instead.
               THEN /\ exec' = [exec EXCEPT ![<<t, s>>] =
                                   [pc |-> "regComp", out |-> "dirty", fInc |-> exec[<<t, s>>].fInc]]
                    /\ UNCHANGED <<version, publishedInc, doublePub>>
               \* TxnLogRetry, park: the predicate failed against the
               \* SNAPSHOT, and nothing on this arm re-reads live state —
               \* its withLock region checks only coverage (version-blind),
               \* and a read-only log resolves zero locks anyway — so a
               \* write committed since the snapshot does not rescue this
               \* outcome: modelled by deciding on snap, not version. The
               \* live staleness re-check happens later, at the park guards
               \* (ExecParkScan / ExecParkStale).
               ELSE /\ exec' = [exec EXCEPT ![<<t, s>>] =
                                   [pc |-> "regComp", out |-> "retry", fInc |-> exec[<<t, s>>].fInc]]
                    /\ UNCHANGED <<version, publishedInc, doublePub>>
          ELSE IF refineOnCommit
          THEN /\ exec' = [exec EXCEPT ![<<t, s>>] =
                              [pc |-> "regComp", out |-> "dirty", fInc |-> exec[<<t, s>>].fInc]]
               /\ UNCHANGED <<version, publishedInc, doublePub>>
          ELSE /\ version' = [v \in VarIds |->
                                IF v \in Writes(t) THEN version[v] + 1
                                                   ELSE version[v]]
               /\ doublePub' = [doublePub EXCEPT ![t] = @ \/ publishedInc[t]]
               /\ publishedInc' = [publishedInc EXCEPT ![t] = TRUE]
               /\ exec' = [exec EXCEPT ![<<t, s>>] =
                              [pc |-> "regComp", out |-> "success", fInc |-> exec[<<t, s>>].fInc]]
    /\ UNCHANGED <<status, tally, unsubs, hasDown, completedCount, inc,
                   declared, cascadeFired, droppedSpawn, snap, submitVars, unsubW, active,
                   graphSem, retryVars>>

(* Commit throws before publishing — poll(commit) raises, execute's
   handleErrorWith takes over: registerCompletion (first time) + complete.
   This same shape also covers the NORMAL TxnResultFailure dispatch (a
   TxnLogError log): one removal, one cascade, one Left completion. *)
ExecCommitFail(t, s) ==
    /\ AbortsEnabled
    /\ exec[<<t, s>>].pc = "commit"
    /\ exec' = [exec EXCEPT ![<<t, s>>] = [pc |-> "eRegComp", out |-> "fail", fInc |-> exec[<<t, s>>].fInc]]
    /\ UNCHANGED <<txnVars, submitVars, unsubW, schedVars, retryVars>>

(* registerCompletion: semaphore-guarded remove (R3)... *)
ExecRegComplete(t, s) ==
    /\ exec[<<t, s>>].pc = "regComp"
    /\ graphSem = "none"
    /\ active' = active \ {t}
    /\ exec'   = [exec EXCEPT ![<<t, s>>].pc = "spawnU"]
    /\ UNCHANGED <<txnVars, submitVars, unsubW, graphSem, version, retryVars>>

(* ...followed by triggerUnsub.start (fire-and-forget): the cascade races
   everything that follows, including the dirty resubmission — but the
   cascadeFired gate admits at most one cascade per incarnation, and the
   fInc check models the fiber holding the OLD incarnation's gate after a
   resubmission bumped inc (the old gate is already TRUE in the code). *)
ExecSpawnUnsub(t, s) ==
    /\ exec[<<t, s>>].pc = "spawnU"
    /\ IF exec[<<t, s>>].fInc = inc[t] /\ ~cascadeFired[t]
       THEN /\ unsubW' = SpawnUnsub(unsubW, t, unsubs[t], inc[t])
            /\ cascadeFired' = [cascadeFired EXCEPT ![t] = TRUE]
            /\ droppedSpawn' = (droppedSpawn \/ (unsubs[t] /= {} /\ UnsubSlotsFull(unsubW, t)))
       ELSE UNCHANGED <<unsubW, cascadeFired, droppedSpawn>>
    \* Dispatch: success completes; dirty resubmits (imm); retry either
    \* resubmits (plain) when hasDownstream — someone subscribed during the
    \* run, so a conflicting write may already have landed — or parks. The
    \* hasDownstream read is collapsed into this step: the txn left
    \* activeTransactions at regComp, so no new subscriber can flip it.
    /\ exec' = [exec EXCEPT ![<<t, s>>].pc =
                   CASE exec[<<t, s>>].out = "success" -> "complete"
                     [] exec[<<t, s>>].out = "dirty"   -> "resubInit"
                     [] exec[<<t, s>>].out = "retry"   ->
                            IF hasDown[t] THEN "resubInit" ELSE "parkAcq"
                     [] OTHER                          -> "complete"]
    /\ UNCHANGED <<status, tally, unsubs, hasDown, completedCount, inc,
                   declared, publishedInc, doublePub, snap, submitVars,
                   active, graphSem, version, retryVars>>

(* completionSignal.complete(Right) — TxnResultSuccess dispatch. The code
   never demotes executionStatus (nothing writes it after Running except a
   resubmission's set(Scheduled)), so status is deliberately NOT touched
   here; completion is tracked by completedCount alone. *)
ExecComplete(t, s) ==
    /\ exec[<<t, s>>].pc = "complete"
    /\ completedCount' = [completedCount EXCEPT ![t] = @ + 1]
    /\ exec'   = [exec EXCEPT ![<<t, s>>] = NoExec]
    /\ UNCHANGED <<status, tally, unsubs, hasDown, inc, declared,
                   publishedInc, doublePub, cascadeFired, droppedSpawn, snap, submitVars,
                   unsubW, schedVars, retryVars>>

(* TxnResultLogDirty dispatch: refine the declared footprint to the actual
   one (log-derived, getValidated) and resubmit via
   submitTxnForImmediateRetry — INSIDE this fiber. this.copy shares
   unsubs/tally/hasDown/completionSignal; only the footprint is replaced.

   REDUCTION R5 (provably vacuous since the H4 fix): the model has one
   submit workflow per txn (guard: submitPc = "idle"), serializing
   concurrent imm submissions the code could in principle run inline from
   two dirty fibers. Post-fix no such concurrency exists: two dirty fibers
   of ONE incarnation would need two admitted fibers (excluded by the
   admission gate / NoDoubleExec), and a fiber of incarnation k cannot
   still be mid-resubmission when k+1 resubmits, because k+1's fiber is
   spawned inside k's resubmission region and cannot admit until that
   region releases. *)
ExecResubInit(t, s) ==
    /\ exec[<<t, s>>].pc = "resubInit"
    /\ inc[t] < MaxInc
    /\ submitPc[t] = "idle"
    \* dirty refines the declared footprint from the log and resubmits via
    \* submitTxnForImmediateRetry; a retry-with-downstream keeps its
    \* footprint and resubmits via plain submitTxn (both freshIncarnation).
    \* LoggedFP, not ActualFP: the refinement source is TxnLogValid.idFootprint,
    \* another fold over log.values — so an unlogged read is dropped from the
    \* declared footprint on refinement, not merely missed by the stale check.
    /\ declared'     = [declared EXCEPT ![t] =
                           IF exec[<<t, s>>].out = "dirty"
                           THEN Validated(LoggedFP(t)) ELSE @]
    /\ inc'          = [inc EXCEPT ![t] = @ + 1]
    /\ publishedInc' = [publishedInc EXCEPT ![t] = FALSE]
    \* freshIncarnation: brand-new tally / unsub-edge map / status /
    \* cascade-gate refs. Old cascades still drain the OLD map — their
    \* frozen pend copies live in unsubW — and their decrements no-op
    \* against re-incarnated targets (UnsubDrain's inc match). Scanners in
    \* the pre-Scheduled window see NotScheduled and skip, matching the
    \* fresh status ref.
    /\ tally'        = [tally EXCEPT ![t] = 0]
    /\ unsubs'       = [unsubs EXCEPT ![t] = {}]
    /\ status'       = [status EXCEPT ![t] = "NotScheduled"]
    /\ cascadeFired' = [cascadeFired EXCEPT ![t] = FALSE]
    /\ hasDown'      = [hasDown EXCEPT ![t] = FALSE]
    /\ submitPc'     = [submitPc EXCEPT ![t] = "reset1"]
    /\ submitMode'   = [submitMode EXCEPT ![t] =
                           IF exec[<<t, s>>].out = "dirty" THEN "imm" ELSE "plain"]
    /\ exec'         = [exec EXCEPT ![<<t, s>>].pc = "resubWait"]
    /\ UNCHANGED <<completedCount, doublePub, droppedSpawn, snap,
                   scanPending, curTarget, curDir, curContained, unsubW,
                   schedVars, retryVars>>

(* A fiber at the incarnation bound retires its slot: the code resubmits
   without any bound, so the model truncates the behaviour here, frees the
   slot, and raises truncatedWake — which makes the state a declared
   exploration horizon (Terminating stutters on it) instead of a spurious
   protocol deadlock.

   IT DOES NOT FIRE ANYWHERE. The retry scenarios are the ones that would need
   it: WITHOUT SweepWake's self-skip a retrier burns an incarnation per SPURIOUS
   SELF-WAKE while its conflictor sits mid-submission, and any finite MaxInc is
   then exhaustible by adversarial scheduling. With the skip in place this
   action has ZERO action coverage in every organic config — Scheduler,
   SchedulerRetry and SchedulerAbsentKey alike, all three of which assert
   BoundsNeverBind and pass. It stays as the model's finiteness backstop,
   because the code's resubmission really is unbounded and something has to
   close that off. But no organic scenario reaches it, so if it starts firing,
   something regressed. *)
ExecResubTruncate(t, s) ==
    /\ exec[<<t, s>>].pc = "resubInit"
    /\ inc[t] >= MaxInc
    /\ exec' = [exec EXCEPT ![<<t, s>>] = NoExec]
    /\ truncatedWake' = TRUE
    /\ UNCHANGED <<status, tally, unsubs, hasDown, completedCount, inc,
                   declared, publishedInc, doublePub, cascadeFired,
                   droppedSpawn, snap, submitVars, unsubW, schedVars,
                   parked, retrySem, sweep, droppedSweep, parkChk>>

(* The imm submission ran to completion inside this fiber; execute returns. *)
ExecResubDone(t, s) ==
    /\ exec[<<t, s>>].pc = "resubWait"
    /\ submitPc[t] = "idle"
    /\ submitMode[t] = "imm"
    /\ exec' = [exec EXCEPT ![<<t, s>>] = NoExec]
    /\ UNCHANGED <<txnVars, submitVars, unsubW, schedVars, retryVars>>

(* An exception inside the imm submission propagates to execute's
   handleErrorWith, which runs registerCompletion a SECOND time and re-enters
   triggerUnsub. PRE-FIX that spawned a SECOND cascade over the SAME shared
   unsubSpecs, racing the first one's clear() — one of H4's two double-execution
   engines. Post-fix the fiber lands at "eRegComp" and the cascadeFired gate
   turns the second cascade into a no-op (ExecErrSpawnUnsub). The path stays in
   the model because the gate is exactly what needs checking.
   permit.use releases the semaphore on the way out. *)
ExecResubAbort(t, s) ==
    /\ AbortsEnabled
    /\ exec[<<t, s>>].pc = "resubWait"
    /\ submitPc[t] /= "idle"
    /\ graphSem' = IF graphSem = t THEN "none" ELSE graphSem
    /\ submitPc' = [submitPc EXCEPT ![t] = "idle"]
    /\ exec'     = [exec EXCEPT ![<<t, s>>] = [pc |-> "eRegComp", out |-> "fail", fInc |-> exec[<<t, s>>].fInc]]
    /\ UNCHANGED <<txnVars, submitMode, scanPending, curTarget, curDir,
                   curContained, unsubW, active, version, retryVars>>

(* Error path: registerCompletion (possibly the second time — idempotent on the
   active set, and since the H4 fix idempotent on the unsub cascade too, via the
   cascadeFired gate below), then complete(Left). *)
ExecErrRegComplete(t, s) ==
    /\ exec[<<t, s>>].pc = "eRegComp"
    /\ graphSem = "none"
    /\ active' = active \ {t}
    /\ exec'   = [exec EXCEPT ![<<t, s>>].pc = "eSpawnU"]
    /\ UNCHANGED <<txnVars, submitVars, unsubW, graphSem, version, retryVars>>

ExecErrSpawnUnsub(t, s) ==
    /\ exec[<<t, s>>].pc = "eSpawnU"
    /\ IF exec[<<t, s>>].fInc = inc[t] /\ ~cascadeFired[t]
       THEN /\ unsubW' = SpawnUnsub(unsubW, t, unsubs[t], inc[t])
            /\ cascadeFired' = [cascadeFired EXCEPT ![t] = TRUE]
            /\ droppedSpawn' = (droppedSpawn \/ (unsubs[t] /= {} /\ UnsubSlotsFull(unsubW, t)))
       ELSE UNCHANGED <<unsubW, cascadeFired, droppedSpawn>>
    /\ exec' = [exec EXCEPT ![<<t, s>>].pc = "eComplete"]
    /\ UNCHANGED <<status, tally, unsubs, hasDown, completedCount, inc,
                   declared, publishedInc, doublePub, snap, submitVars,
                   active, graphSem, version, retryVars>>

ExecErrComplete(t, s) ==
    /\ exec[<<t, s>>].pc = "eComplete"
    /\ completedCount' = [completedCount EXCEPT ![t] = @ + 1]
    /\ exec'   = [exec EXCEPT ![<<t, s>>] = NoExec]
    /\ UNCHANGED <<status, tally, unsubs, hasDown, inc, declared,
                   publishedInc, doublePub, cascadeFired, droppedSpawn, snap, submitVars,
                   unsubW, schedVars, retryVars>>

---------------------------------------------------------------------------
(* triggerUnsub cascades — detached fibers, NO semaphore. Post-fix, a
   cascade is spawned with its edge set preloaded (reduction R6: the gate
   serializes cascades and the owner left activeTransactions before the
   spawn, so the drained map is frozen — see SpawnUnsub). *)
---------------------------------------------------------------------------

(* Each drained edge is unsubscribeUpstreamDependency on the closure's
   incarnation: if the downstream re-incarnated since subscribing, the
   decrement lands on a dead ref — a no-op here (the inc match). Live
   drains whose old value was 1 spawn the downstream's execute, tagged
   with the downstream's current incarnation. *)
UnsubDrain(o, u) ==
    /\ unsubW[<<o, u>>].ph = "drain"
    /\ unsubW[<<o, u>>].pend /= {}
    /\ \E e \in unsubW[<<o, u>>].pend :
        IF inc[e[1]] = e[2]
        THEN /\ tally' = [tally EXCEPT ![e[1]] = @ - 1]
             /\ exec'  = IF tally[e[1]] = 1 THEN SpawnExec(exec, e[1], inc[e[1]]) ELSE exec
             /\ droppedSpawn' = (droppedSpawn \/ (tally[e[1]] = 1 /\ ExecSlotsFull(exec, e[1])))
             /\ unsubW' = [unsubW EXCEPT ![<<o, u>>].pend = @ \ {e}]
        ELSE /\ unsubW' = [unsubW EXCEPT ![<<o, u>>].pend = @ \ {e}]
             /\ UNCHANGED <<tally, exec, droppedSpawn>>
    /\ UNCHANGED <<status, unsubs, hasDown, completedCount, inc, declared,
                   publishedInc, doublePub, cascadeFired, snap, submitVars,
                   schedVars, retryVars>>

(* unsubSpecs.clear() — a no-op if the owner re-incarnated (the cascade
   holds the old, dead map; the new incarnation's map is already fresh). *)
UnsubClear(o, u) ==
    /\ unsubW[<<o, u>>].ph = "drain"
    /\ unsubW[<<o, u>>].pend = {}
    /\ unsubs' = IF inc[o] = unsubW[<<o, u>>].ownerInc
                 THEN [unsubs EXCEPT ![o] = {}]
                 ELSE unsubs
    /\ unsubW' = [unsubW EXCEPT ![<<o, u>>] = NoUnsub]
    /\ UNCHANGED <<status, tally, hasDown, completedCount, inc, declared,
                   publishedInc, doublePub, cascadeFired, droppedSpawn,
                   snap, submitVars, exec, schedVars, retryVars>>

---------------------------------------------------------------------------
(* Next *)
---------------------------------------------------------------------------

---------------------------------------------------------------------------
(* Retry machinery — H1 territory: submitTxnForRetry parks under the retry
   semaphore; checkRetryQueue sweeps run fire-and-forget after every
   submission's release and wake parked txns whose footprints conflict
   with the submitted one — wakes fire ONLY from these sweeps. The H1
   window: the parking fiber left activeTransactions at regComp and lands
   in the retry map only at ParkAdd; a conflicting writer whose sweep runs
   in that gap sees the parker in neither structure. *)
---------------------------------------------------------------------------

(* retrySemaphore.permit acquire for the park region *)
ExecParkAcq(t, s) ==
    /\ exec[<<t, s>>].pc = "parkAcq"
    /\ retrySem = NoHolder
    /\ retrySem' = [kind |-> "park", t |-> t]
    /\ exec'     = [exec EXCEPT ![<<t, s>>].pc = "parkScan"]
    /\ UNCHANGED <<txnVars, submitVars, unsubW, active, graphSem, version,
                   parked, sweep, droppedSweep, truncatedWake, parkChk>>

(* Park region, split in CODE ORDER — NOT collapsed. The retry semaphore
   serializes this region against SWEEPS but NOT against commits or
   completions, so each read is its own step and a conflictor can move
   between the reads. THE ORDER IS LOAD-BEARING (H1):

     ExecParkScan  — activeTransactions scan, catching conflictors that
                     have not yet committed (their submission sweep already
                     ran, against a map not yet containing us).
     ExecParkStale — read-set staleness, catching conflictors that already
                     COMMITTED. It must be the LAST read before the insert:
                     a conflictor committing after it is either still in
                     activeTransactions at scan time (removal follows its
                     commit, and the scan precedes the stale read) or has a
                     sweep still blocked on the retry semaphore we hold —
                     which will find us parked and wake us.

   The reverse order (stale, then scan) LOSES WAKEUPS: a conflictor can commit
   after the stale read and leave activeTransactions before the scan, escaping
   both. That is not a hypothetical — reverse the two actions' pc transitions
   on a scratch copy and SchedulerRetry.cfg deadlocks in a 36-state trace, with
   tw's ExecCommit and ExecRegComplete landing in exactly the gap between tr's
   stale read and tr's scan. *)
ExecParkStale(t, s) ==
    /\ exec[<<t, s>>].pc = "parkStale"
    /\ retrySem = [kind |-> "park", t |-> t]
    \* LoggedFP, not ActualFP: anyReadChangedSinceRead folds over log.values,
    \* so a read that recorded no entry is INVISIBLE to this check.
    /\ parkChk' = [parkChk EXCEPT ![<<t, s>>].st =
                      \E v \in CombinedIds(LoggedFP(t)) : version[v] /= snap[<<t, s>>][v]]
    /\ exec' = [exec EXCEPT ![<<t, s>>].pc = "parkInsert"]
    /\ UNCHANGED <<txnVars, submitVars, unsubW, active, graphSem, version,
                   parked, retrySem, sweep, droppedSweep, truncatedWake>>

ExecParkScan(t, s) ==
    /\ exec[<<t, s>>].pc = "parkScan"
    /\ retrySem = [kind |-> "park", t |-> t]
    /\ parkChk' = [parkChk EXCEPT ![<<t, s>>].cf =
                      \E a \in active : ~IsCompatible(declared[t], declared[a])]
    /\ exec' = [exec EXCEPT ![<<t, s>>].pc = "parkStale"]
    /\ UNCHANGED <<txnVars, submitVars, unsubW, active, graphSem, version,
                   parked, retrySem, sweep, droppedSweep, truncatedWake>>

ExecParkInsert(t, s) ==
    /\ exec[<<t, s>>].pc = "parkInsert"
    /\ retrySem = [kind |-> "park", t |-> t]
    /\ parkChk' = [parkChk EXCEPT ![<<t, s>>] = [cf |-> FALSE, st |-> FALSE]]
    /\ retrySem' = NoHolder
    /\ IF parkChk[<<t, s>>].cf \/ parkChk[<<t, s>>].st
       THEN /\ exec' = [exec EXCEPT ![<<t, s>>] =
                           [pc |-> "resubInit", out |-> "retry",
                            fInc |-> exec[<<t, s>>].fInc]]
            /\ UNCHANGED parked
       ELSE /\ parked' = parked \cup {t}
            /\ exec'   = [exec EXCEPT ![<<t, s>>] = NoExec]
    /\ UNCHANGED <<txnVars, submitVars, unsubW, active, graphSem, version,
                   sweep, droppedSweep, truncatedWake>>

(* checkRetryQueue: acquire the retry semaphore and snapshot the parked
   set (retryMap.keys.toList) — evaluated HERE, not at spawn time. *)
SweepAcquire(t, k) ==
    /\ sweep[<<t, k>>].ph = "acq"
    /\ retrySem = NoHolder
    /\ retrySem' = [kind |-> "sweep", t |-> t]
    /\ sweep'    = [sweep EXCEPT ![<<t, k>>] =
                       [ph |-> "scan", pend |-> parked, fp |-> sweep[<<t, k>>].fp]]
    /\ UNCHANGED <<txnVars, submitVars, exec, unsubW, active, graphSem,
                   version, parked, droppedSweep, truncatedWake, parkChk>>

(* Per parked txn: if it is NOT the submitter itself and its footprint
   conflicts with the submitted one, run the stored wake — freshIncarnation
   >>= submitTxn, .start'ed — and drop it from the map. The self-skip
   (p /= t) matters: a parked transaction is footprint-incompatible with
   ITSELF (it writes), so without it a transaction's own in-flight
   submission sweep wakes it, it re-runs, re-parks, and the next sweep can
   do it again — an unbounded spurious spin. Its own submission cannot
   satisfy its own predicate: it retried rather than committed. This is why
   the retry map is keyed by TxnId rather than by footprint. *)
SweepWake(t, k) ==
    /\ sweep[<<t, k>>].ph = "scan"
    /\ sweep[<<t, k>>].pend /= {}
    /\ retrySem = [kind |-> "sweep", t |-> t]
    /\ \E p \in sweep[<<t, k>>].pend :
        IF p /= t /\ ~IsCompatible(sweep[<<t, k>>].fp, declared[p]) /\ p \in parked
        THEN /\ parked' = parked \ {p}
             /\ IF inc[p] < MaxInc
                THEN /\ inc'          = [inc EXCEPT ![p] = @ + 1]
                     /\ tally'        = [tally EXCEPT ![p] = 0]
                     /\ unsubs'       = [unsubs EXCEPT ![p] = {}]
                     /\ status'       = [status EXCEPT ![p] = "NotScheduled"]
                     /\ cascadeFired' = [cascadeFired EXCEPT ![p] = FALSE]
                     /\ hasDown'      = [hasDown EXCEPT ![p] = FALSE]
                     /\ publishedInc' = [publishedInc EXCEPT ![p] = FALSE]
                     /\ submitPc'     = [submitPc EXCEPT ![p] = "reset1"]
                     /\ submitMode'   = [submitMode EXCEPT ![p] = "plain"]
                     /\ UNCHANGED truncatedWake
                ELSE /\ truncatedWake' = TRUE
                     /\ UNCHANGED <<inc, tally, unsubs, status, cascadeFired,
                                    hasDown, publishedInc, submitPc, submitMode>>
             /\ sweep' = [sweep EXCEPT ![<<t, k>>].pend = @ \ {p}]
        ELSE /\ sweep' = [sweep EXCEPT ![<<t, k>>].pend = @ \ {p}]
             /\ UNCHANGED <<parked, inc, tally, unsubs, status, cascadeFired,
                            hasDown, publishedInc, submitPc, submitMode,
                            truncatedWake>>
    /\ UNCHANGED <<completedCount, declared, doublePub, droppedSpawn, snap,
                   scanPending, curTarget, curDir, curContained, exec,
                   unsubW, active, graphSem, version, retrySem, droppedSweep,
                   parkChk>>

(* Sweep done: permit release *)
SweepRelease(t, k) ==
    /\ sweep[<<t, k>>].ph = "scan"
    /\ sweep[<<t, k>>].pend = {}
    /\ retrySem = [kind |-> "sweep", t |-> t]
    /\ retrySem' = NoHolder
    /\ sweep'    = [sweep EXCEPT ![<<t, k>>] = NoSweep]
    /\ UNCHANGED <<txnVars, submitVars, exec, unsubW, active, graphSem,
                   version, parked, droppedSweep, truncatedWake, parkChk>>

(* Legitimate terminal states stutter: every submitted transaction's
   completion signal has fired. Anything else with no enabled action is a
   REAL protocol deadlock, and TLC's deadlock detection (now enabled for
   organic runs — no -deadlock flag) reports it. *)
Terminating ==
    /\ \/ \A t \in Txns : completedCount[t] >= 1
       \/ truncatedWake
    /\ UNCHANGED vars

Next ==
    \/ \E t \in Txns :
        \/ SubmitReset1(t)     \/ SubmitReset2(t)   \/ SubmitAcquire(t)
        \/ SubmitSetScheduled(t) \/ SubmitInsertActive(t)
        \/ SubmitScanPick(t)   \/ SubmitScanCheck(t)
        \/ SubmitSubCheck(t)   \/ SubmitSubApply(t)
        \/ SubmitReadiness(t)  \/ SubmitRelease(t)  \/ SubmitAbort(t)
    \/ \E t \in Txns, s \in {"A", "B"} :
        \/ ExecAdmit(t, s)      \/ ExecSnapshot(t, s) \/ ExecCommit(t, s)
        \/ ExecCommitFail(t, s) \/ ExecRegComplete(t, s)
        \/ ExecSpawnUnsub(t, s) \/ ExecComplete(t, s)
        \/ ExecResubInit(t, s)  \/ ExecResubTruncate(t, s)
        \/ ExecResubDone(t, s)  \/ ExecResubAbort(t, s)
        \/ ExecErrRegComplete(t, s) \/ ExecErrSpawnUnsub(t, s)
        \/ ExecErrComplete(t, s)
        \/ ExecParkAcq(t, s)    \/ ExecParkScan(t, s)
        \/ ExecParkStale(t, s)  \/ ExecParkInsert(t, s)
    \/ \E o \in Txns, u \in {"u1", "u2"} :
        \/ UnsubDrain(o, u) \/ UnsubClear(o, u)
    \/ \E t \in Txns, k \in {"s1", "s2", "s3"} :
        \/ SweepAcquire(t, k) \/ SweepWake(t, k) \/ SweepRelease(t, k)
    \/ Terminating

Spec == Init /\ [][Next]_vars

---------------------------------------------------------------------------
(* State constraint — a finiteness backstop, and deliberately nothing more.

   THE TALLY'S LOWER BOUND SITS BELOW TallyNonNegative'S FLOOR, and the gap is
   load-bearing. A state constraint must never be tight enough to IMPLY an
   invariant. TLC prunes a constraint-violating state from the QUEUE, so with a
   floor of 0 here, every state TLC went on to EXPLORE would satisfy
   TallyNonNegative by construction, and any behaviour reachable only THROUGH a
   negative tally would be quietly unreachable — the bound would be enforcing
   the very property the invariant is there to test. (TLC does evaluate
   invariants on a state before pruning it, so the boundary violation itself
   would still be reported; what would be lost is everything beyond it.)
   Leaving the floor at -2 keeps TallyNonNegative falsifiable INSIDE the
   explored space, which is the only place its verdict means anything.

   inc needs no clause: ExecResubTruncate caps it at the action level. *)
---------------------------------------------------------------------------

StateConstraint ==
    /\ \A t \in Txns : tally[t] >= -2 /\ tally[t] <= 4
    /\ \A v \in VarIds : version[v] <= MaxVer

---------------------------------------------------------------------------
(* Invariants — the property table, each one's verdict and each one's
   negative control all live in specs/README.md. *)
---------------------------------------------------------------------------

TypeOK ==
    /\ status \in [Txns -> {"NotScheduled", "Scheduled", "Running"}]
    /\ graphSem \in Txns \cup {"none"}
    /\ active \subseteq Txns

(* Tallies must never go negative: a negative tally means an unsubscribe
   was delivered for an edge the tally no longer counts (H4 family). *)
TallyNonNegative == \A t \in Txns : tally[t] >= 0

(* Contract C — the interface property Spec A assumes: no two
   transactions in their execute/commit window with incompatible DECLARED
   footprints (current incarnations). The code's window is
   [admitForExecution succeeded, registerCompletion fired] — completion
   never demotes status, so the window is
   encoded from exec-fiber position, not status: past regRun (snapshot
   pending or taken, commit pending) until the active-set removal fires. *)
InExecWindow(t) ==
    \E s \in {"A", "B"} : exec[<<t, s>>].pc \in {"snap", "commit", "regComp"}

ContractC ==
    \A t \in Txns, u \in Txns :
        (t /= u /\ InExecWindow(t) /\ InExecWindow(u))
            => IsCompatible(declared[t], declared[u])

(* No transaction has two fibers inside the execute/commit window at once
   (double-spawn via stale zero-tests / stale subscriptions). *)
NoDoubleExec ==
    \A t \in Txns :
        ~(exec[<<t, "A">>].pc \in ExecWindowPcs /\ exec[<<t, "B">>].pc \in ExecWindowPcs)

(* No execute fiber may be ADMITTED (inside the commit window) for a
   transaction that already completed — a completed txn re-executing means
   its log commits twice. Fibers merely PARKED at the admission gate
   ("regRun") for a completed txn are expected and harmless: the gate
   rejects them (terminal status loses the CAS). "Completed" is
   completedCount >= 1. *)
NoExecOnCompleted ==
    \A t \in Txns, s \in {"A", "B"} :
        exec[<<t, s>>].pc \in ExecWindowPcs => completedCount[t] = 0

(* The two-slot fiber bound never silently truncated a behaviour: every
   attempted spawn found a free slot. Include this in enumeration runs —
   if it goes red, deeper verdicts at those depths are unreliable until
   slots are widened for that scenario. *)
NoDroppedSpawn == ~droppedSpawn

(* completionSignal completes at most once (the #52 class) *)
CompletionAtMostOnce == \A t \in Txns : completedCount[t] <= 1

(* A transaction's writes are published at most once per incarnation *)
NoDoublePublish == \A t \in Txns : ~doublePub[t]

(* No truncation and no dropped sweep occurred: the verdict is exhaustive
   within the bounds rather than clipped by them.

   EVERY ORGANIC CONFIG ASSERTS THIS, retry ones included — which is worth
   saying, because the retry scenarios look like the ones that should need an
   escape. Without SweepWake's self-skip a parked retrier is woken by its OWN
   in-flight submission sweep, re-runs, re-parks and is woken again, burning an
   incarnation per cycle, and no finite MaxInc survives that. The self-skip
   removes the spin, and with it gone ExecResubTruncate has ZERO action coverage
   in Scheduler, SchedulerRetry and SchedulerAbsentKey alike. Only
   SchedulerAborts omits this invariant, and it omits most of the table besides:
   an aborted zombie legitimately strands its dependents. *)
BoundsNeverBind == ~truncatedWake /\ ~droppedSweep


===============================================================================

