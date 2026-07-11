# Formal Specification Plan — Scheduler & Commit Protocols

**Status:** Phases 0–2 executed and merged (2026-07-11, PRs #55–#59) —
`specs/README.md` is now the verdict source of truth. **H4 CONFIRMED
organically and FIXED the same day** (all six Spec B safety invariants were
violated with a minimal counterexample pinned in CI at TLC search depth ~50;
the fix — admission gate, fresh incarnations, exactly-once cascades — turned
the full invariant set exhaustively green at the same bounds and the CI pin
flipped to expected-clean; see the verdict table).
**H5 CONFIRMED at both levels and FIXED (2026-07-11)**: confirmed
relation-level by `FootprintLemmas.tla` (then `DocumentsReadGapH5`) and
behaviourally by the oracle test's probe (~98% of contended
whole-map-read + new-key-insert reps non-serializable); fixed by adding a
third conjunct to `isCompatibleWith` (child-entry writes conflict with
parent-structure reads), with the lemma flipped to
`DocumentsParentReadChildWriteCaught` and the probe flipped to a
serializability regression test. The oracle test landed as PR #56; the fix
follows it. Spec A's model-level `CommitSnapshotValid` check remains Phase 3
(now expected to HOLD in accurate mode for this idiom).
**H1 CONFIRMED and FIXED (2026-07-11)** in Phase 2 — a hard deadlock at TLC
depth 39 in the retry model; fixed by scanning `activeTransactions` for
footprint conflicts and *then* re-checking the read set, inside the
retry-semaphore region, before parking (the order is load-bearing). A
pre-existing spurious self-wake spin surfaced in the same verification and was
fixed by keying the retry map on `TxnId` (see the verdict table).
**Phase 3 executed (2026-07-11): Spec A landed and BOTH remaining hypotheses
CONFIRMED — H2 and H3 are open defects, pinned red in CI.** H2: a lock-order
deadlock via the map-lock fallback, reachable on **fully accurate footprints**
(`withLock` sorts by the log key while a new-key entry resolves to its *map's*
lock — two decoupled id spaces). H3: write skew when the static-analysis
fallback under-declares — unsound in **both** directions and reachable from
ordinary read-your-own-write code (198/200 contended reps skew). Fixes are
pre-validated by negative controls NC-1/NC-2 and follow in their own PRs.
Two results came out of Spec A besides the verdicts: **the H5 fix is what
discharges Spec B's atomic-commit abstraction** (§3's refinement obligation),
and **the commit protocol alone is not serializable** — removing only the
Contract C guard breaks it with every lock intact (NC-4), so footprint
accuracy is a safety precondition, not a throughput knob.
Plan below retained as written (critique-ratcheted: self-review +
independent agent review applied). **Invariant naming note:** implementation
tightened some planned property names — `TallyIsUpstreamCount` shipped as
the weaker-but-sufficient `TallyNonNegative`, `CompletionExactlyOnce`'s
safety half as `CompletionAtMostOnce`, and `StatusWellFormed` as
`NoDoubleExec` + `NoExecOnCompleted`; `NoDroppedSpawn` was added as a
model-capacity detector. `specs/README.md` uses the shipped names. Line
references in the plan body below predate the `// SPEC:` anchor insertions
(off by up to 18 lines in `TxnRuntimeContext.scala` / `IdFootprint.scala`);
the specs and `specs/README.md` cite symbols and are authoritative.
**Tooling:** TLA+ / TLC (echidna house pattern)
**Scope:** `TxnRuntimeContext.scala`, `TxnLogContext.scala` (commit path), `IdFootprint.scala`, `TxnVarRuntimeId.scala`

---

## 1. Objective

