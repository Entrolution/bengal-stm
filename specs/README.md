# TLA+ Formal Specifications

Formal specifications of bengal-stm's transaction scheduling and commit
protocols, verified with the TLC model checker. The plan, architecture
rationale, and hypothesis list live in
[`docs/plans/archive/formal-specs.md`](../docs/plans/archive/formal-specs.md);
this README is the operational reference and the **source of truth for
verdicts**.

## What These Specs Verify

**Footprint compatibility relation** (`src/main/scala/bengal/stm/model/runtime/IdFootprint.scala`):

- The exact `isCompatibleWith` / `getValidated` semantics, encoded once in
  `common/Footprint.tla` and shared by every other spec
- Algebraic lemmas: symmetry, validation idempotence (the re-application
  half of the `isValidated` flag's soundness — the other half is call-site
  discipline, see the `IdFootprint.scala` anchor), validation monotonicity,
  writer self-incompatibility
- The full parent-hierarchy conflict matrix, including the **H5 fix**: a
  parent-structure READ now conflicts with a child-entry WRITE (whole-map
  read vs new-key insert) while parent-read vs child-read stays compatible —
  see the verdict table

**Scheduler protocol** (`src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala`):

- Submission and dependency-graph construction, tallies, statuses,
  unsubscribe cascades, the dirty-resubmission path, the
  `handleErrorWith` recovery branches, and the retry map / park / wake
  machinery (`TxnResultRetry`, `submitTxnForRetry`, `checkRetryQueue`)
- Six safety invariants over that machinery — all six **HOLD since the H4
  fix** (admission gate, fresh incarnations, exactly-once cascades),
  verified exhaustively with deadlock detection enabled; before the fix
  all six failed organically (see the verdict table)
- **Lost wakeups**, which no invariant states: a transaction parked forever
  is a dead-end state, and legitimate terminals stutter through the explicit
  `Terminating` action, so TLC reports the park as a **deadlock**. Both
  wakeup defects in this workstream — H1 and the absent-key one — surfaced
  that way, and both are pinned that way

**Commit protocol** (`src/main/scala/bengal/stm/runtime/TxnLogContext.scala`):

