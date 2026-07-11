# TLA+ Formal Specifications

Formal specifications of bengal-stm's transaction scheduling and commit
protocols, verified with the TLC model checker. The plan, architecture
rationale, and hypothesis list live in
[`docs/plans/formal-specs.md`](../docs/plans/formal-specs.md); this README is
the operational reference and the **source of truth for verdicts**.

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

**Scheduler protocol, Phase 1 scope** (`src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala`):

- Submission and dependency-graph construction, tallies, statuses,
  unsubscribe cascades, the dirty-resubmission path, the
  `handleErrorWith` recovery branches, and (since Phase 2) the retry
  map / park / wake machinery (`TxnResultRetry`, `submitTxnForRetry`,
  `checkRetryQueue`)
- Six safety invariants over that machinery — all six **HOLD since the H4
  fix** (admission gate, fresh incarnations, exactly-once cascades),
  verified exhaustively with deadlock detection enabled; before the fix
  all six failed organically (see the verdict table)

**Commit protocol** (`src/main/scala/bengal/stm/runtime/TxnLogContext.scala`):

- The `withLock` / `isDirty` / `commit` sequence at lock-operation
  granularity: the log run, lock resolution (identity is state-dependent —
  a new-key map entry falls back to the map's structural lock), blocking
  acquisition in resolved order, the write-set-only dirty check, publish,
  release, and the dirty-resubmission refinement
- Contract C is **assumed** here and **checked** by Spec B — the
  assume-guarantee split is discharged, not aspirational
- **Two confirmed defects, both pinned red**: H2 (lock-order deadlock via
  the map-lock fallback — reachable on *accurate* footprints) and H3 (write
  skew when the static-analysis fallback under-declares). See the verdict
  table

**They do not verify:**

- The free-monad compiler / static-analysis walker (its *output* is modelled
  — accurate vs under-approximated footprints are a Spec A mode — but its
  internals are not)
- `TxnLog` bookkeeping, value semantics (vars are version counters here)
- Cancellation, `TxnVarMap.internalStructureLock` (leaf lock below the
  modelled `commitLock`s), runtime-ID hash collisions (assumed distinct —
  IDs are 32-bit UUID-hash-derived, `TxnStateEntity.scala:36-37`, so
  collisions are possible at scale but are a property-test concern)

## Specs

| Spec | What it models | Scala correspondence |
|------|----------------|----------------------|
| `common/Footprint.tla` | The footprint data model and compatibility relation (operators only) | `IdFootprint.scala`, `TxnVarRuntimeId.scala` |
| `common/FootprintLemmas.tla` | Exhaustive lemma checks over a 4-id universe (256 footprints, 65,536 ordered pairs) as named `ASSUME`s | `IdFootprint.scala` |
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

From the repository root. Every result below is asserted by
`specs/check_expected.sh`, which encodes whether a run is expected clean or
expected to reproduce a **pinned counterexample** (an EXPECTED-RED verdict):

```bash
# Footprint lemmas — expected clean, ~1s
./specs/check_expected.sh specs/common/FootprintLemmas.cfg \
    specs/common/FootprintLemmas.tla NONE

# Scheduler verification — expected CLEAN since the H4 fix (exhaustive,
# deadlock detection on), ~2-3s
./specs/check_expected.sh specs/scheduler/Scheduler.cfg \
    specs/scheduler/Scheduler.tla NONE

# Robustness config — expected RED (pinned CompletionAtMostOnce);
# ALLOW_DEADLOCK suppresses deadlock detection for aborted-zombie states
./specs/check_expected.sh specs/scheduler/SchedulerAborts.cfg \
    specs/scheduler/Scheduler.tla CompletionAtMostOnce ALLOW_DEADLOCK

# --- Commit protocol (Spec A) ---

# H2 — lock-order deadlock via the map-lock fallback. EXPECTED RED.
./specs/check_expected.sh specs/commit/CommitH2.cfg \
    specs/commit/CommitProtocol.tla NoWaitsForCycle

# H3 — write skew when the fallback under-declares. EXPECTED RED, three ways:
#   CommitH3        the EMPTY-footprint extreme (case _ => IdFootprint.empty)
#   CommitH3Partial the PARTIAL footprint ordinary code actually produces
#                   (StaticAnalysisShortCircuitException) — same verdict
#   CommitH3Writer  an under-declared WRITER breaking a correctly-declared
#                   peer's reads (it has no reads of its own at all)
./specs/check_expected.sh specs/commit/CommitH3.cfg \
    specs/commit/CommitProtocol.tla CommitSnapshotValid
./specs/check_expected.sh specs/commit/CommitH3Partial.cfg \
    specs/commit/CommitProtocol.tla CommitSnapshotValid
./specs/check_expected.sh specs/commit/CommitH3Writer.cfg \
    specs/commit/CommitProtocol.tla CommitSnapshotValid

# The one case the fallback IS caught — a pure write-write collision, by the
# commit locks plus the dirty check. EXPECTED CLEAN.
./specs/check_expected.sh specs/commit/CommitDirty.cfg \
    specs/commit/CommitProtocol.tla NONE

# Accurate footprints, including the H5 idiom. EXPECTED CLEAN — the H5 fix,
# confirmed from the commit-protocol side. ~10s.
./specs/check_expected.sh specs/commit/CommitAccurate.cfg \
    specs/commit/CommitProtocol.tla NONE

# Raw TLC invocation (traces print to stdout; TTrace files are gitignored):
java -XX:+UseParallelGC -cp specs/tla2tools.jar tlc2.TLC \
    -config specs/scheduler/Scheduler.cfg specs/scheduler/Scheduler.tla \
    -workers auto
```

Deadlock detection is ENABLED for organic Scheduler runs (no `-deadlock`
flag): legitimate terminals — every completion signal fired — stutter via
the explicit `Terminating` action, so any reported deadlock is a real
protocol deadlock. The failure-injection config passes `ALLOW_DEADLOCK`
(the script's 4th argument adds `-deadlock`) because aborted zombies
legitimately strand their dependents.

CI (`.github/workflows/specs.yml`) runs `verify_anchors.sh`, the lemma
check, both Scheduler configs (safety and retry/park/wake), and **all six
commit-protocol configs** on every change to `specs/**` or the in-scope
runtime sources — the whole set costs seconds. Only the Scheduler robustness
pin is `workflow_dispatch`-gated. Expectations bind in both directions: a clean spec going
red means a protocol regression (or rolling-TLC drift — the `v1.8.0` tag is
a nightly; investigate which), and a pinned counterexample stopping
reproducing means the modelled defect changed shape. Either way, fix and
spec move together (this verdict table, the cfg, and the plan's hypothesis
rows).

## Verdict Table (source of truth)

Scenario for all Scheduler rows: 3 txns / 2 vars, `t1` under-declared (empty
footprint — the static-analysis fallback in `TxnRuntime.commit`), `t2`
accurately declaring a write to the same var, `t3` accurately declaring a
read of it; `MaxInc=2`, `MaxVer=6`, no failure injection. This is the exact
scenario that produced the H4 counterexample family before the fix.

| Property | Verdict | Evidence |
|----------|---------|----------|
| `LemmaCompatSymmetric`, `LemmaValidatedIdempotent`, `LemmaValidatedPreservesUpdates`, `LemmaValidatedMonotoneCompat`, `LemmaWriterSelfIncompatible` | **HOLD** (exhaustive at 4-id universe) | `FootprintLemmas.cfg`, clean |
| `DocumentsParentReadChildWriteCaught` — parent-structure read vs child-entry write now judged **incompatible** (plus `DocumentsParentReadChildReadCompatible`: pure readers stay compatible) | **HOLDS — H5 FIXED (2026-07-11)** by the relation's third conjunct; before the fix this pair was compatible (pinned then as `DocumentsReadGapH5`) | `FootprintLemmas.cfg` |
| **H5 phantom write skew — behavioural** (whole-map read + new-key insert) | **FIXED (2026-07-11)** — was CONFIRMED at ~98% of contended reps (both txns observed the empty map, both committed); the fixed relation serializes the pair and the regression test asserts every rep serializable | `SerializabilityOracleSpec` ("H5 phantom write skew … regression for the H5 fix") |
| `DocumentsSiblingInsertsCompatible` — two new-key inserts to one map compatible (H2 enabler) | **PINNED** (correct behaviour; the hazard is the lock aliasing, Spec A) | `FootprintLemmas.cfg` |
| `NoDoubleExec`, `ContractC`, `NoExecOnCompleted`, `NoDoublePublish`, `TallyNonNegative`, `CompletionAtMostOnce` | **HOLD — H4 FIXED (2026-07-11)** by the admission gate (`admitForExecution`: status CAS + zero tally under the graph semaphore), fresh bookkeeping per incarnation (`freshIncarnation`), and exactly-once cascades (`cascadeFired`). Exhaustive at the defect scenario's own bounds — 846k distinct states in ~30 s, queue drained (it was 24k/~2 s when Phase 1 measured it, before Phase 2 added the retry machinery to the same module; the gate still collapsed the pre-fix >32M-state explosion, which never terminated at all). **Before the fix all six failed organically** at TLC search depths ~50–64 (`NoDoubleExec` was the CI pin; historical narrative below) | `Scheduler.cfg` (CI, expected clean) |
| **Deadlock freedom** (organic) | **HOLDS** — detection enabled, legitimate terminals modelled by `Terminating` | `Scheduler.cfg` |
| **H1 lost wakeup — park/submit window** | **CONFIRMED and FIXED (2026-07-11)**: pre-fix the retry config deadlocked — a conflictor's submission-time sweep ran against a retry map that did not yet contain the parker, the conflictor's commit then satisfied the parker's predicate, and wakes fire only from sweeps, so nothing ever woke it. The fix scans `activeTransactions` for footprint conflicts and re-checks the read set (`hasChangedSinceRead` — a real comparison, unlike the vacuous read-only `isDirty`), both inside the retry-semaphore region before parking. **The check ORDER is load-bearing**: scan first, staleness last. The reverse order — which a first draft shipped — still loses wakeups (a conflictor commits after the staleness read and leaves `activeTransactions` before the scan, escaping both) and TLC finds it as a deadlock; that is negative control NC-A below | `SchedulerRetry.cfg` (CI, expected clean) |
| **Spurious self-wake spin** (found while verifying H1) | **FIXED (2026-07-11)**: a parked transaction is footprint-incompatible with *itself* (it writes), so its own in-flight submission sweep woke it, it re-ran, re-parked, and the next sweep could do it again — an unbounded spin under adversarial scheduling, pre-existing and orthogonal to H1. The retry map is now keyed by `TxnId` rather than footprint, and a sweep skips the submitting transaction (its own submission cannot satisfy its own predicate — it retried rather than committed). Keying by id also removes the old wake-chaining of distinct transactions that happened to share a footprint | `SchedulerRetry.cfg` (`BoundsNeverBind`); negative control NC-C |
| `NoDroppedSpawn` | **HOLDS post-fix** — the admission gate retires loser fibers fast enough that no spawn is dropped at these bounds (pre-fix the two-slot bound truncated behaviour from depth ~72) | `Scheduler.cfg` |
| `TypeOK` | holds on every completed run | all cfgs |

### Commit protocol (Spec A)

Scenarios are per-config (`Txns` + `UnderDeclared`). Every compatibility
judgement below is **computed** by `common/Footprint.tla`, never hand-assigned.

| Property | Verdict | Evidence |
|----------|---------|----------|
| **H2 — lock-order deadlock via the map-lock fallback** (`NoWaitsForCycle`) | **CONFIRMED (2026-07-11) — OPEN, pinned red.** Two transactions each inserting a **fresh key into two maps** have compatible footprints, so the scheduler deliberately runs them concurrently; each new-key entry falls back to its **map's** structural `commitLock`, so both hold `{M1.lock, M2.lock}`; and `withLock` sorts by the **log key**, which for an absent key is the existential hash of `(mapId, key)` — a value with no relation to the lock it resolves to. Hash-derived order therefore inverts the acquisition order and both fibers block forever on `Semaphore.permit`, callers hung on `completionSignal.get`. **Needs no fallback, no under-declaration, and no scheduler bug — it is reachable on fully ACCURATE footprints.** 17-state trace, exhaustive at depth 25 | `CommitH2.cfg` (CI, expected red) |
| **H3 — write skew when the fallback under-declares** (`CommitSnapshotValid`) | **CONFIRMED (2026-07-11) at both levels — OPEN, pinned red.** Reads are never commit-validated and hold no lock, so the scheduler's footprint conflict-avoidance is the only defence, and an under-approximated footprint switches it off. **Unsound in BOTH directions:** the under-declared transaction reads what a peer overwrites (`CommitH3.cfg`), *and* its undeclared writes invalidate a correctly-declared peer's reads (`CommitH3Writer.cfg` — that transaction has no reads at all and still breaks its peer). **Reachable from ordinary code**: `staticAnalysisCompiler` executes real reads but never applies writes, so reading back a key the transaction just wrote yields `None` during analysis and `Some(v)` at run time; a partial continuation on it (`.get`, a pattern match) throws during analysis only, and everything after the throw goes undeclared. Measured **198/200 contended reps skewed** — the anomaly is the default outcome under contention, exactly as for H5 pre-fix | `CommitH3.cfg`, `CommitH3Partial.cfg`, `CommitH3Writer.cfg` (CI, expected red); `StaticAnalysisFallbackSpec` (behavioural, pinned red) |
| **H3 — which fallback branch actually fires** | `TxnRuntime.commit`'s `handleErrorWith` has **two** under-approximating branches, and it matters which is which. `case _ => IdFootprint.empty` is the *extreme* point — compatible with everything — and is what `CommitH3.cfg` models. But the demonstrated defect takes the **other** branch: the walker's own handler converts the throw into `StaticAnalysisShortCircuitException(s)`, carrying the **partial** footprint accumulated so far, and `TxnRuntime.commit` schedules on *that*. `CommitH3Partial.cfg` models the partial footprint the real code produces (declared = just the scratch-key write; the whole skew undeclared behind it) and **reproduces the same violation** — so the verdict does not depend on the idealised empty case. Under-approximation is unsound whatever its *size*; what matters is that the conflicting ids are missing | `CommitH3Partial.cfg` (CI, expected red) |
| **The fallback's one safe case** — a pure write-write collision between two under-declared transactions | **HOLDS.** The conflict lands on the write set, which the commit locks and the dirty check *do* cover: the loser sees its snapshot moved, releases, refines its footprint from the actual log, and re-runs. This is the row that makes the H3 boundary precise — and `CommitH3Writer.cfg` is the row that stops it being over-read: the fallback is safe only when **neither** party reads anything | `CommitDirty.cfg` (CI, expected clean) |
| **H5 — model-side confirmation of the fix** (`CommitSnapshotValid`, accurate mode) | **HOLDS.** The H5 idiom (whole-map read + new-key insert) is now judged **incompatible** by the relation's third conjunct, so Contract C serializes the pair and no phantom arises. Independent of the relation-level lemma and of the oracle test — the same conclusion reached from the commit protocol instead. **Negative control NC-3 removes the third conjunct and this config goes red**, so the check is load-bearing rather than incidentally satisfied | `CommitAccurate.cfg` (CI, expected clean) |
| `NoLocksWithoutWrites` — a pure reader's log resolves **zero** locks and can never report dirty | **HOLDS** (fidelity pin). Read-only entries return `lock = None` and `isDirty = pure(false)`. This is why the `TxnLogRetry` re-validation before a park is vacuous, and why the H1 fix needed `hasChangedSinceRead` rather than `isDirty`. Pinned so the model can never quietly "improve" the protocol by validating reads | `CommitAccurate.cfg` (the `ro` transaction) |
| `ContractCHolds` | **HOLDS** on every config — but read it as a *guard pin*, not a verified protocol property: it restates `TxnEnter`'s guard, so it goes red only if a future edit weakens the guard and silently widens the model's concurrency. It is **trivially true in fallback mode**, where the declared footprints are empty and empty is compatible with everything. The property itself is *earned* by Spec B, which checks `ContractC` against the real scheduler | all `commit/` cfgs |
| `TypeOK`, `LocksHeldConsistent`, `BoundsNeverBind`, `PublishedExactlyOnce` | **HOLD** on every config — model sanity checks, not protocol results. `LocksHeldConsistent` earned its keep: it caught a real model bug (a release path that freed the locks but left the acquired-prefix bookkeeping stale). `PublishedExactlyOnce` is **structurally unfalsifiable here** — `pubCount` increments only in `Publish`, which is the sole exit from `validate` to `release`, and nothing re-enters. The real once-per-incarnation guarantee is Spec B's `NoDoublePublish`, which *can* fail and did, pre-H4-fix | all `commit/` cfgs |

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

**Negative controls — Spec A.** Applied to a scratch copy of
`CommitProtocol.tla` (NC-3 to a scratch `Footprint.tla`). NC-1 and NC-2 are
not only controls: each applies a **candidate fix** and shows it works, so the
fixes are pre-validated against the model before a line of Scala moves.

| # | Break / fix applied | Result |
|---|---------------------|--------|
| NC-1 | **Candidate H2 fix**: sort by the lock's **owner** id instead of the log key | `CommitH2.cfg` goes **clean** — the cycle is gone. Lock ↔ owner-id is a bijection, so a single ascending order is a total order on locks and no circular wait can form |
| NC-2 | **Candidate H3 fix**: an under-declared footprint is incompatible with everything | `CommitH3.cfg`, `CommitH3Partial.cfg` and `CommitH3Writer.cfg` all go **clean** — the fix works. **But read `CommitDirty.cfg` carefully: it stays clean *vacuously*.** Under NC-2 the two under-declared transactions can no longer overlap, so neither ever goes dirty and `DirtyRestart` becomes unreachable (measured by TLC action coverage: it fires 2× at baseline, **0× under NC-2**). That is the fix's throughput cost made concrete — and it means this control does **not** demonstrate that the dirty-refinement path survives the fix. Demonstrating that belongs to the H3 fix PR, which must add a scenario where an accurately-declared transaction still goes dirty |
| NC-3 | Remove the relation's **third conjunct** (the pre-H5-fix relation) | `CommitAccurate.cfg` goes **red** (`CommitSnapshotValid`) — the H5 fix is load-bearing here, confirmed from the commit-protocol side |
| NC-4 | Remove the **Contract C guard** — accurate footprints, locks and dirty check intact | `CommitAccurate.cfg` goes **red** (`CommitSnapshotValid`) — the commit protocol alone is NOT safe; the scheduler is doing real work. (Checking `ContractCHolds` as well would be circular — it restates the guard — so it is dropped for this control) |

**Reproducing the historical RED verdicts:** check out a pre-fix revision
(any tree before the H4 fix landed), copy that revision's `Scheduler.cfg`,
list the invariant of interest, and run TLC — each halts within seconds at
its quoted depth. On the fixed protocol the full invariant set verifies
clean.

**Robustness config** (`scheduler/SchedulerAborts.cfg`, run manually or via
the `workflow_dispatch`-gated CI step): enables nondeterministic failure
injection at every point a `handleErrorWith` can observe (`execute`'s
handler and the submit wrapper in `TxnRuntime.commit`). Measured and pinned:
`CompletionAtMostOnce` violated (**15-state counterexample**) — a submit-wrapper abort
landing after the readiness check completes the caller's signal with an
error while the already-spawned execute commits and completes again: a
spurious failure report on a transaction that published. The
double-`registerCompletion` path (second unsubscribe cascade) is also in
scope here. These runs assert protocol *robustness to* exceptions, not that
any particular throw is organically reachable; mid-publish throws are inside
the atomic-commit abstraction and out of scope (Spec A territory). With the
organic verdicts already red these runs add evidence, not new invariant
flips.

## Invariant Cross-Reference

Each anchored invariant carries a `// SPEC: <Name>` comment at the code site
that realises (or, for EXPECTED-RED rows, *fails to guard*) it —
`specs/verify_anchors.sh` parses these tables and greps the declared file,
failing CI if an anchor disappears. For expected-red invariants the anchor
marks the **mechanism site**; it moves to the fix site when the fix lands.

### Scheduler protocol (anchors in `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala`)

| TLA+ Invariant | Anchor site | Status at anchor |
|---------------|-------------|------------------|
| `NoDoubleExec` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | spawn sites stay unguarded by design; `admitForExecution` admits at most one fiber per incarnation — HOLDS |
| `TallyNonNegative` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | the `getAndUpdate(_ - 1)` decrement is the only tally sink; fresh per-incarnation edges + exactly-once cascades pair every decrement with one increment — HOLDS |
| `NoExecOnCompleted` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | `admitForExecution`'s status CAS rejects fibers for completed incarnations — HOLDS |
| `ContractC` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | the submit scan builds edges; the admission gate makes them binding — HOLDS |
| `CompletionAtMostOnce` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | completion sites (success, error handler, submit wrapper); one execute window per incarnation — HOLDS organically |
| `NoDoublePublish` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | `log.commit` publishes; at most one admitted fiber per incarnation — HOLDS |
| `NoLostWakeup` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | `submitTxnForRetry` re-checks reads + active conflictors inside the retry-semaphore region before parking (the H1 fix) — the retry config verifies deadlock-free |

### Footprint relation (anchors in `src/main/scala/bengal/stm/model/runtime/IdFootprint.scala`)

| TLA+ Invariant | Anchor site | Status at anchor |
|---------------|-------------|------------------|
| `LemmaValidatedIdempotent` | `src/main/scala/bengal/stm/model/runtime/IdFootprint.scala` | one `getValidated` application is a fixpoint — HOLDS, makes the memoisation flag sound |
| `DocumentsParentReadChildWriteCaught` | `src/main/scala/bengal/stm/model/runtime/IdFootprint.scala` | the relation's third conjunct conflicts child writes with parent reads — the H5 fix |

### Commit protocol (anchors in `TxnLogContext.scala` / `TxnRuntimeContext.scala`)

Per the convention above, an EXPECTED-RED invariant's anchor marks the
**mechanism site** — the code that fails to guard it — and moves to the fix
site when the fix lands.

| TLA+ Invariant | Anchor site | Status at anchor |
|---------------|-------------|------------------|
| `NoWaitsForCycle` | `src/main/scala/bengal/stm/runtime/TxnLogContext.scala` | `withLock` sorts by the LOG KEY while a new-key entry resolves to its MAP's lock — the two id spaces are decoupled, so sorting cannot order the acquired locks — **EXPECTED-RED (H2)** |
| `NoLocksWithoutWrites` | `src/main/scala/bengal/stm/runtime/TxnLogContext.scala` | read-only entries return `lock = None` and `isDirty = pure(false)` — commit validation and commit locks cover the write set only — HOLDS (and is why serializability rests on the scheduler) |
| `CommitSnapshotValid` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | `TxnRuntime.commit`'s `handleErrorWith` yields an under-approximated footprint, which is unsound rather than merely weak — **EXPECTED-RED (H3)** |

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
| `graphSem` | `graphBuilderSemaphore` holder (`retrySemaphore` arrives with Phase 2) |

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
different ones for different jobs: the **existential** id, hashed from
`(mapId, key)` with the map as its parent, is what the **footprint** always
uses; the entry `TxnVar`'s **own** id, hashed from a fresh sequential
`TxnVarId` minted at insert time, is what the **log** keys an entry by once
the key exists. `withLock` sorts by the log key. That decoupling is H2.

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
| R4 | Static analysis, `AnalysedTxn` construction, `Deferred` creation → `Init` | pre-protocol and **read-only**. Note the correction that Spec A's A7 spells out: the static-analysis walker *does* touch shared state (it performs live `txnVar.get` / `txnVarMap.get` reads, before `submitTxn` and hence outside any Contract-C window). It is safe to fold into `Init` because it never *writes*; the values it reads can still shift under it, which is the data-dependent-footprint gap recorded in plan §10 |
| R5 | Concurrent `submitTxnForImmediateRetry` runs of one txn → serialized (one submit workflow per txn) | post-H4-fix this is **provably vacuous** rather than merely accepted: two dirty fibers for one txn would need two admitted fibers in one incarnation, which the admission gate excludes (`NoDoubleExec` HOLDS) |
| R6 | Cascade spawns with its edge set preloaded (gate + nonEmpty + values collapse) | the `cascadeFired` gate serializes cascades per incarnation and the owner left `activeTransactions` before the spawn, so the drained map is frozen — pre-fix these steps were kept separate precisely because double-spawned cascades could race a `clear()` |
| — | Commit → one atomic truthful step (dirty iff a written var moved since **this fiber's** snapshot; snapshots are per-slot) | Spec B's contract with Spec A (plan §3); Spec A refines lock/validate/publish |
| — | Sweeps are spawned on EVERY submission and read the retry map only at semaphore-acquire time — deliberately NOT optimised away when nothing is parked | a sweep spawned while a transaction is mid-park blocks on the retry semaphore, then finds that transaction parked and wakes it. That rescue path is load-bearing for the park-time checks; an earlier draft skipped empty-map sweeps and thereby deleted it (the reduction was unsound and is recorded here as a warning) |
| — | The park region is split in CODE ORDER, never collapsed | the retry semaphore serializes it against sweeps but NOT against commits or completions, so a conflictor can move between the two checks; collapsing them hides the ordering defect that TLC finds as a deadlock |
| — | Two exec slots + two cascade slots per txn, `droppedSpawn` ghost | the ghost turns any silent drop into a `NoDroppedSpawn` violation instead of an invisible truncation; post-fix no drop occurs at these bounds (pre-fix, drops began at depth ~72) |

Failure injection (`AbortsEnabled`): a nondeterministic abort at any point
the corresponding `handleErrorWith` covers. The model does not claim any
specific organic throw site — injection runs measure robustness, and their
verdicts are labelled accordingly.

### Spec A reductions

Split in **code order**, per the Phase 2 lesson (a collapsed region verified a
wrong protocol clean — see the H1 row). Nothing that collapses a
check-then-act gap is collapsed here, and every collapse carries its argument.

| # | Reduction | Justification |
|---|-----------|---------------|
| A1 | Read phase → one step **per accessed entity** (NOT one snapshot) | the log run reads live state entity by entity, so other transactions' publishes interleave between them; collapsing would hide reads that straddle a concurrent publish. Writes snapshot too — `setVar` on an unlogged var does `txnVar.get` first to build `initial` |
| A1a | Intra-transaction access order is **nondeterministic**, not fixed | the scenario catalogue records each transaction's access *set*, not its program order. A fixed order models ONE program and can hide the interleavings of the others — a false-**green** risk, which is the direction that matters here (the red verdicts are hand-validated against the code regardless). Quantifying over all orders makes every clean verdict hold for EVERY program with that access set. It over-approximates, so a counterexample must be validated line-by-line against the code before acceptance — the standing rule for every counterexample in this repo |
| A2 | Lock **resolution** → one step per write entry, in sort order (NOT collapsed) | `traverse(_._2.lock)` is sequential in `F`, and each `TxnLogUpdateVarMapEntry.lock` is a **live** read of `getTxnVar(key)`. Lock identity is state-dependent and is resolved *before* any acquisition, so a resolved identity can in principle go stale before it is taken — the model must be able to show that |
| A3 | Lock **acquisition** → one blocking step per distinct lock, in resolved order | this is where H2's cycle forms; it is the whole point |
| A4 | The dirty check → **one** step | it reads only write-set entities (read-only entries hardcode `isDirty = false`), and every write-set entity contributes a lock. A concurrent writer to one either holds the same lock (excluded) or is footprint-incompatible (excluded by Contract C in accurate mode). In fallback mode that argument weakens — but a coarser check can only **hide** anomalies, never invent them, and the fallback configs already go red |
| A5 | Publish (`log.commit`) → **one** step | **this is plan §3's refinement obligation, discharged.** In accurate mode no concurrent transaction can read *or* write any entity in our write set: overlap is caught by raw-id intersection, and a reader of a written entity's *parent* by the relation's third conjunct — the H5 fix. So the publish is unobservable-as-torn, which is exactly what licenses Spec B's atomic-commit abstraction. Note the dependency: **before the H5 fix this reduction was unsound** |
| A6 | Lock **release** → one step | releasing only ever frees locks; extra interleavings would hold strictly fewer locks, which cannot create a cycle atomic release avoids, and the publish has already happened |
| A7 | Static analysis + `AnalysedTxn` construction → `Init` | Spec B's R4. **NOT because there is no contention** — `staticAnalysisCompiler` performs *live reads* of shared state (`txnVar.get`, `txnVarMap.get`, `getTxnVar(key)`) and runs in `TxnRuntime.commit` **before** `submitTxn`, i.e. outside `activeTransactions` and outside any Contract-C window, concurrently with other transactions' non-atomic `log.commit`. The justification is that it never *writes*, so it cannot perturb another transaction. What it *can* do is read a value that changes under it, and since those values drive the free-monad continuation they can change the footprint it computes — that is the **data-dependent footprint** gap, recorded in plan §10 and out of scope here. (Spec B's R4 carries the same correction) |
| A8 | A **whole-map read** is one step | `TxnVarMap.get` is *not* atomic in the code — it reads the index `Ref`, then each entry's `Ref` in turn — so it can observe a torn map. Sound here by exactly the A5 argument: the relation's third conjunct makes any child-entry writer incompatible with a structure reader, so nobody can mutate the map under a whole-map read. **Like A5, this reduction depends on the H5 fix** and was unsound before it |
| — | Values → **version counters** | `isDirty` compares values, so a rewrite of an unchanged value reads clean in the code but bumps the version in the model. The abstraction therefore over-approximates dirtiness: it can produce spurious dirty retries (safe) but never misses a real change. Where versions are *not* enough, the operator is faithful but **unexercised**: for a new-key insert the entry's `initial` is `None` and `isDirty` asks `oValue.isDefined` — "does the key exist now", not "did the version move" — and `EntryDirty` branches on exactly that, but no config can reach the branch (see the catalogue precondition above), and insert-then-delete ABA is not representable at all because deletes are unmodelled |
| — | The `TxnLogRetry` re-validation branch is **out of scope as behaviour** | its park/wake consequences are Spec B's (H1). What Spec A owes it is the fidelity pin that it is *vacuous* for a pure reader — no locks, never dirty — which is the `NoLocksWithoutWrites` invariant. A model that "helpfully" re-validated reads before parking would mask the very gap that forced `hasChangedSinceRead` into existence |

## Parameter Sweeps & State-Space Data

Measured on 12 cores (Apple x86_64, JDK 21, TLC 2026-07 build):

- **Post-H4-fix full verification** (`Scheduler.cfg`, all invariants,
  deadlock detection on): **exhaustive — 3.78M states generated, 846k
  distinct, search depth 85, queue drained, ~30 s** on 12 cores. The admission
  gate retires loser fibers immediately, collapsing the interleaving explosion,
  so the green run fits CI at the defect scenario's own bounds with no
  downsizing.
  *(This figure was 70k/24k/depth-77/~2 s when Phase 1 measured it; Phase 2 added
  the retry/park/wake machinery — the retry semaphore, sweeps, and the park
  region — to the same module, and the number grew with it. The earlier figure
  sat stale in this file until Phase 3; it is quoted here only so a reader who
  remembers it is not confused.)*
  Two useful corollaries of running with deadlock detection on: the
  `MaxInc` incarnation bound never binds in this scenario (an
  `ExecResubTruncate` firing would strand a transaction and surface as a
  deadlock), and the organic verdict is scoped to **non-throwing
  transactions** — the error-dispatch shape (`TxnResultFailure`) is
  exercised only in the failure-injection config, where it is
  protocol-isomorphic to the success path.
- **Pre-fix, for the record**: violation runs halted in 1–3 s at TLC search
  depths 50–64 (4k–120k distinct states); the `NoDroppedSpawn` boundary run
  reached depth 72 in ~20 s / 1.2M distinct states; and a full organic
  sweep at the same `MaxInc=2, MaxVer=6` bounds was **infeasible** — >32M
  distinct states after 10 min, queue still growing at depth 98. The
  ~1300× state-space collapse is itself evidence of how much spurious
  concurrency the unguarded protocol admitted.

**Spec A.** Read the two halves of this table differently — and note what
"exhaustive" does and does not mean here:

- The **clean** configs really are exhaustive: the queue drains, and the counts
  are identical under `-workers 1` and `-workers auto`. Their verdicts hold over
  the whole state graph at these bounds, and `BoundsNeverBind` confirms no
  horizon clipping.
- The **red** configs are **not** exhaustive, and do not need to be. TLC halts at
  the first invariant violation, which is the entire point of a pinned
  counterexample. Their queues are non-empty at halt, their state counts are
  worker- and timing-dependent (so treat them as indicative, not as reproducible
  constants), and their "depth" is the depth reached at the halt, **not** a
  state-graph diameter. `BoundsNeverBind` for those rows is therefore established
  only over the explored prefix.

Measured with `-workers 1` for determinism:

| Config | Verdict | States (gen / distinct) | Queue at halt | Depth reached |
|--------|---------|------------------------|---------------|---------------|
| `CommitH2` | RED — `NoWaitsForCycle` | 312 / 156 | 9 | 17 (**17-state counterexample**) |
| `CommitH3` | RED — `CommitSnapshotValid` | 317 / 168 | 7 | 19 |
| `CommitH3Partial` | RED — `CommitSnapshotValid` | 816 / 350 | 3 | 26 |
| `CommitH3Writer` | RED — `CommitSnapshotValid` | 240 / 131 | 5 | 18 |
| `CommitDirty` | clean — **exhaustive** | 205 / 132 | 0 | 27 |
| `CommitAccurate` | clean — **exhaustive** | 31,368 / 11,385 | 0 | 48 |

All six together run in **~10 s** wall-clock on 12 cores (`CommitAccurate`, the
largest at 5 transactions, is 5–15 s of that depending on load). Spec A is cheap
enough that CI runs every config on every push, with none held back for
`workflow_dispatch`.

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
  distinct naturals chosen per scenario; the real system's hash-derived IDs
  make every ordering class reachable.
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
- **Expected-red pins over green-washing.** Where the protocol is defective,
  CI asserts the counterexample *reproduces* rather than skipping the check —
  the spec stays load-bearing while the defect exists, and the flip to green
  is forced through this README and the plan together.

## Keeping Specs and Code in Sync

1. **Source anchors** (`// SPEC: <Name>`) verified by
   `specs/verify_anchors.sh` in CI — see the cross-reference tables above.
2. **Pinned expectations** via `specs/check_expected.sh` in CI — clean specs
   must stay clean, and confirmed counterexamples must keep reproducing
   until the code is fixed.
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
   contention with exactly-once effect checks. Whole-map reads stay out of
   the *generated* suite (the targeted test covers the idiom
   deterministically); `waitUntil`/retry workloads join in Phase 2.
4. **Behavioural pins for open defects**
   (`src/test/scala/spec/StaticAnalysisFallbackSpec.scala`): the H3
   counterpart to the TLC pins. A control shows the write-skew pair being
   correctly serialized when its footprint is fully analysable; the pinned
   reproduction shows the same pair skewing once an ordinary
   read-your-own-write collapses the declared footprint (**198/200 contended
   reps**). It asserts the anomaly still reproduces — when H3 is fixed this
   goes red and is rewritten to assert that no rep skews, forcing the code,
   the TLC pins, this table and the plan to move together.
