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
- Pins of current behaviour with protocol consequences, including the **H5
  read gap**: a parent-structure READ is judged compatible with a child-entry
  WRITE (whole-map read vs new-key insert) — see the verdict table

**Scheduler protocol, Phase 1 scope** (`src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala`):

- Submission and dependency-graph construction, tallies, statuses,
  unsubscribe cascades, the dirty-resubmission path, and the
  `handleErrorWith` recovery branches
- Six safety invariants over that machinery — all six currently **fail
  organically** (no failure injection); see the verdict table

**They do not verify (Phase 1):**

- The retry map / park / wake machinery (`TxnResultRetry`) — Phase 2
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
| `scheduler/Scheduler.tla` | Spec B: the scheduler protocol at Ref/TrieMap-operation granularity | `TxnRuntimeContext.scala` |
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

# Scheduler evidence config — expected RED: pinned H4 counterexample
# (NoDoubleExec violated at TLC search depth ~50), ~2s
./specs/check_expected.sh specs/scheduler/Scheduler.cfg \
    specs/scheduler/Scheduler.tla NoDoubleExec

# Raw TLC invocation (traces print to stdout; TTrace files are gitignored):
java -XX:+UseParallelGC -cp specs/tla2tools.jar tlc2.TLC \
    -config specs/scheduler/Scheduler.cfg specs/scheduler/Scheduler.tla \
    -workers auto -deadlock
