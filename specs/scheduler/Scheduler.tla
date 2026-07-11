------------------------------ MODULE Scheduler ------------------------------
(*
 * Spec B: the bengal-stm transaction scheduler protocol.
 * Scala correspondence: TxnRuntimeContext.scala. Actions cite method names
 * (submitTxn, subscribeDownstreamDependency, ...) rather than line numbers —
 * line references rot on every comment edit and verify_anchors.sh cannot
 * guard them; symbol names it can grep.
 *
 * PHASE 1 SCOPE (docs/plans/formal-specs.md §5, §8): submission and graph
 * building, dependency tallies, execution statuses, unsubscribe cascades,
 * the dirty-resubmission path (submitTxnForImmediateRetry), and the error
 * recovery branches. The retry map / park / wake machinery (TxnResultRetry)
 * is Phase 2 and absent here; the commit is Spec B's atomic version-bump
 * abstraction (plan §3) — Spec A refines it.
 *
 * GRANULARITY (plan §5 atomicity mapping): one model step = one operation on
 * one shared mutable object (Ref get/set/update, TrieMap read/insert/remove).
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
 *   R6. A cascade spawns with its edge set preloaded: the cascadeFired
 *       gate serializes cascades per incarnation and the drained map is
 *       frozen by spawn time, so the code's gate/nonEmpty/values steps
 *       collapse soundly (pre-fix these were kept separate because
 *       double-spawned cascades could race a clear()).
 *
 * THE H4 FIX IS MODELLED (matching the code): admitForExecution admits at
 * most one fiber per incarnation into the commit window (status CAS +
 * tally==0 under the graph semaphore — ExecAdmit); every resubmission is a
 * freshIncarnation with new tally/unsubs/status/cascade refs (edges are
 * incarnation-tagged, and drains/clears against re-incarnated targets
 * no-op, modelling decrements of dead refs); triggerUnsub fires exactly
 * once per incarnation (cascadeFired).
 *
 * Commit outcomes are truthful, not nondeterministic: dirty iff a written
 * var's version moved since this fiber's snapshot. A nondeterministic
 * failure can strike the commit (before publish — a mid-publish throw is
 * inside the atomic-commit abstraction and out of scope here) or the
 * dispatch of a dirty resubmission — modelling `execute`'s handleErrorWith,
 * which runs registerCompletion a SECOND time and starts a SECOND
 * unsubscribe cascade — and a plain submission can abort, modelling the
 * submit wrapper's handleErrorWith in TxnRuntime.commit, which completes
 * the signal and leaves the transaction wherever it was.
 *)

EXTENDS Footprint, Integers, FiniteSets, Sequences

CONSTANTS
    MaxInc,          \* dirty-resubmission bound per txn (action-level
                     \* truncation: ExecResubTruncate retires the fiber)
    MaxVer,          \* version bound per var (CONSTRAINT backstop)
    AbortsEnabled    \* enable the nondeterministic failure injections

---------------------------------------------------------------------------
(* Scenario — edit this section to vary the workload (Phase 2 may split
   scenarios into their own modules). Runtime ids are arbitrary distinct
   naturals; hash-derived order in the real system makes every ordering
   class reachable (TxnStateEntity.runtimeId). *)
---------------------------------------------------------------------------

Txns == {"t1", "t2", "t3"}

V1 == [val |-> 1, par |-> NoParent]
V2 == [val |-> 2, par |-> NoParent]
VarIds == {V1, V2}

