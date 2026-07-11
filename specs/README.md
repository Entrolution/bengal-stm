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

**They do not verify:**

- The commit protocol's internals: lock ordering, dirty-check, publish
  (Spec A, `commit/`) — Phase 3; Spec B treats commit as one atomic step
- The free-monad compiler / static-analysis walker
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
| `commit/` | Spec A: commit protocol (locks, dirty-check, publish) | **Phase 3 — not yet written** |

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
check, and the clean Scheduler verification on every change to `specs/**`
or the in-scope runtime sources (plus the `workflow_dispatch`-gated
robustness pin). Expectations bind in both directions: a clean spec going
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
| `NoDoubleExec`, `ContractC`, `NoExecOnCompleted`, `NoDoublePublish`, `TallyNonNegative`, `CompletionAtMostOnce` | **HOLD — H4 FIXED (2026-07-11)** by the admission gate (`admitForExecution`: status CAS + zero tally under the graph semaphore), fresh bookkeeping per incarnation (`freshIncarnation`), and exactly-once cascades (`cascadeFired`). Exhaustive at the defect scenario's own bounds, ~24k distinct states in ~2s — the gate collapsed the pre-fix >32M-state explosion. **Before the fix all six failed organically** at TLC search depths ~50–64 (`NoDoubleExec` was the CI pin; historical narrative below) | `Scheduler.cfg` (CI, expected clean) |
| **Deadlock freedom** (organic) | **HOLDS** — detection enabled, legitimate terminals modelled by `Terminating` | `Scheduler.cfg` |
| **H1 lost wakeup — park/submit window** | **CONFIRMED and FIXED (2026-07-11)**: pre-fix the retry config deadlocked — a conflictor's submission-time sweep ran against a retry map that did not yet contain the parker, the conflictor's commit then satisfied the parker's predicate, and wakes fire only from sweeps, so nothing ever woke it. The fix scans `activeTransactions` for footprint conflicts and re-checks the read set (`hasChangedSinceRead` — a real comparison, unlike the vacuous read-only `isDirty`), both inside the retry-semaphore region before parking. **The check ORDER is load-bearing**: scan first, staleness last. The reverse order — which a first draft shipped — still loses wakeups (a conflictor commits after the staleness read and leaves `activeTransactions` before the scan, escaping both) and TLC finds it as a deadlock; that is negative control NC-A below | `SchedulerRetry.cfg` (CI, expected clean) |
| **Spurious self-wake spin** (found while verifying H1) | **FIXED (2026-07-11)**: a parked transaction is footprint-incompatible with *itself* (it writes), so its own in-flight submission sweep woke it, it re-ran, re-parked, and the next sweep could do it again — an unbounded spin under adversarial scheduling, pre-existing and orthogonal to H1. The retry map is now keyed by `TxnId` rather than footprint, and a sweep skips the submitting transaction (its own submission cannot satisfy its own predicate — it retried rather than committed). Keying by id also removes the old wake-chaining of distinct transactions that happened to share a footprint | `SchedulerRetry.cfg` (`BoundsNeverBind`); negative control NC-C |
| `NoDroppedSpawn` | **HOLDS post-fix** — the admission gate retires loser fibers fast enough that no spawn is dropped at these bounds (pre-fix the two-slot bound truncated behaviour from depth ~72) | `Scheduler.cfg` |
| `TypeOK` | holds on every completed run | all cfgs |

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
| R4 | Static analysis, `AnalysedTxn` construction, `Deferred` creation → `Init` | pre-protocol, no shared state contention |
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

## Parameter Sweeps & State-Space Data

Measured on 12 cores (Apple x86_64, JDK 21, TLC 2026-07 build):

- **Post-H4-fix full verification** (`Scheduler.cfg`, all invariants,
  deadlock detection on): **exhaustive in ~2 s — 70k states generated, 24k
  distinct, search depth 77, queue drained**. The admission gate retires
  loser fibers immediately, collapsing the interleaving explosion, so the
  green run fits CI at the defect scenario's own bounds with no downsizing.
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