```

`-deadlock` is required: terminal states (all transactions completed, or a
zombie left by an aborted submission) have no successors by design, and TLC's
default deadlock detection would report them as errors.

CI (`.github/workflows/specs.yml`) runs `verify_anchors.sh` and then both
checks above on every change to `specs/**` or the in-scope runtime sources
(plus a `workflow_dispatch`-gated robustness check). A pinned-red check
failing means the counterexample stopped reproducing — either the protocol
changed, or the rolling TLC build drifted (the `v1.8.0` tag is a nightly);
investigate which before touching the pin. The coupling is deliberate: fix
and spec must move together (update this verdict table, the cfg pin, and the
hypothesis rows in the plan).

## Verdict Table (source of truth)

Scenario for all Scheduler rows: 3 txns / 2 vars, `t1` under-declared (empty
footprint — the static-analysis fallback in `TxnRuntime.commit`), `t2`
accurately declaring a write to the same var, `t3` accurately declaring a
read of it; `MaxInc=2`, `MaxVer=6`, no failure injection. All six defect
verdicts fire at TLC search depths 50–64, below the depth-72 truncation boundary
(`NoDroppedSpawn` below), so none of them is an artifact of the two-slot
fiber bound.

| Property | Verdict | Evidence |
|----------|---------|----------|
| `LemmaCompatSymmetric`, `LemmaValidatedIdempotent`, `LemmaValidatedPreservesUpdates`, `LemmaValidatedMonotoneCompat`, `LemmaWriterSelfIncompatible` | **HOLD** (exhaustive at 4-id universe) | `FootprintLemmas.cfg`, clean |
| `DocumentsReadGapH5` — parent-structure read vs child-entry write judged **compatible** | **PINNED** (current behaviour; relation-level H5 premise confirmed) | `FootprintLemmas.cfg`; flips RED when the relation is fixed |
| **H5 phantom write skew — behavioural** (whole-map read + new-key insert: both txns observe the empty map, both commit; no serial order exists) | **CONFIRMED in the shipped library — pinned in the test suite** (~98% of contended reps on a 12-core host; the static-analysis walker does not close the gap) | `SerializabilityOracleSpec` ("H5 phantom write skew"); flips RED when fixed |
| `DocumentsSiblingInsertsCompatible` — two new-key inserts to one map compatible (H2 enabler) | **PINNED** (correct behaviour; the hazard is the lock aliasing, Spec A) | `FootprintLemmas.cfg` |
| `NoDoubleExec` | **RED — organic, pinned in CI** (TLC search depth ~50, 49-state trace) | H4 counterexample, narrative below |
| `ContractC` | **RED — organic** (depth ~51) | same root cause; drop-and-rerun procedure below |
| `NoExecOnCompleted` | **RED — organic** (depth ~58) | 〃 |
| `NoDoublePublish` | **RED — organic** (depth ~58) | 〃 |
| `TallyNonNegative` | **RED — organic** (depth ~60) | 〃 |
| `CompletionAtMostOnce` | **RED — organic** (depth ~64) | 〃 |
| `NoDroppedSpawn` | **RED at depth ~72 — a model-capacity boundary, not a code defect** | the two-slot fiber bound truncates real behaviour from depth ~72 on (a third concurrent execute is reachable in code); enumeration results beyond that depth would need widened slots |
| `TypeOK` | holds on every completed run | all cfgs |

**H4 counterexample narrative** (validated line-by-line against the code;
minimal 49-state trace (TLC search depth ~50), `Scheduler.cfg`):

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

**Reproducing the non-pinned RED verdicts:** copy `Scheduler.cfg`, list the
invariant of interest (dropping any that fail shallower — the order above is
shallowest-first), and run TLC. Each halts within seconds at the quoted
depth.

**Robustness config** (`scheduler/SchedulerAborts.cfg`, run manually or via
the `workflow_dispatch`-gated CI step): enables nondeterministic failure
injection at every point a `handleErrorWith` can observe (`execute`'s
handler and the submit wrapper in `TxnRuntime.commit`). Measured and pinned:
`CompletionAtMostOnce` violated at **depth 16** — a submit-wrapper abort
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
| `NoDoubleExec` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | execute spawn sites (readiness + tally zero-test) have no dedup guard — EXPECTED RED |
| `TallyNonNegative` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | the `getAndUpdate(_ - 1)` decrement is the only tally sink; stale shared-`unsubSpecs` edges under-run it — EXPECTED RED |
| `NoExecOnCompleted` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | `registerRunning` re-checks neither tally nor completion — EXPECTED RED |
| `ContractC` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | the submit scan is the intended conflict-avoidance mechanism — EXPECTED RED |
| `CompletionAtMostOnce` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | completion sites (success, error handler, submit wrapper) — EXPECTED RED |
| `NoDoublePublish` | `src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala` | `log.commit` publishes; a second fiber re-publishes — EXPECTED RED |

### Footprint relation (anchors in `src/main/scala/bengal/stm/model/runtime/IdFootprint.scala`)

| TLA+ Invariant | Anchor site | Status at anchor |
|---------------|-------------|------------------|
| `LemmaValidatedIdempotent` | `src/main/scala/bengal/stm/model/runtime/IdFootprint.scala` | one `getValidated` application is a fixpoint — HOLDS, makes the memoisation flag sound |
| `DocumentsReadGapH5` | `src/main/scala/bengal/stm/model/runtime/IdFootprint.scala` | the parent rule tests ids only against the other side's update set — PINNED current behaviour (H5) |

## Variable Mapping (Scheduler.tla)

| TLA+ variable | Scala |
|---------------|-------|
| `status[t]` | `AnalysedTxn.executionStatus: Ref[F, ExecutionStatus]` — never demoted, exactly as in the code (nothing writes it after `Running` except a resubmission's `set(Scheduled)`); completion is visible only via `completedCount` |
| `tally[t]` | `AnalysedTxn.dependencyTally: Ref[F, Int]` — shared across incarnations via `copy` |
| `unsubs[t]` | `AnalysedTxn.unsubSpecs: MutableMap[TxnId, F[Unit]]` (edge set; the `F[Unit]` values are the downstream decrements) — shared across incarnations |
| `hasDown[t]` | `AnalysedTxn.hasDownstream: Ref[F, Boolean]` |
| `completedCount[t]` | completions of `AnalysedTxn.completionSignal: Deferred` (counted to detect doubles; the real `Deferred` is first-wins) |
| `inc[t]`, `declared[t]` | incarnation ordinal and that incarnation's validated footprint (`idFootprint`, refined on the dirty path) |
| `snap[t, slot]`, `version[v]` | abstraction of log-entry `initial` captures (per execute fiber — each run builds a private log) and committed var state (values → version counters) |
| `submitPc[t]`, `scanPending[t]`, `curTarget/curDir/curContained` | position inside `submitTxn` / `submitTxnForImmediateRetry` and the scan loop |
| `exec[t, slot]` | in-flight `execute` fibers (two slots — double-spawn must be representable) |
| `unsubW[t, slot]` | in-flight `triggerUnsub` cascades (two slots — the error path spawns a second) |
| `active` | `TxnScheduler.activeTransactions` keys |
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
| R2 | Subscribe triple (tally++, insert, hasDown) → one step | cascades do read and `clear()` the `unsubs` cell concurrently, but every torn-triple interleaving coincides observably with some atomic placement of the triple relative to `snapVals`/`clear` steps; the contains-check stays separate (that gap is H4's home) |
| R3 | acquire/act/release → one guarded step for single-op semaphore regions (`registerRunning`, the active-set remove) | nothing else can run under the held semaphore in a one-op region |
| R4 | Static analysis, `AnalysedTxn` construction, `Deferred` creation → `Init` | pre-protocol, no shared state contention |
| R5 | Concurrent `submitTxnForImmediateRetry` runs of one txn → serialized (one submit workflow per txn) | **unjustified reduction, accepted for Phase 1** — the code runs each dirty fiber's resubmission inline, so two dirty fibers race their resets/scans; that region only exists after a `NoDoubleExec` violation, and enumeration traces from it are re-validated against the code individually; per-slot submit machinery is Phase 2 rework |
| — | `triggerUnsub`'s nonEmpty check and values snapshot NOT collapsed | a concurrent `clear()` between them is a real trace (double-spawned cascades) |
| — | Commit → one atomic truthful step (dirty iff a written var moved since **this fiber's** snapshot; snapshots are per-slot) | Spec B's contract with Spec A (plan §3); Spec A refines lock/validate/publish |
| — | Two exec slots + two cascade slots per txn, `droppedSpawn` ghost | a third spawn is reachable in code (from depth ~72 in this scenario); the ghost turns any silent drop into a `NoDroppedSpawn` violation instead of an invisible truncation |

Failure injection (`AbortsEnabled`): a nondeterministic abort at any point
the corresponding `handleErrorWith` covers. The model does not claim any
specific organic throw site — injection runs measure robustness, and their
verdicts are labelled accordingly.

## Parameter Sweeps & State-Space Data

Measured on 12 cores (Apple x86_64, JDK 21, TLC 2026-07 build):

- **Violation runs** (`Scheduler.cfg` and drop-and-rerun variants): halt in
  **1–3 s** at TLC search depths 50–64 (the `NoDroppedSpawn` boundary run reaches
  depth 72 in ~20 s / 1.2M distinct states); 4k–120k distinct states for
  the defect verdicts. CI-friendly.
- **Full organic sweep** at `MaxInc=2, MaxVer=6` (no protocol invariants):
  **infeasible** — >32M distinct states after 10 min (~18M states/min
  generated, ~3.4M distinct/min), queue still growing at depth 98.
- Consequence: post-fix **green** verification of Spec B needs tighter
  bounds (`MaxInc=1`, smaller `MaxVer`, or 2-txn scenarios) — sized when a
  fix PR needs them (plan Phase 5). Expected-red pins are unaffected.

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
  Double-spawn and double-cascade are the defects under test; the model must
  represent them. A third spawn is dropped, and — since that is reachable
  *before* any invariant fires — every drop sets the `droppedSpawn` ghost so
  `NoDroppedSpawn` reports the truncation explicitly (RED at depth ~72 in
  the shipped scenario).
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
   increment canary for the H4 double-execution family, and a **pinned H5
   reproduction** — the whole-map-read + new-key-insert phantom write skew
   reproduces in the shipped library (measured ~98% of contended reps, on both a
   12-core host and a 2-thread pool) and the test
   asserts it keeps reproducing until fixed, exactly as `check_expected.sh`
   pins TLC counterexamples. Whole-map reads are excluded from the
   *generated* suite for that reason; `waitUntil`/retry workloads join in
   Phase 2. Deterministic replay tests for other confirmed traces arrive
   with their fixes.