Formally specify bengal-stm's two concurrency-critical protocols in TLA+, model-check
them with TLC, and wire mechanical spec↔code alignment so the specs stay honest as the
code evolves. The recent bug history (#50 retry-in-handleErrorWith, #51 TxnVarMap
new-key concurrency, #52 completionSignal deadlock) is concentrated exactly in this
protocol layer.

**These specs will verify (protocol-level correctness):**

- The transaction scheduler: dependency-graph construction, dependency tallies,
  execution statuses, unsubscribe cascades, the retry map, and park/wake — including
  the error-recovery paths.
- The commit protocol: sorted lock acquisition, dirty-check validation, version
  publication.
- The footprint compatibility relation itself (shared module, §3).
- The interface between scheduler and commit (Contract C, §3).

**They will not verify:**

- The free-monad compiler / static-analysis walker (`TxnCompilerContext`) — though
  H5 requires *reading* its output conventions once (§6).
- `TxnLog` map-merge bookkeeping (`getVarMap`/`extractMap` semantics).
- Value-level semantics (vars are abstracted to version counters).
- Cancellation (all protocol regions of interest sit inside `uncancelable`).
- `TxnVarMap.internalStructureLock` — a second locking layer acquired during commit
  publication *underneath* the modelled `commitLock`s (`TxnVarMap.scala:86`). It is a
  leaf lock (never held while acquiring a commit lock), so it sits safely below
  Spec A's atomic-publish abstraction; recorded here because it is the only commit-path
  lock the specs do not represent.
- Type-erasure hazards (Scala-level; property-test territory).
- Runtime-ID hash collisions (§10, treated as an explicit `ASSUME`).

## 2. Tooling & Layout (echidna house pattern)

Mirror `../echidna` exactly:

```
specs/
  README.md               # scope, run commands, cross-reference tables, variable
                          # mapping, parameter sweeps, design decisions, verdicts
  verify_anchors.sh       # ported from echidna; parses README tables, greps Scala
  common/
    Footprint.tla         # IdFootprint + isCompatibleWith, exactly once (§3)
    Footprint.cfg         # footprint-only lemma checks (symmetry, validation
                          # idempotence, parent-rule cases)
  scheduler/
    Scheduler.tla         # Spec B (safety)
    Scheduler.cfg         # safety config (symmetry, larger bounds)
    SchedulerLiveness.cfg # liveness config (no symmetry — TLC restriction; minimal bounds)
  commit/
    CommitProtocol.tla    # Spec A
    CommitProtocol.cfg          # accurate-footprint mode (two maps — see H2)
    CommitProtocolFallback.cfg  # under-approximated-footprint mode (H3)
.github/workflows/specs.yml     # verify-anchors + model-check jobs (ubuntu-latest,
                                # temurin 21, tla2tools v1.8.0, -workers auto)
```

Conventions carried over: raw TLA+ (no PlusCal), heavily-commented module headers with
phase breakdown and a "Scala correspondence" note per action, `.cfg` per checkable
concern, shared operators in a common module (echidna's `BinomialBeta.tla` precedent),
`tla2tools.jar` gitignored and downloaded in CI, `*_TTrace_*` TLC trace dumps
gitignored (echidna committed one by accident — avoid repeating).

CI runs on GitHub-hosted `ubuntu-latest` (as echidna does), not Blacksmith — keeps the
2-vcpu Blacksmith minutes for sbt. Path triggers: `specs/**` plus the four in-scope
source files. **CI budget is arithmetic, not aspiration:** with ~6 configs at a
single-digit-minute target each, the model-check job either matrixes per-config or
raises its timeout to ~30 min; config sizes are locked only after the Phase 1
state-count spike (§8) measures real states/minute on the CI runner class.
Deeper bounds are documented as manual sweeps in `specs/README.md`.

## 3. Architecture: Two Specs, a Shared Relation, and an Interface Contract

A single monolithic model (scheduler + locks + dirty checks + retry map) would be
unwieldy and slow to check. The split is sound because the two protocols meet at one
narrow interface:

> **Contract C:** no two transactions whose *declared* footprints are incompatible
> (`IdFootprint.isCompatibleWith = false`) are ever concurrently inside their
> execute/commit window.

- **`common/Footprint.tla`** encodes `IdFootprint` and `isCompatibleWith` **exactly**
  — raw-ID intersection plus the parent rule (`IdFootprint.scala:52-58`), and
  `getValidated` (`IdFootprint.scala:26-34`) — once, EXTENDed by both specs. Scenario
  configs must never hand-pick "compatible/incompatible" pairs; compatibility is always
  computed by this module (H2's original misdiagnosis in an earlier draft of this plan
  — see §6 — is exactly the error this rule prevents). The module gets its own tiny
  lemma config: symmetry of `isCompatibleWith`, idempotence of `getValidated`,
  and the parent-rule case table.
- **Spec B** (scheduler) *checks* Contract C, plus liveness of the retry machinery.
- **Spec A** (commit) *assumes* Contract C and checks strict serializability and
  deadlock freedom of the lock/validate/publish sequence.

**Why this decomposition is load-bearing — the central design fact:** read-only log
entries are never validated at commit time (`TxnLogReadOnlyVarEntry.isDirty =
pure(false)`, `lock = None` — `TxnLogContext.scala:70-74`) and commit locks cover only
the write set. The commit protocol *alone* therefore does not give serializability;
the scheduler's conflict avoidance is the only defence against stale reads and write
skew. Consequently the accuracy of declared footprints is a safety precondition, and
the static-analysis fallback to a partial or empty footprint
(`TxnRuntimeContext.scala:382-387`) is a soundness boundary, not an optimisation
detail (hypothesis H3) — and any gap in `isCompatibleWith` itself is a direct hole in
the safety story (hypothesis H5).

**Conditional validity of the assume-guarantee split.** Spec B is *expected to refute*
Contract C on the shipped code (H4; H5 attacks it at the relation level). Until those
verdicts resolve — fix landed or interleaving argued unreachable — Spec A's
accurate-mode "holds" verdicts describe the **post-fix system**, not the shipped
library. The same condition attaches to §3's refinement justification below. This is
stated in the Phase 3 exit criteria and must be stated in `specs/README.md`'s verdict
table rather than silently assumed.

**Refinement obligation (documented, not machine-checked initially):** Spec B treats
commit as one atomic version-bump action. Spec A justifies this coarsening: under
Contract C with accurate footprints, conflicting commits are mutually excluded, so
their internal steps cannot interleave observably. A machine-checked refinement
mapping (B's atomic commit ⊑ A) is a stretch goal (§8).

## 4. Spec A — `commit/CommitProtocol.tla`

**Purpose:** verify the `withLock` + `isDirty` + `commit` sequence
(`TxnLogContext.scala:997-1039`, `TxnRuntimeContext.scala:279-323`).

**State (↔ code):**

| TLA+ variable | Scala correspondence |
|---|---|
| `version[v]` | abstract commit counter per `TxnVar`/map entry/map structure (values abstracted away) |
| `lockHolder[l]` | `commitLock: Semaphore[F]` per var and per map (structural); map-entry writes resolve to the entry's own lock or the structural lock per the fallback rule |
| `txnPc[t]` | position in read → sort → acquire(k) → validate → publish/release |
| `snapshot[t][v]` | `initial` captured in log entries during the read phase |
| `readSet[t]`, `writeSet[t]` | the txn's *actual* access sets |
| `declared[t]` | the txn's *declared* footprint (= actual, or an under-approximation, per mode) |
| `lockSeq[t]` | lock IDs sorted by runtime-ID value (`TxnLogContext.scala:1029-1036`) |

**Model design decisions:**

- **Runtime IDs are arbitrary distinct naturals chosen adversarially in the config.**
  Real IDs are UUID-hash-derived (`TxnStateEntity.scala:36-37`), so their order is
  pseudorandom relative to creation order — every ordering class is reachable in some
  program. Configs pin concrete orderings that exercise each class (for H2: entry IDs
  of map 1 and map 2 interleaved in opposite orders).
- **The map-lock fallback is modelled faithfully:** a new-key map entry contributes
  the *map's* structural lock at the *entry's* position in the sorted sequence
  (`TxnLogContext.scala:276-279`), while a map-structure write contributes the same
  lock at the *structure's* position. Lock *identity* is state-dependent — the aliasing
  at the heart of H2.
- **Dirty check matches the code, not the textbook:** only write-set entries are
  validated (`version[v] = snapshot[t][v]` for `v ∈ writeSet[t]`); read-only entries
  contribute nothing. Do not "fix" this in the model — exposing its consequences is
  the point.
- **The retry branch is in scope but modelled as the code has it, which is weaker
  than it looks:** the `TxnLogRetry` re-validation under lock
  (`TxnRuntimeContext.scala:306-319`) delegates to `validLog.isDirty`
  (`TxnLogContext.scala:1088-1089`), whose read-only entries are always clean and
  lockless — for the typical read-then-retry transaction it acquires **no locks and
  can never report dirty**. The model must reproduce this vacuity (park decisions
  consult no versions); a model that "helpfully" re-validates before parking would
  mask H1-adjacent traces. Pinned in the fidelity table (§5).
- **Lock acquisition is one step per lock** (blocking); validate-and-publish happens
  while holding exactly the write-set locks; dirty → release all, refine declared
  footprint from actual, re-enter read phase (models `TxnResultLogDirty` →
  `submitTxnForImmediateRetry`).
- **Contract C as an action guard:** in accurate mode, a txn may enter its
  execute window only if no txn with an incompatible declared footprint (computed by
  `common/Footprint.tla`, never hand-assigned) is inside its own. In fallback mode the
  guard uses the under-approximated declared footprints — TLC then explores the
  concurrency the real scheduler would permit.

**Properties:**

| Property | Kind | Expected initial verdict |
|---|---|---|
| `NoWaitsForCycle` — no cycle in the waits-for graph over lock holders/waiters (explicit invariant, not TLC's built-in deadlock detection, which terminal all-done states would trip) | safety | **counterexample** at two-map config (H2) |
| `CommitSnapshotValid` — at publish, every `v ∈ readSet` (structure reads included) still has its snapshot version | safety (strong form of strict serializability) | ~~counterexample in accurate mode via phantom insert (H5)~~ — expected to HOLD post-H5-fix; **counterexample** in fallback mode (H3, write skew) |
| `PublishedExactlyOnce` — a txn's writes are published at most once per incarnation | safety | holds |
| `EventualCommit` — every non-retry txn eventually publishes (weak fairness; see §9 fairness caveat) | liveness | holds in accurate mode, post-fix |

If `CommitSnapshotValid` fails in accurate mode *other than* via the H5 mechanism,
fall back to an explicit history-based serializability check (maintain committed
history, assert a serial order exists) before declaring an anomaly — snapshot
validity is sufficient but not necessary for serializability. Build that checker
only if needed.

**Config:** 2–3 txns, 2 plain vars + **2 maps** (structure + ≤2 entries each — two
maps are required for H2's accurate-mode cycle; one map cannot produce two shared
locks between footprint-compatible txns). Symmetry over txns in safety runs only.

## 5. Spec B — `scheduler/Scheduler.tla`

**Purpose:** verify the scheduler protocol of `TxnRuntimeContext.scala:53-361` —
graph building, tallies, statuses, unsub cascades, retry map, park/wake, error
recovery — at the granularity where the real races live.

**State (↔ code):**

| TLA+ variable | Scala correspondence |
|---|---|
| `active` | `activeTransactions: MutableMap[TxnId, AnalysedTxn[_]]` |
| `status[t]` | `executionStatus: Ref[F, ExecutionStatus]` (+ `Completed` for bookkeeping) |
| `tally[t]` | `dependencyTally: Ref[F, Int]` |
| `unsubs[t]` | `unsubSpecs: MutableMap[TxnId, F[Unit]]` (set of downstream ids) — **shared across incarnations** (case-class `copy`, line 348) |
| `hasDownstream[t]` | `hasDownstream: Ref[F, Boolean]` |
| `completed[t]` | `completionSignal: Deferred` (completed-once flag) |
| `declared[t, i]` | declared footprint **per incarnation** `i` — refined on the dirty path (`TxnRuntimeContext.scala:346-349`); Contract C is checked against the *current* incarnation's footprint |
| `snapshot[t]` | versions of the txn's actual footprint captured at execute start — needed to force the dirty outcome truthfully (dirty iff a written var's version changed) rather than nondeterministically |
| `retryMap` | `retryMap: MutableMap[IdFootprint, F[Unit]]` (footprint → set of parked ids; the code's `>>` chaining = fire-together, a set suffices) |
| `graphSem`, `retrySem` | the two semaphores, as explicit holder variables |
| `version[v]` | coarse var state; commit = one atomic bump of the write set (§3) |
| `fiberPc[f]` | program counters for in-flight fibers (submit scans, triggerUnsub cascades, executes) |

**Atomicity mapping (the fidelity crux).** The model's atomic step = one operation on
one shared mutable object:

- each `Ref` `get`/`set`/`update`/`getAndUpdate` — one step (atomic in CE);
- each `TrieMap` read/insert/remove — one step;
- check-then-act sequences (e.g. `unsubSpecs.contains` then `addOne`,
  `TxnRuntimeContext.scala:241-254`) — two steps, interleavable;
- semaphore-guarded regions are **not** atomic: they serialise against the same
  semaphore only. Steps of `triggerUnsub` fibers (which run *outside* the graph
  semaphore, `TxnRuntimeContext.scala:184`) interleave freely with in-region
  subscribe steps;
- `.start`ed fibers (`testAndLink` scans, `triggerUnsub`, spawned `execute`s,
  `checkRetryQueue`) are separate model fibers; `joinWithNever` inside the region
  pins their completion before semaphore release, as in the code;
- **park decisions consult no versions** — the retry-path re-validation is vacuous
  for read-only logs (§4); the model must not re-check freshness before parking.

This mapping is recorded as a table in `specs/README.md`; every action comment in the
spec cites the line range it models.

**Finiteness engineering (required, not assumed).** Fiber-level interleaving at this
granularity explodes fast, and TLC needs the model to be finite by construction:

- No dynamically spawned model fibers: scan work, unsub cascades, and wake-ups are
  encoded as **pending-work sets** drained one step at a time (equivalent
  interleavings, bounded state), with a fixed small fiber pool only where genuine
  concurrent identity matters (the `triggerUnsub`-vs-resubmit race needs two).
- Explicit bounds as CONSTANTS with `CONSTRAINT` backstops: max incarnations per txn
  (dirty/retry loops), max version per var, max simultaneous submissions.
- Liveness caveat recorded in the spec header: `CONSTRAINT`-truncated behaviours can
  mask liveness violations near the bound; liveness configs choose bounds where
  the interesting cycle fits well inside the horizon.
- **Phase 1 exit criterion includes a measured state-count spike** (states, distinct
  states, minutes on a CI-class runner) before config sizes are locked (§8).

**Behaviours modelled:** `submitTxn`, `submitTxnForImmediateRetry` (including its
Running/Scheduled asymmetric subscribe, lines 106-133, and the shared
`unsubSpecs`/`dependencyTally` refs across incarnations), `registerRunning`, `execute`
outcome dispatch (success / retry / dirty / failure — dirty forced truthfully from
`snapshot`), `registerCompletion` + `triggerUnsub`, `submitTxnForRetry` (park),
`checkRetryQueue` (wake), `checkExecutionReadiness` and
`unsubscribeUpstreamDependency` zero-tests, **and the error-recovery branches**: a
nondeterministic step-failure action driving `execute`'s `handleErrorWith`
(`TxnRuntimeContext.scala:355-358`) — which runs `registerCompletion` a **second**
time and starts a second `triggerUnsub` over the shared `unsubs`, a double-unsub /
negative-tally hazard (H4's failure family via the #52-fix path) — plus the submit
wrapper's error completion (`TxnRuntimeContext.scala:407-411`). These paths are what
make `CompletionExactlyOnce` a meaningful check; scoping them out would verify only
the happy path of the very fix (#52) that motivated this work.

**Properties:**

| Property | Kind | Expected initial verdict |
|---|---|---|
| `TallyIsUpstreamCount` — `tally[t]` equals the number of live upstream edges pointing at `t` | safety (inductive-strength; catches tally corruption) | **counterexample** (H4) |
| `ContractC` — no two Running txns have incompatible declared footprints (current incarnations; relation from `common/Footprint.tla`) | safety (the Spec A interface) | **counterexample** (H4 corollary) |
| `StatusWellFormed` — per incarnation: NotScheduled → Scheduled → Running → Completed, no double-fire of `execute` | safety | to determine — no explicit guard exists in the code |
| `CompletionExactlyOnce` — `completionSignal` completed exactly once per submitted txn, including error paths (the #52 class) | safety + liveness | safety: to determine (double-`registerCompletion` path); liveness follows H1 verdict |
| `ParkedConsistency` — parked ⇒ not in `active`, tally = 0 | safety | holds |
| `NoLostWakeup` — a parked txn flagged by a conflicting commit after its park point is eventually resubmitted (flag-based encoding) | liveness (weak fairness; §9 caveat) | **counterexample** (H1) |
| `EventualCompletion` — every submitted txn (with a satisfiable retry predicate) eventually completes | liveness | follows H1 verdict |

**Config:** 3 txns / 2 vars for safety (symmetry over vars), 2 txns / 1–2 vars for
liveness. Footprints are constants per txn *base*, always run through
`common/Footprint.tla` for compatibility; retry txns get a predicate that becomes
true after a designated write.

## 6. Hypothesis Regression List (model acceptance criteria)

Five concrete suspected defects came out of the pre-planning code read and the plan
critique. They double as **fidelity acceptance tests**: the finished specs must either
reproduce each counterexample or return *no counterexample at the stated bounds
plus a written mechanism argument* for why the interleaving is excluded (bounded TLC
runs cannot prove unreachability — H2's own history below shows how a too-small
config silently excludes a behaviour). A spec that can do neither is modelling a
different algorithm and goes back for granularity rework before any green invariant
is trusted. Each row must record why its config bounds suffice to exhibit the claimed
behaviour.

| # | Hypothesis | Spec / property | Code site | Expected |
|---|---|---|---|---|
| H1 | **Park/submit lost-wakeup window.** On `TxnResultRetry`, `registerCompletion` (removes txn from `active`) precedes the park into `retryMap`. A conflicting writer submitted in the gap sees the txn in neither structure (no graph edge, no wake), commits, and nothing ever wakes the parked txn — wakes fire only on submission. | B / `NoLostWakeup` | `TxnRuntimeContext.scala:330-359`, `61-95` | **CONFIRMED (2026-07-11)** as a hard deadlock at depth 39 in the Phase 2 retry model (2 txns, 1 var — as predicted; manifests as a dead-end state, so no fairness machinery was needed). **FIXED same day**: `submitTxnForRetry` scans `activeTransactions` for footprint conflicts and then re-checks the read set (`hasChangedSinceRead`) inside the retry-semaphore region before parking. Both checks are required (sweeps fire at submission, BEFORE the conflicting commit) and **the order is load-bearing** — staleness must be the last read before the insert; the reverse order still deadlocks (negative control NC-A), which a first draft of the fix shipped and the code-order-split model caught. A second, pre-existing defect surfaced in the same verification: a parked transaction's OWN submission sweep woke it (self-incompatible footprint), an unbounded spurious spin — fixed by keying the retry map on `TxnId` and skipping the submitter. Post-fix `SchedulerRetry.cfg` verifies exhaustively with deadlock detection on (CI, expected clean) |
| H2 | **Lock-order inversion via the map-lock fallback — two maps.** New-key entries acquire the *map's* structural lock at the *entry's* hash-derived sort position. Two txns inserting fresh keys into maps M1 and M2 have pairwise-compatible footprints (disjoint entry IDs; nobody writes a structure ID), yet both hold `{M1.lock, M2.lock}` — and adversarial ID ordering acquires them in opposite orders. Circular wait under accurate footprints. *(A one-map variant with a shared plain var is footprint-incompatible and thus Contract-C-excluded — an earlier draft of this plan got that wrong, which is why compatibility is computed, never hand-assigned; the one-map variant remains reachable in fallback mode.)* | A / `NoWaitsForCycle` | `TxnLogContext.scala` (`TxnLogValid.withLock`, `TxnLogUpdateVarMapEntry.lock`), `TxnVarMap.getRuntimeId` | **CONFIRMED (2026-07-11) — OPEN, pinned red.** 17-state counterexample, exhaustive at depth 25 (`specs/commit/CommitH2.cfg`). The mechanism is sharper than predicted: a map entry has **two runtime ids** — the *existential* id hashed from `(mapId, key)` (which the footprint always uses) and the entry `TxnVar`'s *own* id (which the log keys it by once the key exists). `withLock` sorts by the **log key**; the lock resolves to the **map**. The two id spaces are unrelated, so sorting cannot order the acquired locks at all. **Bounds note (why 2 txns + 2 maps is minimal AND sufficient):** two *compatible* txns can share a lock only via this fallback — sharing a var lock or an entry lock means both write it (raw-id overlap ⇒ incompatible ⇒ Contract-C-excluded), and sharing a map lock via a structure *write* is caught by the parent rule. A 2-cycle needs two shared locks, hence two maps. **Fix pre-validated (NC-1): sort by the lock's OWNER id, not the log key** — lock ↔ owner-id is a bijection, so one ascending order is a total order and no cycle can form |
| H3 | **Write skew when declared footprints under-approximate.** Reads are never commit-validated and read locks don't exist, so two txns in the empty-footprint fallback (`r x, w y` vs `r y, w x`) both validate and both publish. The refinement path never triggers because neither log is dirty. | A / `CommitSnapshotValid` (fallback mode) | `TxnLogContext.scala` (`TxnLogReadOnlyVarEntry.isDirty`/`lock`), `TxnRuntimeContext.scala` (`TxnRuntime.commit`'s `handleErrorWith`) | **CONFIRMED (2026-07-11) at both levels — OPEN, pinned red.** Model: `CommitH3.cfg`. **Two findings beyond the hypothesis.** (1) *Unsound in BOTH directions*: an under-declared txn with **no reads at all** still breaks a correctly-declared peer, because its undeclared writes invalidate that peer's reads (`CommitH3Writer.cfg`). The fallback is safe only when NEITHER party reads — which kills the tempting cheap fix of flagging only read-incompleteness. The one safe case (pure write-write) is pinned clean in `CommitDirty.cfg`. (2) *Reachable from ordinary code*: `staticAnalysisCompiler` executes real reads but never applies writes, so reading back a key the txn just wrote yields `None` during analysis and `Some(v)` at run time; a partial continuation on it throws during analysis only, and everything after the throw goes undeclared. **198/200 contended reps skew** (`StaticAnalysisFallbackSpec`, pinned red) — the same signature as H5 pre-fix, and holding the transaction fixed while making the continuation *total* removes the skew, which pins the throw as the cause rather than the shape. This is a **live defect**, not a documented boundary. (3) *Which branch fires*: the reachable path takes `handleErrorWith`'s **`StaticAnalysisShortCircuitException`** branch — a **partial** footprint — not the `case _ => IdFootprint.empty` one. `CommitH3Partial.cfg` models that partial footprint and reproduces the same violation, so the verdict does not rest on the idealised empty case; under-approximation is unsound whatever its size. **Fix (decided): (A) flag the under-approximation ⇒ incompatible with everything (pre-validated, NC-2), plus (B) give the analyser a shadow log so read-your-own-write stops throwing** — A alone is sound but serializes ordinary code against everything; B keeps A's cost from being paid |
| H4 | **Incarnation confusion on dirty resubmit.** `this.copy(idFootprint = …)` shares `unsubSpecs` and `dependencyTally` refs with the previous incarnation, and `triggerUnsub` is a fire-and-forget fiber racing the resubmit's graph scan. A stale `unsubSpecs.contains` hit skips a needed subscription (tally under-count → premature execute, Contract C breach) and stale unsubs can drive the tally negative. The double-`registerCompletion` error path (§5) reaches the same failure family without a dirty retry. | B / `TallyIsUpstreamCount`, `ContractC`, `CompletionExactlyOnce` | `TxnRuntimeContext.scala:184, 238-254, 346-349, 355-358` | **CONFIRMED (2026-07-11)** — organic counterexample, variant mechanism: the resubmission's reversed edge landed on a txn whose execute fiber was already spawned (`registerRunning` re-checked nothing), and the previous incarnation's cascade drained the new incarnation's edge from the shared `unsubSpecs`, spawning a second execute → double commit, double completion, negative tally, Contract C breach. **FIXED same day**: `admitForExecution` (status CAS + zero-tally under the graph semaphore — at most one fiber per incarnation enters the commit window), `freshIncarnation` at every resubmission site (dirty, retry, parked-wake — old cascades drain dead refs), and a `cascadeFired` exactly-once gate on `triggerUnsub`. All six invariants + `NoDroppedSpawn` + deadlock-freedom verified exhaustively at the defect scenario's own bounds (~24k states, ~2s — pre-fix >32M unfinished); the CI pin flipped from expected-red `NoDoubleExec` to expected-clean |
| H5 | **Phantom write skew under fully accurate footprints.** The parent rule in `isCompatibleWith` checks each side's IDs only against the other side's *update* IDs (`IdFootprint.scala:52-58`) — a child-entry **write** is never tested against a parent-structure **read**. So "read whole map" (`readIds ∋ structureId`) vs "insert new key" (`updatedIds ∋ entryId(parent=structureId)`) is judged compatible; the structure RO entry is never validated and holds no lock (`TxnLogContext.scala:143-147`); two check-then-insert txns both see the key absent and both commit. Breaks serializability with *no* fallback involved — the relation itself has the hole. | A / `CommitSnapshotValid` (accurate mode); `common/Footprint.tla` lemma table documents the relation gap | `IdFootprint.scala:52-58`, `TxnLogContext.scala:143-147` | **CONFIRMED (2026-07-11)** — relation level (then-`DocumentsReadGapH5`) and behaviourally (`SerializabilityOracleSpec` probe: both txns observed the empty map and both committed in ~98% of contended reps; pre-step answered empirically — the walker does *not* over-approximate whole-map reads to structure writes). **FIXED same day**: fix decision resolved as extending the parent rule (third conjunct — child-entry writes conflict with parent-structure reads); lemma flipped to `DocumentsParentReadChildWriteCaught`, probe flipped to a serializability regression. Spec A's model-level `CommitSnapshotValid` check remains Phase 3, now expected to HOLD in accurate mode for this idiom |

**Verdict handling:** each confirmed counterexample becomes a decision point, not an
automatic fix: (a) fix the code (separate PR: fix + deterministic replay/stress test +
TLC re-run flipping the expected verdict), or (b) accept and document as a known
boundary in `specs/README.md` (plausible for H3 if the fallback is deemed acceptable
risk — though a cheap sound fix exists: an under-approximation *flag* that makes the
footprint incompatible-with-everything, trading throughput for soundness on the
fallback path only; for H5 the likely fixes are extending the parent rule to cover
parent-*reads* or validating structure reads at commit). Hypotheses that produce no
counterexample get the bounded-verdict treatment described above, recorded in the
spec header. **Once specs land, the verdict table in `specs/README.md` is the source
of truth and this section becomes a pointer to it.**

## 7. Spec↔Code Alignment Mechanism

Echidna's three-layer pattern, adapted for Scala/CE:

1. **Source anchors + cross-reference tables.** Every invariant in §4/§5 gets a
   `// SPEC: <InvariantName>` comment at the Scala line that upholds it, a row in the
   `specs/README.md` cross-reference tables, and `verify_anchors.sh` (direct port —
   Scala's `//` comments make the grep identical, and the existing `src/` path regex
   already matches `src/main/scala/…`) fails CI if an anchor disappears. Anchors are
   added **incrementally in each spec PR**, not retrofitted at the end. For
   expected-red invariants, anchor placement is ill-defined until the fix lands —
   those rows anchor the *mechanism site* (e.g. the subscribe dedup check for
   `TallyIsUpstreamCount`) and move to the fix site in the Phase 5 PR. Same known
   limitation as echidna: anchors moved to a wrong line *within* the declared file
   remain a reviewer responsibility.

2. **Semantic property tests** (`src/test/scala/spec/`, package `ai.entrolution.spec`
   — test packages must stay outside `stm` to avoid `private[stm]` member shadowing):
   - **Serializability oracle test (pulled forward to Phase 1 — it is
     TLA-independent and exercises the real code):** a deliberately naive reference
     STM (one global lock, sequential commits) as oracle; ScalaCheck generates small
     concurrent workloads (2–4 txns over 2–3 vars/map keys, including retry, map
     new-key inserts, and whole-map reads — the H5 idiom); the real STM's observable
     outcome must be reachable by some serial order in the oracle. This generalises
     the conserved-quantity checks in the existing `StmStressSpec` (which validates
     specific invariants like transfer totals) to arbitrary generated workloads, and
     runs in the normal 2.13/3 test matrix.
   - **Counterexample replay tests:** each confirmed TLC trace is hand-translated
     into a test. Honest caveat: `TestControl` gives a deterministic scheduler, not
     arbitrary interleaving control — where a trace needs a precise interleaving,
     replay tests use `Deferred`-based sync points or a bounded stress loop asserting
     the fixed invariant. Named after the TLA+ action sequence they replay.
   - **(Stretch) sync-point seam:** a no-op `private[stm] def tracePoint(label): F[Unit]`
     at the ~8 protocol edges (post-completion/pre-park, post-scan/pre-subscribe, …),
     overridable in tests to enforce exact interleavings — upgrades replay tests from
     stress loops to deterministic reproductions, and opens the door to full trace
     validation later. Zero production cost; only added if stress-loop replay proves
     flaky.

3. **CI (`specs.yml`)** mirroring echidna's: `verify-anchors` job (2-min timeout,
   Java-free) gating a `model-check` job (temurin 21, TLC 1.8.0, `-workers auto`),
   per-config matrix or ~30-min timeout per §2, running every `.cfg`. Plus a
   CONTRIBUTING.md note: PRs touching the four in-scope files must update the specs
   or state why no protocol behaviour changed.

## 8. Phasing

Each phase is one PR (house `/commit` + `/pr` flow). Estimates are focussed working
days, not calendar days.

| Phase | Content | Est. | Exit criterion |
|---|---|---|---|
| 0 | Scaffolding: `specs/` tree, README skeleton, `verify_anchors.sh` port, `specs.yml`, gitignore entries | 0.5d | CI green with empty-but-wired pipeline (may fold into phase 1's PR) |
| 1 | `common/Footprint.tla` + lemma config; Spec B core safety: submit/graph/tally/unsub/complete + error-recovery branches (no retry map); `TallyIsUpstreamCount`, `ContractC`, `StatusWellFormed`, `CompletionExactlyOnce`(safety), `ParkedConsistency`; **H4 verdict**; **state-count spike** to size configs; **serializability oracle test** (TLA-independent, can land as a sibling PR) | 3–4d | TLC runs green-or-expected-red on measured, locked configs; H4 trace or bounded-verdict note in hand; oracle test in the matrix |
| 2 | Spec B retry + liveness: retryMap, park/wake, `hasDownstream` branch, fairness; `NoLostWakeup`, `EventualCompletion`; **H1 verdict** | 1–2d | liveness config completes within budget; H1 verdict recorded; any liveness counterexample validated line-by-line against the code before acceptance (§9) |
| 3 | Spec A: locks/dirty/publish with fallback lock identity, two-map accurate config + fallback config; H5 compiler-footprint pre-step; **H2, H3, H5 verdicts** | 2–3d | both configs run; verdicts recorded **with their Contract-C-conditionality noted** (accurate-mode "holds" results are contingent on H4/H1/H5 resolutions — §3) |
| 4 | Alignment completion: remaining anchors + cross-reference tables, replay/stress tests for confirmed traces, CONTRIBUTING note, README completion (variable maps, atomicity table, sweeps, design decisions, verdict table as source of truth) | 1–2d | `verify_anchors.sh` green; README at echidna standard |
| 5 | Fix PRs for confirmed defects (one per hypothesis: fix + replay test + flipped TLC verdict + anchor moved to fix site), then stretch items as appetite allows | open | each fix lands with its regression evidence; Spec A accurate-mode results re-validated once Contract C holds |
| — | **Stretch (unscheduled):** machine-checked refinement B ⊑ A; Apalache inductive-invariant pass over `TallyIsUpstreamCount`; full trace validation via the sync-point seam; strong-fairness/FIFO-queue escalation if §9's fairness caveat bites | — | — |

Total for phases 0–4: **8–11 focussed days.** Phase ordering note: B before A because
B's verdicts (H1/H4) are pure protocol races with no policy judgement attached —
faster payoff and the strongest fidelity test of the modelling approach — and because
Contract C (checked by B) is the assumption A consumes. The oracle test rides with
Phase 1 because it is the cheapest real-code evidence in the whole plan and depends
on nothing TLA-related.

## 9. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| **Fidelity** — model atomicity coarser than CE reality ⇒ verifying a different algorithm | Atomicity mapping table (§5) reviewed against code line-by-line; H1–H5 as acceptance tests — a model that can't reproduce suspected races is rejected, not trusted |
| **State explosion**, especially with fiber-level granularity | Finiteness engineering (§5): pending-work sets, no dynamic fiber spawning, CONSTANT bounds + CONSTRAINT backstops; measured state-count spike before configs lock; split cfgs; symmetry in safety runs only (TLC forbids symmetry + liveness); deep sweeps manual |
| **Weak fairness is not CE-faithful** — WF on a contended acquire isn't continuously enabled, so TLC can emit starvation lassos CE's FIFO semaphore excludes; conversely a spurious lasso could be misread as the H1 trace | Every liveness counterexample is validated line-by-line against the code before acceptance; pre-planned escalation: strong fairness on acquire actions or an explicit FIFO wait-queue model (stretch row, §8) |
| **Bounded runs oversold** — "no counterexample" at small bounds is not unreachability | Verdict category is "no counterexample at stated bounds + written mechanism argument" (§6); each hypothesis row justifies its bounds; H2's one-map/two-map history kept as the cautionary example |
| **Spec drift** | Anchors added per-PR + path-triggered CI + CONTRIBUTING rule (§7) |
| **False-alarm anomalies** — snapshot-validity stronger than serializability | History-based fallback checker, built only if the strong form fails outside the H5 mechanism (§4) |
| **Misjudged intent** — a "defect" may be a deliberate trade-off (H3 fallback likeliest) | Every verdict is a decision point with a trace attached (§6); no fix is presumed |
| **CI cost/flakiness** | Config sizes locked from measured spike data; per-config matrix or ~30-min timeout (§2); `workflow_dispatch` escape hatch for deep runs (echidna pattern) |

## 10. Observations Out of Scope (recorded, not modelled)

- **Runtime-ID collisions:** IDs are `UUID.nameUUIDFromBytes(…).hashCode()` — 32-bit,
  birthday bound ≈ 77k entities for a 50% pairwise-collision chance somewhere. A
  collision conflates two vars in the log map (silent entry overwrite) and in
  footprints. The specs `ASSUME` distinct IDs; the risk is documented in
  `specs/README.md` and is property-test / arithmetic territory, not TLC territory.
- **Data-dependent footprints:** a txn whose access set depends on values read may
  have a different actual footprint on re-execution than the refined declared
  footprint from its previous run. Spec B models declared footprints per incarnation
  (§5) but actual footprints as fixed per txn. Revisit only if H3/H5 verdicts make
  refinement soundness load-bearing.
- **Semaphore fairness:** CE `Semaphore` is FIFO; the model uses weak fairness, which
  is *weaker in a way that can matter* — see the fairness row in §9 for the
  validation discipline and escalation path.
- **Duplicate log entries for one logical map entry (latent, masked by Contract C).**
  Surfaced while modelling H2. The log is keyed by the entry `TxnVar`'s *own*
  runtime id when the key exists and by the *existential* id when it does not
  (`TxnLogValid.writeVarMapValue` / `getVarMapValueEntry` both branch on
  `getTxnVar(key)`). So if a key's existence flips **mid-run** — another txn commits an
  insert while our log is being built — a second access to the same logical entry
  resolves to a *different* log key and creates a **second log entry** for it. Both
  would then be committed. This is unreachable under accurate footprints (anyone who
  can insert that key writes its existential id, which is raw-id-overlapping with our
  access, hence Contract-C-excluded) and it is subsumed by H3 in fallback mode, so it
  is recorded rather than modelled. It becomes live if footprint accuracy is ever
  weakened — and it is a second, independent reason the two id spaces should be
  reconciled when H2 is fixed.