(* Actual access sets: what the transaction's log will really touch. *)
ActualFP(t) ==
    CASE t = "t1" -> FP({}, {V1})
      [] t = "t2" -> FP({}, {V1})
      [] t = "t3" -> FP({V1}, {V2})

(* Declared base footprints: t1 under-declares (models the static-analysis
   fallback in TxnRuntime.commit's handleErrorWith) — this is what lets t1
   and t2 run concurrently, forces a dirty commit, and opens the H4 window. *)
DeclaredBase(t) ==
    CASE t = "t1" -> EmptyFootprint
      [] t = "t2" -> FP({}, {V1})
      [] t = "t3" -> FP({V1}, {V2})

Writes(t) == ActualFP(t).updates
TxnRank(t) == CASE t = "t1" -> 1 [] t = "t2" -> 2 [] t = "t3" -> 3

---------------------------------------------------------------------------
(* State *)
---------------------------------------------------------------------------

VARIABLES
    \* --- per-txn protocol state (the AnalysedTxn refs; shared across
    \*     the H4 fix gives every incarnation FRESH tally/unsubs/status/
    \*     cascade refs — old cascades drain dead refs, modelled by
    \*     incarnation-tagged edges whose drains no-op on a mismatch) ---
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
    version          \* var id -> commit counter (values abstracted away)

txnVars    == <<status, tally, unsubs, hasDown, completedCount, inc, declared,
                publishedInc, doublePub, cascadeFired, droppedSpawn, snap>>
submitVars == <<submitPc, submitMode, scanPending, curTarget, curDir, curContained>>
schedVars  == <<active, graphSem, version>>
vars       == <<txnVars, submitVars, exec, unsubW, schedVars>>

ExecSlots  == Txns \X {"A", "B"}
UnsubSlots == Txns \X {"u1", "u2"}

NoExec  == [pc |-> "none", out |-> "none", fInc |-> 0]
NoUnsub == [ph |-> "none", pend |-> {}, ownerInc |-> 0]

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
                   unsubW, schedVars>>

SubmitReset2(t) ==
    /\ submitPc[t] = "reset2"
    /\ hasDown'  = [hasDown EXCEPT ![t] = FALSE]
    /\ submitPc' = [submitPc EXCEPT ![t] = "acq"]
    /\ UNCHANGED <<status, tally, unsubs, completedCount, inc, declared,
                   publishedInc, doublePub, cascadeFired, droppedSpawn, snap, submitMode,
                   scanPending, curTarget, curDir, curContained, exec,
                   unsubW, schedVars>>

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
                   exec, unsubW, active, version>>

(* executionStatus.set(Scheduled) *)
SubmitSetScheduled(t) ==
    /\ submitPc[t] = "setSched"
    /\ status'   = [status EXCEPT ![t] = "Scheduled"]
    /\ submitPc' = [submitPc EXCEPT ![t] = "insAct"]
    /\ UNCHANGED <<tally, unsubs, hasDown, completedCount, inc, declared,
                   publishedInc, doublePub, cascadeFired, droppedSpawn, snap, submitMode,
                   scanPending, curTarget, curDir, curContained, exec,
                   unsubW, schedVars>>

(* activeTransactions.addOne *)
SubmitInsertActive(t) ==
    /\ submitPc[t] = "insAct"
    /\ active'   = active \cup {t}
    /\ submitPc' = [submitPc EXCEPT ![t] = "scanPick"]
    /\ UNCHANGED <<txnVars, submitMode, scanPending, curTarget, curDir,
                   curContained, exec, unsubW, graphSem, version>>

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
                   schedVars>>

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
                   exec, unsubW, schedVars>>

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
                   curDir, exec, unsubW, schedVars>>

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
                   unsubW, schedVars>>

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
                   schedVars>>

(* permit release; checkRetryQueue is a no-op in Phase 1 —
   the retry map is empty by construction *)
SubmitRelease(t) ==
    /\ submitPc[t] = "rel"
    /\ graphSem = t
    /\ graphSem'  = "none"
    /\ submitPc'  = [submitPc EXCEPT ![t] = "idle"]
    /\ curTarget' = [curTarget EXCEPT ![t] = "none"]
    /\ UNCHANGED <<txnVars, submitMode, scanPending, curDir, curContained,
                   exec, unsubW, active, version>>

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
                   unsubW, active, version>>

---------------------------------------------------------------------------
(* Execute fibers — execute with the atomic-commit
   abstraction (plan §3). *)
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
                   snap, submitVars, unsubW, schedVars>>

(* Log construction abstracted to one snapshot of the footprint's versions,
   PER FIBER — each execute run builds a private log in the code, so
   concurrent fibers of one txn capture independent initial values. Spec A
   refines read-time granularity; Spec B needs the snapshot only to make
   the dirty outcome truthful. *)
ExecSnapshot(t, s) ==
    /\ exec[<<t, s>>].pc = "snap"
    /\ snap' = [snap EXCEPT ![<<t, s>>] = [v \in VarIds |-> version[v]]]
    /\ exec' = [exec EXCEPT ![<<t, s>>].pc = "commit"]
    /\ UNCHANGED <<status, tally, unsubs, hasDown, completedCount, inc,
                   declared, publishedInc, doublePub, cascadeFired,
                   droppedSpawn, submitVars, unsubW, schedVars>>

(* The atomic commit: dirty iff a written var moved since the snapshot
   (models the commit pipeline withLock{isDirty}/commit, at
   Spec B's granularity). Publish = bump every written var. *)
ExecCommit(t, s) ==
    /\ exec[<<t, s>>].pc = "commit"
    /\ LET dirtyNow == \E v \in Writes(t) : version[v] /= snap[<<t, s>>][v]
       IN IF dirtyNow
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
                   graphSem>>

(* Commit throws before publishing — poll(commit) raises, execute's
   handleErrorWith takes over: registerCompletion (first time) + complete.
   This same shape also covers the NORMAL TxnResultFailure dispatch (a
   TxnLogError log): one removal, one cascade, one Left completion. *)
ExecCommitFail(t, s) ==
    /\ AbortsEnabled
    /\ exec[<<t, s>>].pc = "commit"
    /\ exec' = [exec EXCEPT ![<<t, s>>] = [pc |-> "eRegComp", out |-> "fail", fInc |-> exec[<<t, s>>].fInc]]
    /\ UNCHANGED <<txnVars, submitVars, unsubW, schedVars>>

(* registerCompletion: semaphore-guarded remove (R3)... *)
ExecRegComplete(t, s) ==
    /\ exec[<<t, s>>].pc = "regComp"
    /\ graphSem = "none"
    /\ active' = active \ {t}
    /\ exec'   = [exec EXCEPT ![<<t, s>>].pc = "spawnU"]
    /\ UNCHANGED <<txnVars, submitVars, unsubW, graphSem, version>>

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
    /\ exec' = [exec EXCEPT ![<<t, s>>].pc =
                   IF exec[<<t, s>>].out = "success" THEN "complete"
                                                     ELSE "resubInit"]
    /\ UNCHANGED <<status, tally, unsubs, hasDown, completedCount, inc,
                   declared, publishedInc, doublePub, snap, submitVars,
                   active, graphSem, version>>

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
                   unsubW, schedVars>>

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
    /\ declared'     = [declared EXCEPT ![t] = Validated(ActualFP(t))]
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
    /\ submitMode'   = [submitMode EXCEPT ![t] = "imm"]
    /\ exec'         = [exec EXCEPT ![<<t, s>>].pc = "resubWait"]
    /\ UNCHANGED <<completedCount, doublePub, droppedSpawn, snap,
                   scanPending, curTarget, curDir, curContained, unsubW,
                   schedVars>>

(* A dirty fiber at the incarnation bound retires its slot: the code would
   resubmit unboundedly; the model truncates the behaviour here and frees
   the slot (leaving it pinned would compound the two-slot masking that
   droppedSpawn guards against). Safety-only truncation — and with deadlock
   detection enabled, a truncation that ever mattered would strand its
   transaction and surface as a deadlock; the clean run therefore also
   proves MaxInc never binds in this scenario (README sweeps section). *)
ExecResubTruncate(t, s) ==
    /\ exec[<<t, s>>].pc = "resubInit"
    /\ inc[t] >= MaxInc
    /\ exec' = [exec EXCEPT ![<<t, s>>] = NoExec]
    /\ UNCHANGED <<txnVars, submitVars, unsubW, schedVars>>

(* The imm submission ran to completion inside this fiber; execute returns. *)
ExecResubDone(t, s) ==
    /\ exec[<<t, s>>].pc = "resubWait"
    /\ submitPc[t] = "idle"
    /\ submitMode[t] = "imm"
    /\ exec' = [exec EXCEPT ![<<t, s>>] = NoExec]
    /\ UNCHANGED <<txnVars, submitVars, unsubW, schedVars>>

(* An exception inside the imm submission propagates to execute's
   handleErrorWith: registerCompletion runs a SECOND time
   and a SECOND triggerUnsub cascade is spawned over the SAME shared
   unsubSpecs. permit.use releases the semaphore on the way out. *)
ExecResubAbort(t, s) ==
    /\ AbortsEnabled
    /\ exec[<<t, s>>].pc = "resubWait"
    /\ submitPc[t] /= "idle"
    /\ graphSem' = IF graphSem = t THEN "none" ELSE graphSem
    /\ submitPc' = [submitPc EXCEPT ![t] = "idle"]
    /\ exec'     = [exec EXCEPT ![<<t, s>>] = [pc |-> "eRegComp", out |-> "fail", fInc |-> exec[<<t, s>>].fInc]]
    /\ UNCHANGED <<txnVars, submitMode, scanPending, curTarget, curDir,
                   curContained, unsubW, active, version>>

(* Error path: registerCompletion (possibly the second time — idempotent on
   the active set, NOT on the unsub cascade), then complete(Left). *)
ExecErrRegComplete(t, s) ==
    /\ exec[<<t, s>>].pc = "eRegComp"
    /\ graphSem = "none"
    /\ active' = active \ {t}
    /\ exec'   = [exec EXCEPT ![<<t, s>>].pc = "eSpawnU"]
    /\ UNCHANGED <<txnVars, submitVars, unsubW, graphSem, version>>

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
                   active, graphSem, version>>

ExecErrComplete(t, s) ==
    /\ exec[<<t, s>>].pc = "eComplete"
    /\ completedCount' = [completedCount EXCEPT ![t] = @ + 1]
    /\ exec'   = [exec EXCEPT ![<<t, s>>] = NoExec]
    /\ UNCHANGED <<status, tally, unsubs, hasDown, inc, declared,
                   publishedInc, doublePub, cascadeFired, droppedSpawn, snap, submitVars,
                   unsubW, schedVars>>

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
                   schedVars>>

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
                   snap, submitVars, exec, schedVars>>

---------------------------------------------------------------------------
(* Next *)
---------------------------------------------------------------------------

(* Legitimate terminal states stutter: every submitted transaction's
   completion signal has fired. Anything else with no enabled action is a
   REAL protocol deadlock, and TLC's deadlock detection (now enabled for
   organic runs — no -deadlock flag) reports it. *)
Terminating ==
    /\ \A t \in Txns : completedCount[t] >= 1
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
    \/ \E o \in Txns, u \in {"u1", "u2"} :
        \/ UnsubDrain(o, u) \/ UnsubClear(o, u)
    \/ Terminating

Spec == Init /\ [][Next]_vars

---------------------------------------------------------------------------
(* State constraint (finiteness backstop; plan §5) *)
---------------------------------------------------------------------------

StateConstraint ==
    \* inc needs no clause: ExecResubTruncate caps it at the action level.
    /\ \A t \in Txns : tally[t] >= -2 /\ tally[t] <= 4
    /\ \A v \in VarIds : version[v] <= MaxVer

---------------------------------------------------------------------------
(* Invariants (plan §5 property table) *)
---------------------------------------------------------------------------

TypeOK ==
    /\ status \in [Txns -> {"NotScheduled", "Scheduled", "Running"}]
    /\ graphSem \in Txns \cup {"none"}
    /\ active \subseteq Txns

(* Tallies must never go negative: a negative tally means an unsubscribe
   was delivered for an edge the tally no longer counts (H4 family). *)
TallyNonNegative == \A t \in Txns : tally[t] >= 0

(* Contract C — the interface property Spec A assumes (plan §3): no two
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


===============================================================================