- The `withLock` / `isDirty` / `commit` sequence at lock-operation
  granularity: the log run, lock resolution (identity is state-dependent —
  a new-key map entry falls back to the map's structural lock), blocking
  acquisition in resolved order, the write-set-only dirty check, publish,
  release, and the dirty-resubmission refinement
- Contract C is **assumed** here and **checked** by Spec B — the
  assume-guarantee split is discharged, not aspirational
- **Three confirmed defects, all since fixed**: H2 (lock-order deadlock via
  the map-lock fallback — reachable on *accurate* footprints), H3 (write skew
  when the static-analysis fallback under-declares) and H6 (the walker
  declares the **wrong ids** because it raced the values it computed them
  from). All seven Spec A configs now verify exhaustively clean. What keeps
  those green verdicts worth having is the negative-control set below: revert
  any one fix and its counterexample comes straight back. See the verdict
  table

**They do not verify:**

- The free-monad compiler / static-analysis walker (its *output* is modelled
  — accurate vs under-approximated footprints are a Spec A mode — but its
  internals are not)
- `TxnLog` bookkeeping, value semantics (vars are version counters here)
- Cancellation, `TxnVarMap.internalStructureLock` (leaf lock below the
  modelled `commitLock`s). (Runtime-ID distinctness is no longer an
  assumption to scope out: every id — entity or map-key existential — is
  issued by the one global allocator (`TxnStateEntity.runtimeId`,
  `TxnVarMap.getRuntimeId`), so ids are unique by construction.)

## Specs

| Spec | What it models | Scala correspondence |
|------|----------------|----------------------|
| `common/Footprint.tla` | The footprint data model and compatibility relation (operators only) | `IdFootprint.scala`, `TxnVarRuntimeId.scala` |
| `common/FootprintLemmas.tla` | Exhaustive lemma checks over a 4-id universe (512 footprints, 262,144 ordered pairs) as named `ASSUME`s | `IdFootprint.scala` |
| `scheduler/Scheduler.tla` | Spec B: the scheduler protocol at Ref/TrieMap-operation granularity — scenarios select `Txns` per config (H4 safety set; retry/park/wake set) | `TxnRuntimeContext.scala` |
| `commit/CommitProtocol.tla` | Spec A: the commit protocol (log run, lock resolution/acquisition, dirty-check, publish, dirty refinement) under an assumed Contract C — scenarios select `Txns` and `UnderDeclared` per config | `TxnLogContext.scala`, `TxnRuntimeContext.scala` (`AnalysedTxn.commit`, `TxnRuntime.commit`) |

`scheduler/Footprint.tla` and `commit/Footprint.tla` are symlinks to
`common/Footprint.tla` — TLC resolves `EXTENDS` from the root module's
directory, and symlinks keep the relation single-sourced without library-path
flags. (Windows contributors need `git config core.symlinks true` on a
filesystem that supports them, or the links materialise as one-line text
files and TLC fails to parse; CI is Linux and unaffected.)

Spec comments cite Scala **symbol names**, not line numbers — line references
rot on every edit and `verify_anchors.sh` cannot guard them.

## Prerequisites

- **Java 11+** (tested with Java 21)
- **tla2tools.jar** in this directory (gitignored) — download from
  [TLA+ releases](https://github.com/tlaplus/tlaplus/releases):
  ```bash
  curl -sL -o specs/tla2tools.jar \
    https://github.com/tlaplus/tlaplus/releases/download/v1.8.0/tla2tools.jar
  ```
  Note: the `v1.8.0` tag is a **rolling pre-release** — it serves current
  nightly builds, so local and CI versions track each other but are not
  byte-stable over time.

## Running the Model Checker

From the repository root:

```bash
./specs/expectations.sh --list          # what is registered, and validate it. No TLC.
./specs/expectations.sh                 # model-check every push-gated expectation
./specs/expectations.sh --all           # ...including the dispatch-gated ones
./specs/expectations.sh --measure       # regenerate specs/measured.tsv
./specs/expectations.sh --check-readme  # the table below vs the measurement
./specs/verify_anchors.sh               # every // SPEC: anchor maps to a row here
```

**Each config declares its own expectation, and that is the only place it is
stated.** The directives live at the top of every `.cfg`:

```
\* @spec    specs/scheduler/Scheduler.tla
\* @expect  NONE | DEADLOCK | <InvariantName>
\* @flags   ALLOW_DEADLOCK        (optional)
\* @run     push | dispatch       (optional; default push)
\* @measure no                    (optional; opt out of state-space measurement)
```

CI globs the directory and derives its steps from those. That arrangement is
deliberate, and it is a response to how this suite actually failed.

The expectation used to live in **two** places — prose in the cfg header
("EXPECTED RED: `CommitSnapshotValid`") and an argument in
`.github/workflows/specs.yml` — with nothing tying them together. They drifted.
**Four cfg headers read EXPECTED RED for months while CI asserted `NONE`**, and
`check_expected.sh` could not see it, because all it ever does is compare TLC's
*output* to the argument it was handed. A cfg could also ship with no stated
verdict at all (`CommitH6` did), or exist and be run by nothing.

So three failure modes are now impossible rather than merely discouraged:

| Failure | Why it cannot happen |
|---|---|
| a config with no stated verdict | `--list` fails |
| a config that no CI step runs | CI globs `specs/*/*.cfg`; there is no list to forget |
| a header that disagrees with CI | there is only **one** declaration to disagree with |

`--list` also **rejects a config that states a verdict in prose**. Explain *why*
a config verifies as it does at any length you like; just do not restate *what*
it verifies as, because a restatement is a thing that can rot.

**State counts are regenerated, not remembered.** `specs/measured.tsv` is
produced by an actual run and committed; CI re-measures and fails on a diff, then
checks the table below against it. Nothing else would catch that class of rot —
the Scheduler config advertised "~24k distinct states" against a real **846,000**,
and the lemma check was documented at "~1s" against a real **39s**. Both sat there
for months, and both were eventually chased out of one file and left in another.

Only exhaustive (`@expect NONE`) configs are measured. A run that **halts on a
violation** explores a scheduling-dependent prefix, so its counts do not
reproduce — consecutive `-workers auto` runs of `SchedulerAbsentKey` gave 9,201
and 8,843. Pinning a number like that yields a guard that fails at random, which
is worse than no guard.

## Verdict Table (source of truth)

Scenario for all Scheduler rows: 3 txns / 2 vars, `t1` under-declared (empty
footprint — the static-analysis fallback in `TxnRuntime.commit`), `t2`
accurately declaring a write to the same var, `t3` accurately declaring a
read of it; `MaxInc=2`, `MaxVer=6`, no failure injection. This is the exact
scenario that produced the H4 counterexample family before the fix.

| Property | Verdict | Evidence |
|----------|---------|----------|
| `LemmaCompatSymmetric`, `LemmaValidatedIdempotent`, `LemmaValidatedPreservesUpdates`, `LemmaValidatedMonotoneCompat`, `LemmaWriterSelfIncompatible` | **HOLD** (exhaustive over the 4-id universe — now 512 footprints / 262,144 ordered pairs, since the under-approximation flag doubled it) | `FootprintLemmas.cfg`, clean |
| `LemmaUnderApproximatedIncompatibleWithAll`, `LemmaValidatedPreservesUnderApproximation`, `LemmaMergePropagatesUnderApproximation` — **the H3 fix, as algebra** | **HOLD.** An under-approximated footprint is incompatible with *every* footprint (the empty one, a pure reader, itself); `getValidated` must carry the flag through, or a validated footprint would silently regain the scheduler's trust — and since the runtime compares validated footprints everywhere, the fix would be a no-op; `mergeWith` ORs it, so a merge is only as trustworthy as its least trustworthy half. Plus `DocumentsUnknownIsNotTheSameAsEmpty` ("I don't know" ≠ "I touch nothing" — the exact conflation H3 exploited) and `DocumentsCompleteFootprintsStillCompatible` (the flag serializes nothing it shouldn't) | `FootprintLemmas.cfg`, clean |
| `DocumentsParentReadChildWriteCaught` — parent-structure read vs child-entry write now judged **incompatible** (plus `DocumentsParentReadChildReadCompatible`: pure readers stay compatible) | **HOLDS — H5 FIXED (2026-07-11)** by the relation's third conjunct; before the fix this pair was compatible (pinned then as `DocumentsReadGapH5`) | `FootprintLemmas.cfg` |
| **H5 phantom write skew — behavioural** (whole-map read + new-key insert) | **FIXED (2026-07-11)** — was CONFIRMED at ~98% of contended reps (both txns observed the empty map, both committed); the fixed relation serializes the pair and the regression test asserts every rep serializable | `SerializabilityOracleSpec` ("H5 phantom write skew … regression for the H5 fix") |
| `DocumentsSiblingInsertsCompatible` — two new-key inserts to one map compatible (H2 enabler) | **PINNED** (correct behaviour; the hazard is the lock aliasing, Spec A) | `FootprintLemmas.cfg` |
| `NoDoubleExec`, `ContractC`, `NoExecOnCompleted`, `NoDoublePublish`, `TallyNonNegative`, `CompletionAtMostOnce` | **HOLD — H4 FIXED (2026-07-11)** by the admission gate (`admitForExecution`: status CAS + zero tally under the graph semaphore), fresh bookkeeping per incarnation (`freshIncarnation`), and exactly-once cascades (`cascadeFired`). Exhaustive at the defect scenario's own bounds — 846k distinct states in 36.1 s, queue drained (it was 24k/~2 s when Phase 1 measured it, before the retry machinery joined the same module; the gate still collapsed the pre-fix >32M-state explosion, which never terminated at all). **Before the fix all six failed organically** at TLC search depths ~50–64 (`NoDoubleExec` was the CI pin; historical narrative below) | `Scheduler.cfg` (CI, expected clean) |
| **Deadlock freedom** (organic) | **HOLDS** — detection enabled, legitimate terminals modelled by `Terminating` | `Scheduler.cfg` |
| **H1 lost wakeup — park/submit window** | **CONFIRMED and FIXED (2026-07-11)**: pre-fix the retry config deadlocked — a conflictor's submission-time sweep ran against a retry map that did not yet contain the parker, the conflictor's commit then satisfied the parker's predicate, and wakes fire only from sweeps, so nothing ever woke it. The fix uses **two** guards inside the retry-semaphore region, before parking: scan `activeTransactions` for a footprint conflict, then re-check the read set (`TxnLogValid.anyReadChangedSinceRead`, which folds the per-entry `hasChangedSinceRead` over `log.values` — a real comparison, where the read-only `isDirty` is vacuous). The scan catches conflictors still running; the fold catches those that already committed and left. **The ORDER is load-bearing**: scan first, staleness last. The reverse — which a first draft shipped — still loses wakeups (a conflictor commits after the staleness read and leaves `activeTransactions` before the scan, escaping both) and TLC reports it as a deadlock; that is negative control NC-A below | `SchedulerRetry.cfg` (CI, expected clean) |
| **Absent-key lost wakeup — H1's window, with the second guard structurally blind** | **CONFIRMED and FIXED (2026-07-12).** Reading a map key that does not exist recorded **no log entry** — `getVarMapValue`'s key-absent branch returned the log untouched. But `anyReadChangedSinceRead` folds over `log.values`, and that fold **is** H1's second park guard, so on this path the guard could not see the read at all and half of the H1 soundness argument was gone. The read was in the **declared** footprint the whole time (the static-analysis walker registers the key's existential id whether or not the key exists), which is why Contract C and the wake sweeps all looked correct; only the log was missing it. Two id spaces again. **The model shared the blind spot**: `Scheduler.tla` computed park staleness over `ActualFP` — what a transaction *touches* — while the code computes it over the **log**, so the spec was checking a *stronger* guard than the protocol implements and verified clean on a defect it contained. `ActualFP` and `LoggedFP` are now separate operators, and `SchedulerAbsentKey.cfg` — `SchedulerRetry.cfg` with the parker's read unlogged and nothing else changed — **deadlocks**. **FIXED**: the key-absent branch now records a `TxnLogReadOnlyVarMapEntry` with `initial = None`, which puts the read where the fold can find it. **No behavioural test reaches this** — the window is H1's (see the coverage table) | `SchedulerAbsentKey.cfg` (CI, pinned RED — the guard's negative control); `SchedulerRetry.cfg` (CI, expected clean — the fixed path); `AbsentKeyParkSpec` (coverage floor only) |
| **Spurious self-wake spin** (found while verifying H1) | **FIXED (2026-07-11)**: a parked transaction is footprint-incompatible with *itself* (it writes), so its own in-flight submission sweep woke it, it re-ran, re-parked, and the next sweep could do it again — an unbounded spin under adversarial scheduling, pre-existing and orthogonal to H1. The retry map is now keyed by `TxnId` rather than footprint, and a sweep skips the submitting transaction (its own submission cannot satisfy its own predicate — it retried rather than committed). Keying by id also removes the old wake-chaining of distinct transactions that happened to share a footprint | `SchedulerRetry.cfg` (`BoundsNeverBind`); negative control NC-C |
| `NoDroppedSpawn` | **HOLDS post-fix** — the admission gate retires loser fibers fast enough that no spawn is dropped at these bounds (pre-fix the two-slot bound truncated behaviour from depth ~72) | `Scheduler.cfg` |
| `TypeOK` | holds on every completed run | all cfgs |

### Commit protocol (Spec A)

Scenarios are per-config (`Txns` + `UnderDeclared`). Every compatibility
judgement below is **computed** by `common/Footprint.tla`, never hand-assigned.

| Property | Verdict | Evidence |
|----------|---------|----------|
| **H2 — lock-order deadlock via the map-lock fallback** (`NoWaitsForCycle`) | **CONFIRMED and FIXED (2026-07-11).** Two transactions each inserting a **fresh key into two maps** have compatible footprints, so the scheduler deliberately runs them concurrently; each new-key entry falls back to its **map's** structural `commitLock`, so both hold `{M1.lock, M2.lock}`; and `withLock` sorted by the **log key**, which for an absent key was the existential id (hash-derived at the time) of `(mapId, key)` — a value with no relation to the lock it resolves to. Key-id order therefore inverted the acquisition order and both fibers blocked forever on `Semaphore.permit`, callers hung on `completionSignal.get`. **It needed no fallback, no under-declaration and no scheduler bug — it was reachable on fully ACCURATE footprints.** 17-state counterexample; the behavioural twin deadlocked and failed with a `TimeoutException`. **FIXED**: `TxnLogEntry.lock` now returns the lock paired with the runtime id of the entity that **owns** it, and `withLock` sorts on the owner. Owner → lock is injective, so one ascending order is a single global total order over locks and no circular wait can form. Post-fix the config verifies **exhaustively clean** (2,100 distinct states, depth 36) with deadlock detection on | `CommitH2.cfg` (CI, expected clean); `CommitLockOrderSpec` |
| **Lock aliasing** — two fresh keys inserted into ONE map (`dbl`) | **HOLDS.** Both entries resolve to that map's single structural `commitLock`, so `withLock`'s `.distinct` must collapse them into ONE acquisition — a 1-permit `Semaphore` taken twice by the same fiber self-deadlocks instantly. Nothing in the catalogue exercised the dedup before, and the H2 fix **changed what it dedupes on** (the `(owner id, Semaphore)` pair rather than the bare `Semaphore`), so it now has its own scenario and its own negative control (NC-5, which removes the dedup and produces the self-deadlock). Deduping on the pair rather than on the owner id alone is deliberate: it compares the `Semaphore` by reference — belt and braces now that owner ids are allocator-issued and unique by construction (at the time of the fix they were hash-derived, and the pair kept two colliding-id locks distinct) | `CommitH2.cfg`; `CommitLockOrderSpec` |
| **H3 — write skew when the fallback under-declares** (`CommitSnapshotValid`) | **CONFIRMED and FIXED (2026-07-11).** Reads are never commit-validated and hold no lock, so the scheduler's footprint conflict-avoidance is the only defence, and an under-approximated footprint switched it off. **It was unsound in BOTH directions:** the under-declared transaction read what a peer overwrote (`CommitH3.cfg`), *and* its undeclared writes invalidated a correctly-declared peer's reads (`CommitH3Writer.cfg` — that transaction has no reads at all and still broke its peer), which is why flagging only read-incompleteness would not have been enough. **It was reachable from ordinary code**: `staticAnalysisCompiler` executes real reads but never applies writes, so reading back a key the transaction just wrote yields `None` during analysis and `Some(v)` at run time; a partial continuation on it throws during analysis only. Measured **198/200 contended reps skewed** — the default outcome under contention, exactly as for H5 pre-fix. **FIXED**: `IdFootprint.isUnderApproximated` flags a footprint the walker could not complete, and the relation makes such a footprint **incompatible with everything**. The transaction is serialized against all others and runs alone; running alone, nothing can change under it, so its unvalidated reads are trivially safe. Post-fix: **0 skews in 1000 reps**, and all three configs verify clean | `CommitH3.cfg`, `CommitH3Partial.cfg`, `CommitH3Writer.cfg` (CI, expected clean); `StaticAnalysisFallbackSpec` |
| **H3 — which fallback branch actually fires** | `TxnRuntime.commit`'s `handleErrorWith` has **two** under-approximating branches and it mattered which. `case _ => IdFootprint.empty` is the *extreme* point; the demonstrated defect took the **other** one — the walker's own handler converts the throw into `StaticAnalysisShortCircuitException(s)` carrying the **partial** footprint, and the runtime scheduled on *that*. **Both are now flagged**, and so are the three handlers in `TxnCompilerContext` that swallow a failed key thunk and carry on with a silently incomplete footprint — those never reached the top-level handler at all, so nothing downstream could have known. `CommitH3Partial.cfg` models the partial case and verifies clean alongside the empty one: under-approximation is unsound whatever its **size**, so the flag, not the content, is what counts | `CommitH3Partial.cfg` (CI, expected clean) |
| **H6 — data-dependent footprint divergence** (`CommitSnapshotValid`) | **CONFIRMED and FIXED (2026-07-12).** The hole the H3 fix could not reach. `staticAnalysisCompiler` runs in `TxnRuntime.commit` **before** `submitTxn` — outside `activeTransactions`, outside any Contract-C window — and computes the access set from **live reads**. So a transaction whose keys or targets depend on a value it read can be scheduled on a footprint naming **the wrong ids**: read a key from a var, have a peer change that var in the gap before scheduling, and the run touches an entry nobody declared. **Nothing throws**, so the H3 flag never fires and the scheduler simply trusts it. Same mechanism as H3, same invariant — an inaccurate declared footprint is an inaccurate declared footprint, whether it got that way by throwing or by racing. **FIXED**: a commit-time **coverage check**. Under the locks and *before publishing*, ask whether the declared footprint COVERS what the run actually touched; if not, refine from the actual log and re-run. Checking before the publish is what makes it work in both directions — our undeclared write never lands, so a peer's unvalidated read of it stays valid, and our own undeclared read never reaches a caller. **Behaviourally confirmed too, and deterministically**: `DataDependentFootprintSpec` suspends the *analysis pass itself* mid-flight (`staticAnalysisCompiler` executes `TxnDelay` thunks, so `STM[F].fromF` is a lever on it), flips the key-source vars while the transaction is not yet in `activeTransactions`, and lets the walker finish declaring the wrong entries. A transfer that preserves `a + b = 0` is then judged compatible with that wrong footprint, slips between the reader's two reads, and the reader sums a **torn** view. Pre-fix: **20/20 reps** observed 1 instead of 0. Post-fix: 0/20 | `CommitH6.cfg` (CI, expected clean); `DataDependentFootprintSpec` |
| **Coverage is not a subset test** | `IdFootprint.covers` asks whether declaring one footprint excludes at least as much concurrency as declaring another would. A subset test on raw ids would be **unusable**: a whole-map read legitimately expands, in the log, into a read-only entry for *every existing key*, so the actual footprint properly contains ids the walker never named — and a subset test would abort every whole-map read in the library. Those ids *are* covered, because a parent read conflicts with any child write via the relation's third conjunct. Note the asymmetry: a parent **read** covers a child **read**, but only a parent **write** covers a child **write**. `LemmaCoverageIsSound` checks all of that exhaustively over every ordered *triple* of **complete** footprints (256³ of them). That is one step short of proven, and it is worth naming which step: the quantifier is restricted to `under = FALSE`, and the restriction is justified in a comment rather than by TLC. Two thirds of that justification rest on a machine-checked lemma — an under-approximated `declared`, or an under-approximated peer, makes `IsCompatible` false and discharges the triple vacuously (`LemmaUnderApproximatedIncompatibleWithAll`). The remaining third is a claim about the Scala: `actual` is a **log** footprint, and a log footprint is complete by construction because it is merged from real log entries. Nothing in TLC checks that last sentence | `FootprintLemmas.cfg` |
| **The dirty-refinement path, after the fix** | **HOLDS — and had to be rebuilt to stay meaningful.** `CommitDirty.cfg` used to show two *under-declared* transactions colliding on the write set and the loser refining. The H3 fix makes that unreachable: under-approximated transactions can no longer overlap, so neither ever goes dirty, and the config would have passed **vacuously** — exactly what negative control NC-2 predicted before the fix was written (`DirtyRestart` fires 2× at baseline, **0×** under NC-2). It now uses `ddw`, a transaction whose declared footprint diverges from its actual one **without the walker throwing** — the data-dependent divergence that is H6. Post-fix that is the **only** remaining source of declared/actual divergence, and therefore the only thing that can still drive the dirty path. Verified non-vacuous: `DirtyRestart` fires | `CommitDirty.cfg` (CI, expected clean) |
| **H5 — model-side confirmation of the fix** (`CommitSnapshotValid`, accurate mode) | **HOLDS.** The H5 idiom (whole-map read + new-key insert) is now judged **incompatible** by the relation's third conjunct, so Contract C serializes the pair and no phantom arises. Independent of the relation-level lemma and of the oracle test — the same conclusion reached from the commit protocol instead. **Negative control NC-3 removes the third conjunct and this config goes red**, so the check is load-bearing rather than incidentally satisfied | `CommitAccurate.cfg` (CI, expected clean) |
| `NoLocksWithoutWrites` — a pure reader's log resolves **zero** locks and can never report dirty | **HOLDS** (fidelity pin). Read-only entries return `lock = None` and `isDirty = pure(false)`. This is why the `TxnLogRetry` re-validation before a park is vacuous, and why the H1 fix needed `hasChangedSinceRead` rather than `isDirty`. Pinned so the model can never quietly "improve" the protocol by validating reads | `CommitAccurate.cfg` (the `ro` transaction) |
| `LockOwnerStable` — the owner id the sort key rests on cannot go stale underneath it | **HOLDS** on all seven configs. The H2 fix sorts by the id of the entity that **owns** each lock, and for a map entry that owner is **state-dependent**: the entry's own lock once the key exists, the map's structural lock while it does not. So the ascending order is a total order only if key existence cannot flip between the `resolve` that reads it and the `acquire` that acts on it. Contract C is why it cannot — inserting or deleting that key means *writing* it, which is raw-id overlap with our own write, hence incompatible, hence out of our window. That argument is sound, and it is machine-checked here instead of assumed: let a future scenario break it (a fallback config that lets two transactions write one map entry, say) and this goes red, rather than the model quietly sorting on a key the code never consults. Scoped to `resolve` and `acquire`, the only phases that read the owner id — past them our own publish flips `keyExists` for our own writes, which is not interference | all `commit/` cfgs |
| `ContractCHolds` | **HOLDS** on every config — but read it as a *guard pin*, not a verified protocol property: it restates `TxnEnter`'s guard, so it goes red only if a future edit weakens the guard and silently widens the model's concurrency. It is **trivially true in fallback mode**, where the declared footprints are empty and empty is compatible with everything. The property itself is *earned* by Spec B, which checks `ContractC` against the real scheduler | all `commit/` cfgs |
| `TypeOK`, `LocksHeldConsistent`, `BoundsNeverBind`, `PublishedExactlyOnce` | **HOLD** on every config — model sanity checks, not protocol results. `LocksHeldConsistent` earned its keep: it caught a real model bug (a release path that freed the locks but left the acquired-prefix bookkeeping stale). `PublishedExactlyOnce` is **structurally unfalsifiable here** — `pubCount` increments only in `Publish`, which is the sole exit from `validate` to `release`, and nothing re-enters. The real once-per-incarnation guarantee is Spec B's `NoDoublePublish`, which *can* fail and did, pre-H4-fix. These four, together with `NoWaitsForCycle`, `CommitSnapshotValid`, `NoLocksWithoutWrites`, `LockOwnerStable` and `ContractCHolds` above, are **every** invariant the seven commit cfgs assert (`grep '^INVARIANT' specs/commit/*.cfg`) — the table has no gaps | all `commit/` cfgs |

**The central design fact, now machine-checked.** Removing *only* the Contract C
guard — accurate footprints, the H5-fixed relation, and every lock and dirty
check left intact — makes `CommitSnapshotValid` fail (negative control NC-4).
So the commit protocol **alone does not give serializability**: the scheduler is
load-bearing, and footprint accuracy is a safety precondition rather than a
throughput knob. That is the premise the whole two-spec decomposition rests on,
and it is no longer merely argued.

**The atomic-commit abstraction is discharged by the H5 fix.** Spec B models
commit as one atomic step (plan §3's refinement obligation). Spec A justifies
that: under Contract C in accurate mode, no concurrent transaction can read *or*
write any entity in a committing transaction's write set — a reader or writer of
a written entity has raw-id overlap, and a reader of a written entity's **parent**
is caught by the relation's third conjunct. That third conjunct **is** the H5 fix.
Before it, a whole-map reader could observe a partially-published write set, so
the two-spec decomposition itself rested on a fix that had not yet been made.

**H4 counterexample narrative — HISTORICAL, fixed 2026-07-11** (this is the
defect the admission gate / fresh incarnations / cascade gate eliminated;
validated line-by-line against the pre-fix code; minimal 49-state trace at
TLC search depth ~50 under the pre-fix protocol):

1. `t1` (empty declared footprint) and `t2` (writes `V1`) are judged
   compatible — under-declaration hides the conflict — and run concurrently.
   Both actually write `V1`; `t1` publishes first; `t2`'s commit validation
   (`isDirty` under `withLock`) finds `V1` moved and returns dirty.
2. `t2`'s completion fires `triggerUnsub.start` — fire-and-forget, inside
   `registerCompletion` — then resubmits itself via
   `submitTxnForImmediateRetry` with a `this.copy` that **shares
   `unsubSpecs` and `dependencyTally`** across incarnations.
3. Meanwhile `t3` submitted via `submitTxn`, saw an empty active set, and
   its `checkExecutionReadiness` **already spawned its execute fiber**, now
   parked at `registerRunning` waiting for the graph semaphore.
4. `t2`'s resubmission scan sees `t3` as `Scheduled` and adds the reversed
   dependency edge (`subscribeDownstreamDependency`: `tally[t3] += 1`,
   `unsubs[t2] += t3`) — the edge assumes `t3` has not started, but its
   fiber is already in flight and `registerRunning` re-checks nothing.
5. `t2`'s **old** cascade (step 2) now reads the **new** incarnation's edge
   out of the shared `unsubSpecs` and drains it
   (`unsubscribeUpstreamDependency`): `tally[t3]` 1→0, old == 1 spawns a
   **second** execute fiber for `t3`.
6. Nothing dedupes execute fibers → `t3` executes twice: `NoDoubleExec`
   (both fibers in the commit window), then downstream `NoDoublePublish`
   (the log commits twice — double effect application for non-idempotent
   transactions), `CompletionAtMostOnce` (second completion),
   `NoExecOnCompleted`, `TallyNonNegative` (stale drains under-run the
   reset tally), and `ContractC` (a stale fiber in-window alongside an
   incompatible peer).

**Negative controls (the model is a detector, not a rubber stamp).** Each
break is applied to a scratch copy of `Scheduler.tla` and run against
`SchedulerRetry.cfg`:

| # | Break | Result |
|---|-------|--------|
| NC-A | Swap the park checks (staleness before the active scan) | **Deadlock** — the ordering defect |
| NC-B | Remove both park checks | **Deadlock** — the original H1 defect |
| NC-C | Remove the sweep's self-skip | **`BoundsNeverBind` violated** — the spurious spin |

**Negative controls — Spec A.** Each break is applied to a scratch copy of
whichever module owns the thing being broken: `CommitProtocol.tla` for NC-1,
NC-4, NC-5 and NC-6; `common/Footprint.tla` for NC-2, NC-3 and NC-7, which
edit `IsCompatible`, the relation's third conjunct and `Covers` respectively.
NC-1 and NC-2 began as **candidate fixes**, run against the model before a
line of Scala moved. The Scala has since shipped, so both now read as
**reverts**: break the fix, watch the counterexample come back.

| # | Break / fix applied | Result |
|---|---------------------|--------|
| NC-1 | **Revert the H2 fix**: sort by the **log key** again instead of the lock's owner id | `CommitH2.cfg` goes **red** (`NoWaitsForCycle`) — the model still detects H2, so the post-fix clean verdict means the fix works rather than that the model stopped looking |
| NC-6 | Remove the commit-time coverage check | `CommitH6.cfg` goes **red** (`CommitSnapshotValid`) — the model still detects H6 |
| NC-7 | Break `Covers` so a declared write "covers" a **sibling** entry of the same map (H6's exact mechanism) | `LemmaCoverageIsSound` **fails** — the lemma specifically rejects H6, rather than being satisfiable by any plausible-looking definition |
| **NC-8** | **Delete the commit-time dirty check** | It was deleted. It could never fire. See below. |

**NC-8 is the one to read, and it ended in a deletion.** Every other control above turns a green
run red, which is what makes the greens worth having. This one never could — and a control that
cannot fire is usually a hole in the suite. Here it was a result about the protocol.

Since the H6 fix, `DirtyRestart` fired **0:0** in six of the seven commit configs and `0:1` in
`CommitDirty`. Across the whole of Spec A the commit-time dirty check caused one transition and
produced no new states, and deleting it changed no verdict. But staying green cannot distinguish
*"redundant"* from *"never exercised"*, so instead of sampling, the strong claim was asserted:

> **`CoverageSubsumesDirty`** (`CommitProtocol.tla`, asserted by all seven configs)
> **If the declared footprint covers the actual one, the write set cannot have moved underneath us.**

It holds, and the argument it encodes is:

1. For my write set to move, some peer must **publish** to an entity `e` that I write.
2. Since the H6 fix, a peer publishes only if **its** coverage holds — so `e`, or `e`'s parent,
   is in **its** declared updates.
3. If **my** coverage holds, `e` or its parent is in **my** declared updates.
4. Two footprints that both declare a write to the same entity are **incompatible** —
   `LemmaCoWriteImpliesIncompatible`, checked **exhaustively over every complete footprint pair**
   in `FootprintLemmas.tla`.
5. Contract C therefore keeps that peer out of my execute window entirely. It cannot have
   published during it. **My write set cannot have moved.**

Step 4 is the load-bearing one and it is a claim about the *relation*, so it is settled
exhaustively rather than argued. Steps 1–3 are the H6 fix and the definition of `Covers`. Step 5
is Contract C.

**The invariant is neither vacuous nor trivial**, and both were checked. `Publish` fired 10× in
`CommitDirty`, so states satisfying the antecedent were reached. And `DirtyRestart` fired — a
state where `IsDirty` was **true** at `validate` genuinely existed — in which the invariant
*required* that coverage had also failed. It had. **The dirty check never once caught anything
the coverage check did not.**

So it is gone, from the model and from the code. `NeedsRefinement` is now just `~CoverageOk`, and
`IsDirty` survives in the spec **only as a ghost**, so that `CoverageSubsumesDirty` can keep
asserting the property that licenses its absence. Every push re-checks it. If it ever breaks, the
check was not redundant after all and the code needs it back.

**What the deletion costs, and what pays for it.** The dirty check was the last thing standing
between a Contract-C violation and a silently lost update. That is a real backstop to give up,
and the proof above only licenses it *because Contract C holds* — which is the scheduler's
obligation, discharged in a model at bounds. A model can hold while code drifts; this project has
had to write that sentence about six separate defects.

So Contract C is now pinned in **three** places, and all three are load-bearing:

| | |
|---|---|
| `specs/scheduler/Scheduler.tla` | checks `ContractC` exhaustively |
| `// SPEC: ContractC` | anchors it to the submit scan and the admission gate |
| **`src/test/scala/spec/ContractCSpec.scala`** | **checks the RUNNING code upholds it** |

The last of those is new, and it is what made the deletion safe rather than brave. It runs eight
transactions whose declared footprints are pairwise incompatible and asserts that **no two are
ever in their bodies at once**, with an anti-vacuity arm proving the same meter *does* see
compatible transactions overlapping. Disable `isCompatibleWith` and it fails, reporting peaks of
2–4. That is the backstop the dirty check used to be, moved from the commit path — where it cost
every transaction a fiber fork, a `Deferred` and a cancel — into a test, where it costs nothing
and says what it means.

## Anchor Cross-Reference

Every anchor is a `// SPEC: <Name>` comment at the code site that realises the
named property. `specs/verify_anchors.sh` parses the tables below, greps the
declared file for the anchor, and fails CI if one goes missing. It also runs
the **reverse** pass: an anchor in `src/` with no row here is an error too, so
neither table nor code can drift alone. Anchors cite Scala **symbol names**;
line numbers rot and the script cannot guard them.

Column 1 is the anchor *name*, and a name is not always a TLA+ invariant —
hence column 3, which says how TLC actually checks the thing. Most are
`INVARIANT`s listed in a `.cfg`. The footprint properties are named `ASSUME`s
in `FootprintLemmas.tla`. **`NoLostWakeup` is neither**: no `.tla` module
declares it, and `grep -rn NoLostWakeup specs/` returns only this file. A lost
wakeup is a transaction parked with nothing left to wake it — a dead-end state
— so TLC finds it by **deadlock detection**, and `NoLostWakeup` is a label for
the property rather than a reference to a formula. Naming it like an invariant
was misleading, and the column heading now says so.

Every anchor below sits at a **fix site**. No expected-red rows remain.

### Scheduler protocol (anchors in `TxnRuntimeContext.scala`, `TxnLogContext.scala`)

| Anchor | Source site | How TLC checks it | Status at anchor |
|--------|-------------|-------------------|------------------|
| `NoDoubleExec` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | `INVARIANT` | spawn sites stay unguarded by design; `admitForExecution` admits at most one fiber per incarnation — HOLDS |
| `TallyNonNegative` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | `INVARIANT` | the `getAndUpdate(_ - 1)` decrement is the only tally sink; fresh per-incarnation edges + exactly-once cascades pair every decrement with one increment — HOLDS |
| `NoExecOnCompleted` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | `INVARIANT` | `admitForExecution`'s status CAS rejects fibers for completed incarnations — HOLDS |
| `ContractC` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | `INVARIANT` | the submit scan builds edges; the admission gate makes them binding — HOLDS |
| `CompletionAtMostOnce` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | `INVARIANT` | completion sites (success, error handler, submit wrapper); one execute window per incarnation — HOLDS organically |
| `NoDoublePublish` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | `INVARIANT` | `log.commit` publishes; at most one admitted fiber per incarnation — HOLDS |
| `NoLostWakeup` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | **deadlock detection**, `SchedulerRetry.cfg` | the H1 fix. `submitTxnForRetry` scans `activeTransactions` for a conflictor, then re-checks the read set, both inside the retry-semaphore region before parking — and in that order. The retry config verifies deadlock-free |
| `NoLostWakeup` | `src/main/scala/bengal/stm/runtime/TxnLogContext.scala` | **deadlock detection**, and pinned RED by `SchedulerAbsentKey.cfg` | the absent-key fix. `getVarMapValue`'s key-absent branch now records a read-only entry instead of returning the log untouched, which is what puts the read where `anyReadChangedSinceRead` — a fold over `log.values`, and H1's second guard — can see it |

### Footprint relation (anchors in `src/main/scala/bengal/stm/model/runtime/IdFootprint.scala`)

| Anchor | Source site | How TLC checks it | Status at anchor |
|--------|-------------|-------------------|------------------|
| `LemmaValidatedIdempotent` | `src/main/scala/bengal/stm/model/runtime/IdFootprint.scala` | named `ASSUME` in `FootprintLemmas.tla` | one `getValidated` application is a fixpoint — HOLDS, which is what makes the memoisation flag sound |
| `DocumentsParentReadChildWriteCaught` | `src/main/scala/bengal/stm/model/runtime/IdFootprint.scala` | named `ASSUME` in `FootprintLemmas.tla` | the relation's third conjunct conflicts child writes with parent reads — the H5 fix |
| `LemmaCoverageIsSound` | `src/main/scala/bengal/stm/model/runtime/IdFootprint.scala` (`covers`) | named `ASSUME` in `FootprintLemmas.tla`, exhaustive over ordered **triples** of complete footprints | coverage implies Contract C carries from the declared footprint to the actual one — HOLDS, and it is the load-bearing half of the H6 fix. The other half is not checked here: `covers` is only sound if `actual` is a log footprint and therefore complete by construction, which is a claim about the Scala, not the model |

### Commit protocol (anchors in `TxnLogContext.scala` / `TxnRuntimeContext.scala` / `IdFootprint.scala`)

`CommitSnapshotValid` carries **five** anchors across two fixes, because both
fixes have a producer half and a relation half and the flag or the check is
only sound if every producer sets it.

| Anchor | Source site | How TLC checks it | Status at anchor |
|--------|-------------|-------------------|------------------|
| `NoWaitsForCycle` | `src/main/scala/bengal/stm/runtime/TxnLogContext.scala` | `INVARIANT` | `withLock` sorts by the id of the entity that OWNS each lock, carried alongside the lock by `TxnLogEntry.lock`. Owner → lock is injective, so this is a single global total order over locks — the H2 fix. HOLDS |
| `NoLocksWithoutWrites` | `src/main/scala/bengal/stm/runtime/TxnLogContext.scala` | `INVARIANT` | read-only entries return `lock = None` and `isDirty = pure(false)` — commit validation and commit locks cover the write set only — HOLDS (and is why serializability rests on the scheduler) |
| `CommitSnapshotValid` | `src/main/scala/bengal/stm/model/runtime/IdFootprint.scala` | `INVARIANT` | **H3, relation half.** The `isUnderApproximated` flag and the first clause of `isCompatibleWith`: a footprint the walker could not complete is incompatible with everything, so the transaction serializes against all others and runs alone — HOLDS |
| `CommitSnapshotValid` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | `INVARIANT` | **H3, producer half.** `TxnRuntime.commit`'s `handleErrorWith` sets the flag on **both** of its under-approximating branches — the partial footprint the walker's own handler carries out, and the empty fallback — because a flag only one producer sets is a flag that does not hold |
| `CommitSnapshotValid` | `src/main/scala/bengal/stm/model/runtime/IdFootprint.scala` | `INVARIANT` | **H6, relation half.** `IdFootprint.covers`: does declaring THIS footprint exclude at least as much concurrency as declaring the actual one would? A hierarchy test, not a subset test — see the verdict row |
| `CommitSnapshotValid` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | `INVARIANT` | **H6, producer half.** The commit-time coverage check: under the locks and *before* publishing, compare the declared footprint against the log's actual one and refine-and-rerun if it does not cover. Checking before the publish is what makes an undeclared write never land |

## Variable Mapping (Scheduler.tla)

| TLA+ variable | Scala |
|---------------|-------|
| `status[t]` | `AnalysedTxn.executionStatus: Ref[F, ExecutionStatus]` — never demoted, exactly as in the code (nothing writes it after `Running` except a resubmission's `set(Scheduled)`); completion is visible only via `completedCount` |
| `tally[t]` | `AnalysedTxn.dependencyTally: Ref[F, Int]` — fresh per incarnation since the H4 fix |
| `unsubs[t]` | `AnalysedTxn.unsubSpecs: MutableMap[TxnId, F[Unit]]` — fresh per incarnation since the H4 fix; modelled as `<<downstreamId, downstreamIncAtSubscribe>>` pairs so drains against re-incarnated targets no-op (dead refs) |
| `cascadeFired[t]` | `AnalysedTxn.cascadeFired: Ref[F, Boolean]` — `triggerUnsub`'s exactly-once gate, fresh per incarnation |
| `exec[t, slot].fInc` | the incarnation an execute fiber was spawned for (in code: the fiber's closure over one `AnalysedTxn`); admission compares it implicitly by gating on that incarnation's own status ref |
| `hasDown[t]` | `AnalysedTxn.hasDownstream: Ref[F, Boolean]` |
| `completedCount[t]` | completions of `AnalysedTxn.completionSignal: Deferred` (counted to detect doubles; the real `Deferred` is first-wins) |
| `inc[t]`, `declared[t]` | incarnation ordinal and that incarnation's validated footprint (`idFootprint`, refined on the dirty path) |
| `snap[t, slot]`, `version[v]` | abstraction of log-entry `initial` captures (per execute fiber — each run builds a private log) and committed var state (values → version counters) |
| `submitPc[t]`, `scanPending[t]`, `curTarget/curDir/curContained` | position inside `submitTxn` / `submitTxnForImmediateRetry` and the scan loop |
| `exec[t, slot]` | in-flight `execute` fibers (two slots — double-spawn must be representable) |
| `unsubW[t, slot]` | in-flight `triggerUnsub` cascades (two slots — the error path spawns a second) |
| `active` | `TxnScheduler.activeTransactions` keys |
| `parked`, `retrySem`, `sweep[t, slot]`, `parkChk[t, slot]` | `retryMap` membership (keyed by `TxnId`; compat computed from `declared`), the `retrySemaphore` holder, in-flight `checkRetryQueue` fibers (three slots per transaction — one per possible submission at `MaxInc=2`; `droppedSweep` ghosts any drop), and the park region's per-fiber check results |
| `graphSem` | `graphBuilderSemaphore` holder |
| `ActualFP(t)` vs `LoggedFP(t)` | not variables but the distinction that hid the absent-key wakeup: what a transaction **touches** vs what its **log records**. They differ on exactly one operation — reading an absent map key — and every fold over `log.values` sees only the second. `anyReadChangedSinceRead` (the park-time staleness check) and `TxnLogValid.idFootprint` (the dirty path's refinement source) are both such folds |

## Variable Mapping (CommitProtocol.tla)

| TLA+ variable | Scala |
|---------------|-------|
| `pc[t]` | position in `AnalysedTxn.commit`: log run → resolve → acquire → validate → publish/refine → release |
| `version[e]` | commit counter per `TxnVar` / map entry / map structure (values abstracted away). A map **structure**'s version bumps whenever any of its entries is written — faithful to `TxnVarMap.get` returning the whole `K -> V` map |
| `keyExists[e]` | live key presence in the map — what `TxnVarMap.getTxnVar(key)` returns, and therefore what `TxnLogUpdateVarMapEntry.lock` branches on |
| `snapVer[t]`, `snapEx[t]` | each log entry's `initial`, captured when the entry was first built during the log run. `snapEx` is the `Some`/`None` distinction that `isDirty` branches on for a new-key insert |
| `writeSeq[t]` | `log.toList.sortBy(_._1.value)`, restricted to lock-contributing (update) entries — sorted by the **LOG KEY** |
| `lockSeq[t]`, `lockIdx[t]` | `locks.flatten.distinct` and how far the `Resource` fold has got. `.distinct` keeps first occurrences, which is what lets two entries aliasing to one map lock acquire it once instead of self-deadlocking a 1-permit `Semaphore` |
| `lockHolder[l]` | `commitLock: Semaphore[F]` holders, per `TxnVar` and per `TxnVarMap` |
| `declared[t]` | the incarnation's declared footprint — `ActualFP(t)` in accurate mode, `EmptyFootprint` for `t \in UnderDeclared`, and refined to the actual log's footprint on a dirty restart (`freshIncarnation(refinement.getValidated)`) |
| `readValid[t]` | ghost: were this transaction's reads still at their snapshot **at its publish**? (`CommitSnapshotValid`) |
| `pubCount[t]`, `truncated` | ghosts: publishes per transaction; whether `MaxInc` ever bound (a clipped horizon must never pass for a clean verdict) |

**Two id spaces.** A map entry has *two* runtime ids and the code uses
different ones for different jobs: the **existential** id, allocated from the
global counter on the key's first reference (a per-map registry keyed by the
key's own equality) with the map as its parent, is what the **footprint**
always uses; the entry `TxnVar`'s **own** id — its fresh sequential `TxnVarId`
minted at insert time, used directly — is what the **log** keys an entry by
once the key exists. `withLock` sorts by the lock's owner id. That decoupling
is H2.

**Scenario-catalogue precondition — read before adding a transaction.**
`ActualFP(t)` does triple duty: the accurately-declared footprint, the
footprint a dirty restart refines to, and (via `Writes(t)`) the entries that
resolve a lock. In the *code* those are three different objects, and three
operations make them diverge:

- **`setVarMap`** declares only the map's **structure** id, but its log holds a
  structure entry **plus a per-key entry for every key** — so the real
  transaction takes the map's lock *and every existing entry's lock*, where the
  model would give it the map lock alone. A config using `setVarMap` would get a
  **wrong lock set**, corrupting exactly the H2-family analysis the spec exists
  for.
- **`getVarMap`** on a non-empty map injects a read-only entry per existing key,
  so the log's reads strictly exceed the static footprint's.
- **`getVarMapValue` on an absent key** creates *no* log entry, while the static
  analyser *does* record the key's existential read id.

Every catalogue transaction is chosen so that static footprint = log footprint =
write set. That holds because both maps start **empty** and nothing calls
`setVarMap`. Adding a transaction that breaks any of those conditions requires
splitting `ActualFP` into three operators **first** — do not just add the row.
This is also why the new-key branch of `EntryDirty` (`initial = None` ⇒ dirty iff
the key exists *now*) is faithful but **never exercised**: firing it needs two
transactions writing the same entry id, which is raw-id overlap, hence
Contract-C-serialized. It is reachable only by combining maps *with*
under-declaration, which no config does yet. Deletes are likewise unmodelled, so
insert-then-delete ABA is not representable. Both are the obvious next scenarios.

## Atomicity Mapping & Reductions

One model step = one operation on one shared mutable object: each `Ref`
`get`/`set`/`update`/`getAndUpdate`, each `TrieMap` read/insert/remove.
Check-then-act sequences are two steps (the `unsubSpecs` contains-check is
deliberately separate from the subscribe writes — that gap is H4's home).
Semaphore-guarded regions are *not* atomic: unsubscribe-cascade steps
interleave with in-region steps. Documented reductions:

| # | Reduction | Justification |
|---|-----------|---------------|
| R1 | Scan fibers → sequential loop, fixed order | inter-fiber steps touch disjoint cells apart from monotone tally increments; external interleavings preserved between every pair of loop steps |
| R2 | Subscribe triple (tally++, insert, hasDown) → one step | cascades drain a frozen edge snapshot and `clear()` a map that receives no further semaphore-serialized inserts, so every torn-triple interleaving coincides observably with an atomic placement of the triple relative to cascade steps; the contains-check stays separate (that gap was H4's home) |
| R3 | acquire/act/release → one guarded step for short semaphore regions (`admitForExecution`, the active-set remove) | for the remove, nothing else runs under the held semaphore; for admission (tally read + status CAS, two operations) the collapse is sound because all status writes are themselves semaphore-guarded and a tally decrement landing between the two would require an unpaired drain, excluded by fresh refs + exactly-once cascades |
| R4 | Static analysis, `AnalysedTxn` construction, `Deferred` creation → `Init` | pre-protocol and **read-only**. Note the correction that Spec A's A7 spells out: the static-analysis walker *does* touch shared state (it performs live `txnVar.get` / `txnVarMap.get` reads, before `submitTxn` and hence outside any Contract-C window). It is safe to fold into `Init` because it never *writes*; the values it reads can still shift under it, and that shift is H6 |
| R5 | Concurrent `submitTxnForImmediateRetry` runs of one txn → serialized (one submit workflow per txn) | post-H4-fix this is **provably vacuous** rather than merely accepted: two dirty fibers for one txn would need two admitted fibers in one incarnation, which the admission gate excludes (`NoDoubleExec` HOLDS) |
| R6 | Cascade spawns with its edge set preloaded (gate + nonEmpty + values collapse) | the `cascadeFired` gate serializes cascades per incarnation and the owner left `activeTransactions` before the spawn, so the drained map is frozen — pre-fix these steps were kept separate precisely because double-spawned cascades could race a `clear()` |
| — | Commit → one atomic truthful step (dirty iff a written var moved since **this fiber's** snapshot; snapshots are per-slot) | Spec B's contract with Spec A (plan §3); Spec A refines lock/validate/publish |
| — | Sweeps are spawned on EVERY submission and read the retry map only at semaphore-acquire time — deliberately NOT optimised away when nothing is parked | a sweep spawned while a transaction is mid-park blocks on the retry semaphore, then finds that transaction parked and wakes it. That rescue path is load-bearing for the park-time checks; an earlier draft skipped empty-map sweeps and thereby deleted it (the reduction was unsound and is recorded here as a warning) |
| — | The park region is split in CODE ORDER, never collapsed | the retry semaphore serializes it against sweeps but NOT against commits or completions, so a conflictor can move between the two checks; collapsing them hides the ordering defect that TLC finds as a deadlock |
| — | The park-time staleness check reads `LoggedFP`, **not** `ActualFP` | the code's check is a fold over `log.values`, so it sees what the log RECORDS, and the two diverge on an absent-key read. Modelling it over `ActualFP` — what the transaction touches — checked a **stronger** guard than the protocol implements, and a model that checks a stronger guard than the code can verify a defective protocol clean. It did: that is the absent-key lost wakeup, and it is the sharpest fidelity lesson in this workstream. Every future check that mirrors a fold over the log must be modelled over `LoggedFP` |
| — | Two exec slots + two cascade slots per txn, `droppedSpawn` ghost | the ghost turns any silent drop into a `NoDroppedSpawn` violation instead of an invisible truncation; post-fix no drop occurs at these bounds (pre-fix, drops began at depth ~72) |

Failure injection (`AbortsEnabled`): a nondeterministic abort at any point
the corresponding `handleErrorWith` covers. The model does not claim any
specific organic throw site — injection runs measure robustness, and their
verdicts are labelled accordingly.

### Spec A reductions

Split in **code order**. The lesson behind that rule has now been learned twice,
both times the same way — a model that is coarser than the code will verify a
broken protocol clean. A collapsed park region hid H1's ordering defect; a park
check modelled over the wrong footprint hid the absent-key wakeup. So nothing
that collapses a check-then-act gap is collapsed here, and every collapse that
remains carries its argument.

| # | Reduction | Justification |
|---|-----------|---------------|
| A1 | Read phase → one step **per accessed entity** (NOT one snapshot) | the log run reads live state entity by entity, so other transactions' publishes interleave between them; collapsing would hide reads that straddle a concurrent publish. Writes snapshot too — `setVar` on an unlogged var does `txnVar.get` first to build `initial` |
| A1a | Intra-transaction access order is **nondeterministic**, not fixed | the scenario catalogue records each transaction's access *set*, not its program order. A fixed order models ONE program and can hide the interleavings of the others — a false-**green** risk, which is the direction that matters here (the red verdicts are hand-validated against the code regardless). Quantifying over all orders makes every clean verdict hold for EVERY program with that access set. It over-approximates, so a counterexample must be validated line-by-line against the code before acceptance — the standing rule for every counterexample in this repo |
| A2 | Lock **resolution** → one step per write entry, in sort order (NOT collapsed) | `traverse(_._2.lock)` is sequential in `F`, and each `TxnLogUpdateVarMapEntry.lock` is a **live** read of `getTxnVar(key)`. Lock identity is state-dependent and is resolved *before* any acquisition, so a resolved identity can in principle go stale before it is taken — the model must be able to show that |
| A3 | Lock **acquisition** → one blocking step per distinct lock, in resolved order | this is where H2's cycle forms; it is the whole point |
| A4 | The dirty check → **one** step | it reads only write-set entities (read-only entries hardcode `isDirty = false`), and every write-set entity contributes a lock. A concurrent writer to one either holds the same lock (excluded) or is footprint-incompatible (excluded by Contract C in accurate mode). In fallback mode that argument weakens — but a coarser check can only **hide** anomalies, never invent them, and the fallback configs already go red |
| A5 | Publish (`log.commit`) → **one** step | **this is plan §3's refinement obligation, discharged.** In accurate mode no concurrent transaction can read *or* write any entity in our write set: overlap is caught by raw-id intersection, and a reader of a written entity's *parent* by the relation's third conjunct — the H5 fix. So the publish is unobservable-as-torn, which is exactly what licenses Spec B's atomic-commit abstraction. Note the dependency: **before the H5 fix this reduction was unsound** |
| A6 | Lock **release** → one step | releasing only ever frees locks; extra interleavings would hold strictly fewer locks, which cannot create a cycle atomic release avoids, and the publish has already happened |
| A7 | Static analysis + `AnalysedTxn` construction → `Init` | Spec B's R4. **NOT because there is no contention** — `staticAnalysisCompiler` performs *live reads* of shared state (`txnVar.get`, `txnVarMap.get`, `getTxnVar(key)`) and runs in `TxnRuntime.commit` **before** `submitTxn`, i.e. outside `activeTransactions` and outside any Contract-C window, concurrently with other transactions' non-atomic `log.commit`. The justification is that it never *writes*, so it cannot perturb another transaction. What it *can* do is read a value that changes under it, and since those values drive the free-monad continuation they can change the footprint it computes. **That is H6** — promoted out of the plan's §10 "out of scope" list once it was confirmed, and since fixed. The reduction still hides H6's *cause*, because folding the walker into `Init` means the model never runs it concurrently with anything. It does not hide H6's *consequence*: `CommitH6.cfg` hands a transaction a declared footprint that names the wrong ids — the state the race leaves behind — and it is against that state that the commit-time coverage check is verified. (Spec B's R4 carries the same correction) |
| A8 | A **whole-map read** is one step | `TxnVarMap.get` is *not* atomic in the code — it reads the index `Ref`, then each entry's `Ref` in turn — so it can observe a torn map. Sound here by exactly the A5 argument: the relation's third conjunct makes any child-entry writer incompatible with a structure reader, so nobody can mutate the map under a whole-map read. **Like A5, this reduction depends on the H5 fix** and was unsound before it |
| — | Values → **version counters** | `isDirty` compares values, so a rewrite of an unchanged value reads clean in the code but bumps the version in the model. The abstraction therefore over-approximates dirtiness: it can produce spurious dirty retries (safe) but never misses a real change. Where versions are *not* enough, the operator is faithful but **unexercised**: for a new-key insert the entry's `initial` is `None` and `isDirty` asks `oValue.isDefined` — "does the key exist now", not "did the version move" — and `EntryDirty` branches on exactly that, but no config can reach the branch (see the catalogue precondition above), and insert-then-delete ABA is not representable at all because deletes are unmodelled |
| — | The `TxnLogRetry` re-validation branch is **out of scope as behaviour** | its park/wake consequences are Spec B's (H1). What Spec A owes it is the fidelity pin that it is *vacuous* for a pure reader — no locks, never dirty — which is the `NoLocksWithoutWrites` invariant. A model that "helpfully" re-validated reads before parking would mask the very gap that forced `hasChangedSinceRead` into existence |

## Parameter Sweeps & State-Space Data

State counts are measured with `-workers 1`; wall-clock is from the CI runner.
Every **clean** config drains its queue, which is what "exhaustive" means here:
the verdict holds over the whole state graph at these bounds, `BoundsNeverBind`
confirms the horizon never clipped it, and the counts are reproducible
constants. The **red** rows are a different kind of number — see below the
table.

| Config | Verdict | States (gen / distinct) | Queue | Depth | CI time |
|--------|---------|------------------------|-------|-------|---------|
| `FootprintLemmas` | clean | — (lemma `ASSUME`s, not a state graph) | — | — | 38.7 s |
| `Scheduler` | clean — **exhaustive** (since the H4 fix) | 3,775,555 / 845,687 | 0 | 85 | 36.1 s |
| `SchedulerRetry` | clean — **exhaustive** (since the H1 fix) | 22,483 / 7,665 | 0 | 61 | 1.7 s |
| `SchedulerAbsentKey` | **RED — deadlock** (pinned; the park guard's negative control). 36-state trace | 6,391 / 2,590 † | 231 † | — | ~30 s |
| `SchedulerAborts` | **RED — `CompletionAtMostOnce`** (pinned). 15-state trace | — † | — † | — | dispatch-gated |
| `CommitH2` | clean — **exhaustive** (since the H2 fix) | 6,082 / 2,100 | 0 | 36 | 1.6 s |
| `CommitH3` | clean — **exhaustive** (since the H3 fix) | 51 / 45 | 0 | 21 | 0.8 s |
| `CommitH3Partial` | clean — **exhaustive** (since the H3 fix) | 91 / 69 | 0 | 27 | 0.8 s |
| `CommitH3Writer` | clean — **exhaustive** (since the H3 fix) | 45 / 41 | 0 | 20 | 0.8 s |
| `CommitH6` | clean — **exhaustive** (since the H6 fix) | 155 / 97 | 0 | 25 | 0.8 s |
| `CommitDirty` | clean — **exhaustive** | 187 / 121 | 0 | 27 | 0.8 s |
| `CommitAccurate` | clean — **exhaustive** | 31,368 / 11,385 | 0 | 48 | 3.4 s |

**† The two red rows are not exhaustive, and do not need to be.** TLC halts at
the first violation — the entire point of a pinned counterexample — so their
counts are a *prefix of the search*, not a state graph: the queue is non-empty
at halt and there is no diameter to report. Read them as indicative. They are
also **worker-dependent**: the `-workers 1` figures above are stable run to run,
but CI runs `-workers auto`, where workers race to the deadlock and the halt
point moves each time — two consecutive `SchedulerAbsentKey` runs generated
9,201 and 8,843 states against the 6,391 quoted above. What reproduces is the
**verdict**, and the verdict is all `check_expected.sh` asserts.
`BoundsNeverBind` for these rows holds only over what was explored.

**Fixing a defect usually shrinks its state space, sometimes drastically.**
That is not a coincidence — every one of these fixes works by *removing*
concurrency the protocol should never have allowed, and the interleavings go
with it.

- `Scheduler` was **infeasible** before the H4 fix: >32M distinct states after
  10 minutes at the same `MaxInc=2, MaxVer=6` bounds, queue still growing at
  depth 98. The admission gate retires loser fibers immediately and the whole
  thing now closes at 846k distinct in 36.1 s — a ~1300× collapse, and a
  measure of how much spurious concurrency the unguarded protocol admitted.
  *(This figure was 70k/24k/depth-77/~2 s before the retry/park/wake machinery
  — retry semaphore, sweeps, park region — joined the same module. It is quoted
  only so a reader who remembers the old number is not confused by this one.)*
  Pre-fix violation runs halted in 1–3 s at search depths 50–64 (4k–120k
  distinct); the `NoDroppedSpawn` boundary run reached depth 72 in ~20 s / 1.2M
  distinct.
- The three **H3** configs shrank by roughly 6× (`CommitH3`: 168 distinct
  states red, 45 clean). An under-approximated footprint is now incompatible
  with everything, so the transaction runs alone and there is very little left
  to interleave.
- `CommitH2` halted red at 312/156, queue 9, depth 17 pre-fix — the 17-state
  counterexample. Post-fix it explores 2,100 distinct states and drains.

Two corollaries of running `Scheduler` with deadlock detection on: the `MaxInc`
incarnation bound never binds in this scenario (an `ExecResubTruncate` firing
would strand a transaction and surface as a deadlock), and the organic verdict
is scoped to **non-throwing** transactions — the error-dispatch shape
(`TxnResultFailure`) is exercised only in the failure-injection config, where it
is protocol-isomorphic to the success path.

`FootprintLemmas` is the single most expensive check in the set, at 38.7 s, and
`LemmaCoverageIsSound` is why: it quantifies over ordered *triples* of complete
footprints, which is 256³ ≈ 16.8M of them, where every other lemma is a pair at
most.

All **seven** Spec A configs together cost about **9 s** — `CommitAccurate`, the
largest at 5 transactions, is 3.4 s of that, and the other six are under 2 s
each. Spec A is cheap enough that CI runs every config on every push, with none
held back for `workflow_dispatch`.

Contract C's guard is what keeps these small: it prunes the interleavings the
scheduler would never allow, which is the whole benefit of the assume-guarantee
split — and Spec B is what earns the right to assume it.

## Design Decisions

- **Raw TLA+, named-`ASSUME` lemma modules, per-concern `.cfg`s** — echidna
  house pattern (`../echidna/specs`).
- **Compatibility is always computed, never hand-assigned.** Scenario
  footprints go through `common/Footprint.tla`'s `IsCompatible`/`Validated`;
  an early plan draft mis-reasoned a compatibility by hand (H2) and this rule
  exists so that cannot recur.
- **Strings as txn ids, records as var ids.** Runtime IDs are arbitrary
  distinct naturals chosen per scenario; the real system's allocator-issued
  IDs are likewise arbitrary distinct naturals whose relative order depends
  on creation/first-touch timing, so every ordering class the model explores
  is reachable in code.
- **Two exec slots / two cascade slots per txn, with a drop detector.**
  Double-spawn and double-cascade were the defects under test; the model
  must represent them to prove the gate rejects them. Every dropped spawn
  sets the `droppedSpawn` ghost so `NoDroppedSpawn` reports truncation
  explicitly — pre-fix it went RED at depth ~72; post-fix it HOLDS (loser
  fibers vacate slots immediately).
- **`status` matches the code exactly**: never demoted (nothing writes it
  after `Running` except a resubmission's `set(Scheduled)`), so scans read
  the same values the code would. Completion is tracked by `completedCount`
  alone, and Contract C's window is encoded from exec-fiber position, not
  status.
- **Pinned reds over green-washing.** While a defect exists, CI asserts that
  its counterexample *reproduces* rather than skipping the check, so the spec
  stays load-bearing and the flip to green has to come through this README and
  the plan together. Every defect is fixed now, and the mechanism kept its
  job: `SchedulerAbsentKey` uses it to hold a **negative control** red in CI
  permanently. A guard whose control is not exercised is a guard nobody is
  checking.

## Which defects behavioural testing could — and could not — have found

Every fix in this workstream was reverted in turn and the behavioural suites re-run.
The result is the clearest argument in this project for why the models were worth
building, and it is recorded here rather than left as a matter of opinion.

| Defect | Reproducible by a test? | Evidence |
|---|---|---|
| **H2** lock-order deadlock | **Yes** | `CommitLockOrderSpec` deadlocked and timed out pre-fix |
| **H3** under-declared footprint | **Yes** | 198/200 contended reps skewed; the soak catches it too (G2) |
| **H5** phantom | **Yes** | ~98% of contended reps; the soak catches it too (G2) |
| **H6** data-dependent footprint | **Only if ENGINEERED** | `DataDependentFootprintSpec` had to suspend the analysis pass with a gate to build the interleaving. The randomized soak does **not** catch it |
| **H4** double execution | **NO** | the dirty-path stress test drives the machinery the fix hardened (~120 resubmissions per 300 reps) but never reaches the defect interleaving — it passes against pre-fix code too, and says so at `SerializabilityOracleSpec`'s dirty-path block. The executable H4 gate is `Scheduler.cfg` |
| **H1** lost wakeup | **NO** | reverting the fix leaves `RetrySoakSpec` green |
| **spurious self-wake spin** | **NO** | reverting the fix leaves `RetrySoakSpec` green |
| **absent-key lost wakeup** | **NO** | reverting the fix leaves `AbsentKeyParkSpec` green — 240 reps under scheduling pressure produced no interleaving. The suite says so in its own header. It is a coverage floor for the absent-key park/wake path, and the defect is pinned by `SchedulerAbsentKey.cfg` instead |

Eight defects, and **five of them could not have been found by running the
code.** H6 needed a probe that already knew the answer. H4, H1, the self-wake
spin and the absent-key wakeup cannot be reached by a running program at all —
in H4's case the suite that comes closest is green against the broken code, and
in the absent-key case the suite exists and is *documented* as unable to catch
what it covers.

Why the two wakeup defects resist is the same story, because it is the same
window. It needs the conflictor's entire submit-and-sweep to complete between
the parker leaving `activeTransactions` and the parker taking the retry
semaphore — about two microseconds. Land early and the conflictor's scan sees
the parker still active, subscribes, and `hasDownstream` makes it resubmit
instead of park: no park, no bug. Land late and the conflictor's sweep blocks on
the semaphore the parker holds, finds it, and wakes it — the rescue path,
working. A running program essentially never lands between the two. **In
production it is reachable**: a GC pause widens that gap to milliseconds. That
is what makes these real bugs rather than curiosities, and it is why only
exhaustive interleaving finds them.

The corollary matters as much. A green behavioural suite is no evidence that
these protocols are correct — five of the eight defects here were sitting under
one. The pins in this file are the evidence.

## Keeping Specs and Code in Sync

1. **Source anchors** (`// SPEC: <Name>`) verified by
   `specs/verify_anchors.sh` in CI — see the cross-reference tables above.
2. **Pinned expectations** via `specs/check_expected.sh` in CI — clean specs
   must stay clean, and pinned counterexamples must keep reproducing. Every
   defect this workstream found is now fixed, so the two remaining reds are
   there permanently by design: `SchedulerAbsentKey` is a negative control for
   the park guard, and `SchedulerAborts` is the robustness pin. Neither is a
   defect waiting on a fix, and if either stops reproducing, something in the
   model or the protocol moved.
3. **Semantic property tests** (`src/test/scala/spec/SerializabilityOracleSpec.scala`,
   runs in the normal 2.13/3 matrix): ScalaCheck-generated concurrent
   point-op workloads against the real STM, with every outcome checked
   against all serial orders of a sequential reference model (green — the
   scheduler serializes footprint-visible conflicts correctly), an
   increment canary for the H4 double-execution family, the **H5
   regression test** — the whole-map-read + new-key-insert idiom, which
   before the fix produced phantom write skew in ~98% of contended reps,
   must now be serializable in every rep (500 per run) — and the **H4
   dirty-path stress test**, which uses data-dependent map keys to force
   declared/actual footprint divergence and drive the resubmission
   machinery (`freshIncarnation` + `admitForExecution`) under real
   contention with exactly-once effect checks — though, as its own note
   records, that test does not reach the H4 interleaving and passes against
   pre-fix code. Whole-map reads stay out of the *generated* workloads (the
   targeted H5 test covers the idiom deterministically), and so do
   `waitFor`/retry workloads: those are carried by `RetrySoakSpec`'s bounded
   buffer, by a targeted park/wake case in this same suite, and — for the
   absent-key path — by `AbsentKeyParkSpec`.
4. **Behavioural regressions for the fixed defects**
   (`src/test/scala/spec/StaticAnalysisFallbackSpec.scala`): the H3
   counterpart to the TLC pins, and it has already flipped once. Two controls
   show the write-skew pair being correctly serialized — first when its
   footprint is fully analysable, then with the read-your-own-write prelude
   still in place but its continuation TOTAL, which holds everything fixed
   except the throw and rules out the scratch map as the cause. The third test
   used to assert that the anomaly still reproduced (**198/200 contended
   reps**); with the fix in, it was rewritten to assert `"no rep skews"` and
   now measures **0 in 1000 reps**. `DataDependentFootprintSpec` is the same
   thing for H6 (20/20 pre-fix, 0/20 post-fix). Neither of these can stand in
   for the TLC pins — see the coverage table above for which defects a test
   could never have reached at all.
