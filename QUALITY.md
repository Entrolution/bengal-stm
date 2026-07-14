# Quality Cycles

## Cycle 2 — 2026-07-13 (bug hunt)

**Scope**: correctness, concurrency, and API-contract bugs. Full main source (runtime, compiler,
model), the serializability oracle and soak harness, both TLA+ specs (spec↔code parity), and the
throughput benchmark. 15 specialist agents over 2 batches, ~7,150 lines. Cycle artifacts
(findings JSONL, manifest, structural risks, execution checklist) are untracked working files,
not part of the repository.

- **Modules reviewed**: 6 covering ~7,150 lines
- **Specialist agents**: 15 (across 2 batches; 0 rate-limited)
- **Findings**: 38 total (0 critical, 7 high, 15 medium, 16 low)
- **By category**: 0 security / 19 correctness / 15 quality / 4 architecture
- **OWASP distribution**: n/a (no security-category findings)
- **Crown-jewel overrides**: 0
- **Cross-validated**: 6 findings independently confirmed by 2–3 agents (the TxnVarMap index race by three)
- **Contradictions**: 1 (null map values) — resolved as a false positive caused by a stale comment, which became a finding itself
- **Structural risks identified**: 8
- **False positives retracted**: 1
- **Rate-limited agents**: 0

The five main-source High findings: whole-map `set` corrupts the transaction log (proven by
executed repro — unchanged keys vanish from read-your-writes, and a following whole-map `set`
durably leaks deleted keys); cancelling `commit` neither cancels nor rolls back (writes publish
after timeout, parked `waitFor` transactions resurrect later); TxnVarMap's mutable index is read
unlocked against in-place structural writes (three vectors, one of which escapes Contract C
entirely); map-key conflict identity derives from `toString` while storage identity is `equals`
(lost updates for `BigDecimal`-like keys); and all runtime ids collapse to 32 bits, putting
birthday collisions in range of the advertised database-index scale. The two benchmark-scoped
Highs: the H3-cliff scenario confounds itself with unbounded allocation, and no scenario in the
suite ever executes H2's multi-lock ordering.

Two meta-results. The conflict-detection *algebra* is sound — every agent that checked
`IdFootprint`, the lock ordering, the park protocol, or the commit gates verified them clean, and
the TLA+ footprint model mirrors the code exactly — but its identity *inputs* (toString, 32-bit
ids, the unlocked index) are where the correctness holes live. And stale prose is an active bug
vector: one stale comment produced two false bug reports inside this very hunt, and both specs
carry narration describing deleted code (Scheduler.tla's commit action still models the removed
dirty check).

### Execution Results — 2026-07-14

Six phases, **15 commits** on `fix/harden-cycle-2` (73ca304 → 3844a77), every checklist item
executed through plan-mode + critique ratchet, revert-checked tests, and per-commit review-fix.

- **Items fixed**: every fix checkbox across all six phases — committed-data correctness,
  error/cancellation contract, liveness, model-layer sealing, documentation truth, and the
  instrumentation cluster (both TLA+ specs, the soak meters, the JMH harness).
- **Regression tests added**: 57 (212 → **269**, none ignored), each red-checked against the
  unfixed code where the fix changes behaviour (doc-promise pins noted as green-on-current).
  Plus 10 CI-level negative-control mutation checks (a new `negative-controls` job runs committed
  spec-mutation patches on every push; a mutant that stays green fails the build).
- **Deferrals**: 1, user-approved — the benchmark re-run/re-publication — and closed the same
  day: re-measured on a dedicated vast.ai Ryzen 9 5950X (A→B→A, drift-gated) and republished.
  The H3 cliff reproduced at −34.7% with zero drift; `dataDependentKey` flipped to a net +15%;
  and the pre-H2 library deadlocked outright on the new `crossMapInsert` workload — the model's
  counterexample reproduced live by the instrument built to measure it.
- **In-situ discoveries**: 2 recorded in the checklist (the failure-path coverage-gate limitation,
  documented; the wake-time re-analysis design lesson). The review loops additionally caught and
  fixed errors in the hardening itself before commit — a wrong lock-span justification in spec
  prose, a wrong negative-control retirement (NC-2 reinstated as the H3-fix revert), and a
  vocabulary misuse (erratum vs short-circuit) that would have misdescribed the H3 benchmark's
  premise.
- **Review-fix cycles**: every commit gated; the larger commits took full multi-agent loops with
  independent verification (Phase 4's sealing: 5 reviewers + 3 verifiers; the Scheduler.tla
  coverage remodel: 3 reviewers), the doc/instrumentation commits took focused loops.
- **Spec-model debt cleared**: Spec B's commit step now models the coverage gate over `LoggedFP`
  (state space 846k → 1.90M distinct, every pinned verdict reproduced including the
  SchedulerAbsentKey deadlock), the DirtyRestart narrative is history everywhere, and the
  negative controls are executable.
- **Final counts**: 269 tests; 12 CI-pinned TLC expectations (unchanged: ten clean, two red) plus
  10 automated negative controls; MiMa vacuous on the 0.15 line throughout (re-confirmed at every
  commit).

- **Trend**: first full-scope bug hunt (Cycle 1 was prose-scoped: 2 live defects found).
  Critical: flat (0 → 0). The live-defect count (7 High) reflects scope expansion, not
  regression — none of the 7 were reachable by Cycle 1's methods.

## Cycle 1 — 2026-07-12

**Scope**: comments, documentation, duplication. Architecture and general hygiene were out of
scope. 12 review agents over `src/main`, 18 test suites, `benchmarks/`, `specs/`, and 7 docs.

The hunt was scoped to prose. It found two live bugs, a public API that did not compile, and a
model that was checking a stronger guard than the code implements.

### Two live defects, both fixed (PR #69)

**A lost wakeup when a transaction parks on a map key that does not exist.** Reading an absent
key recorded no log entry, and `anyReadChangedSinceRead` — the *second* of H1's two park guards
— folds over the log. The guard was structurally blind on that path. Found not by a test but by
an agent asking why one of five copies of the same key-resolution code looked different from
the other four.

It is **not reachable by any behavioural test**, so it was confirmed by TLC —
`SchedulerAbsentKey.cfg` is `SchedulerRetry.cfg` with the parker's read unlogged and nothing
else changed, and it deadlocks where `SchedulerRetry` verifies clean. Two things are worth
keeping from it:

- **The model had the same blind spot.** `Scheduler.tla` computed park-time staleness over the
  transaction's *actual* footprint; the code computes it over the *log*. For an absent-key read
  those differ, so the spec had been checking a **stronger guard than the code implements**.
  That is why neither TLC nor the soak caught it. `ActualFP` and `LoggedFP` are now distinct.
- **The premise was written down three times and never joined up** — `TxnLogContext`,
  `SerializabilityOracleSpec`, and the CHANGELOG each state "reading an absent map key records
  no entry", every one of them as justification for something else.

**An unbounded retry loop, re-publishing on every lap, for any transaction yielding `null`.**
`Option(...)` mapped a null result to `None`, and `None` already meant "refine and re-run". A
`TxnVar[F, String]` holding `null`, read straight back out, hung forever.

### The dominant prose finding

**The fixes landed; the prose did not.** Around 25 sites still described a pre-fix world,
including four `.cfg` headers reading "EXPECTED RED" while CI asserted clean, a comment
describing a "shadow log" half of the H3 fix that was *deliberately rejected and never built*,
and `specs/README.md` — the self-declared source of truth — opening with "Two confirmed
defects, both pinned red" about two defects it later confirmed were fixed.

Neither guard could catch this. `check_expected.sh` compares TLC's *output* to CI's assertion,
so a `.cfg`'s header prose is invisible to it. `verify_anchors.sh` checks that an anchor
**exists**, not that it is **true** — which is also how `NoLostWakeup` came to be listed under
a column headed "TLA+ Invariant" despite existing in no `.tla` file.

`docs/plans/formal-specs.md` had become a *second* verdict source, and both doc-vs-doc
contradictions came from exactly that. It is archived. `specs/README.md` is the only one now,
and `check_expected.sh` no longer tells you to go update the other.

### The user-facing surface was broken

Every code example in the README failed to compile (`import bengal.stm.STM`; the root package
is `ai.entrolution`). Every test sits *inside* `package ai.entrolution`, where the relative
import resolves — so the whole suite stayed green while nothing a user copied would build.
`src/test/scala/usercode/` now compiles the public API from a user's vantage point.

`waitFor`'s wake-up contract was documented nowhere: the predicate is evaluated eagerly at
construction, and what wakes a parked transaction is its **read set**. Hoist the read out of the
transaction and it parks forever. `fromF`/`delay` effects run **twice** per attempt, which
`fromF` — the sharpest edge in the API — did not say at all.

And the README's flagship footprint-hazard example **did not trigger the hazard**: `set`'s value
argument is by-name and suspended, and the analyser forces only the *key* thunk. The `.getOrElse`
fix printed beneath it was a no-op.

### Tests that could not fail

`TxnLockOrderingSpec` is named for lock ordering and **structurally cannot deadlock** — its
transactions have incompatible footprints, so the scheduler serializes them and the lock code is
never reached concurrently. It could never have caught H2, and its assertion passed under a lost
update (proven: disable the compatibility relation and it observes exactly `(3,3)`, sum 6,
against an assertion of `> 3`).

`StaticAnalysisFallbackSpec`'s three arms had all converged on asserting the same thing, so
nothing checked that the H3 shape still *reaches* the fallback — while three files depend on it.
It now has a probe that goes red both when the fix is reverted **and** when the shape stops
throwing.

### NC-8: a negative control that does not fire

Deleting the commit-time dirty check outright (`IsDirty(t) == FALSE`) leaves **all seven**
commit configs verifying clean. Spec A does not check it. The refinement action is now split on
disjoint guards so that this is visible at all — previously both triggers shared one action and
`"DirtyRestart fires"` was `CommitDirty`'s non-vacuity evidence while proving nothing.

There is a reading in which this is correct rather than a gap (H6's coverage check aborts a
divergent transaction *before* the publish that would have made a peer dirty). The model cannot
distinguish that from a catalogue too small to isolate it. Recorded as an open gap; the check
stays in the code.

### What the review process itself got wrong

Worth recording, because the corrections came from adversarial review rather than from care:

- A TLC state count taken from a **parallel** run was labelled `-workers 1` and propagated into
  the source of truth. Parallel counts do not reproduce (9,201 then 8,843 on consecutive runs).
- `CI=true` **does not enable fatal warnings** in this build — sbt-typelevel keys them off
  `GITHUB_ACTIONS`. Every local "CI gate" run this cycle compiled with warnings non-fatal and
  reported success. The gate was theatre.
- Three code comments asserting *why* something was true (`@nowarn`'s purpose, the `hasDownstream`
  branch's reason, the `tally >= -2` allowance) were wrong, and each was corrected by someone
  removing the thing and measuring what actually happened.

### Counts

- 143 → **207** tests, none ignored (the two `TxnLogDirtySpec` park/wake tests, disabled as
  "flaky" in v0.12.0, were reporting H1; they are back and pass 80/80 under load).
- **12** CI-pinned TLC expectations: ten expected-clean, two pinned-red.

## Quality Cycle 3 — 2026-07-14 (quality hunt)

**Scope**: quality debt, not correctness — architecture (coupling/cohesion + abstraction audit),
code hygiene (reuse, simplification, idiom, efficiency), comment hygiene (accuracy, why-test,
timelessness, AI-tell density), and documentation (coverage, structure, accuracy, prose). Full
main source, full test suite, both TLA+ specs + harness, benchmark, and all six active docs.
Cycle artifacts (raw agent outputs, collated findings JSONL, manifest, architecture review,
execution checklist) are untracked working files, not part of the repository.

- **Dimensions**: all four | **Modules**: 7 code/spec groups + 3 doc clusters | **Agents**: 32 across 4 batches (0 rate-limited)
- **Findings**: 113 total (0 Critical / 8 High / 30 Medium / 75 Low) — architecture 4 / hygiene 62 / comments 21 / docs 26
- **Remediation classes**: mechanical 61 / local-refactor 46 / design-change 6
- **Gate-rejected**: 0 | **Suppressed by baseline**: 0 (first quality cycle) | **Cross-validated**: 13 merged groups | **Contradictions**: 1 (+1 impact-rating disagreement, both recorded)
- **Out-of-scope correctness discoveries**: 1 (AbsentKeyParkSpec probe-or-vestige, routed to the checklist's in-situ list)
- **Trend**: first quality cycle — no prior quality baseline. Against bug-hunt cycle 2's meta-result
  ("stale prose is an active bug vector"): the vector persists in the exact class predicted — the
  cycle found stale spec narration describing pre-fix behaviour in present tense (the H1 absent-key
  negative control), a CONTRIBUTING gate teaching `CI=true` after this very file recorded that gate
  as theatre, and the SSoT's `writeSeq` row describing the NC-1 mutant ordering instead of the H2 fix.

The eight Highs in one breath: the TLA+-verified submission scaffold exists as two hand-synced
copies; three hot-path efficiency shapes the codebase itself already measured and fixed once
(fiber-per-trivial-op in the commit and submit scans, triple list materialization) survive at
seven other sites; benchmark figures
are duplicated into the README and have drifted; CONTRIBUTING's pre-push gate does not enable
fatal warnings; specs/README's writeSeq mapping states the reverted-bug sort order; and the
scheduler spec narrates deleted code behaviour in present tense above a deliberate negative
control.

Two meta-results. First, the strongest positive: adversarial fresh-eyes verification affirmed the
load-bearing structures — the cake pattern, the conflict-detection algebra's spec↔code mapping
(CommitProtocol.tla matches the code verbatim), all 12 expectation pins, all 10 negative controls,
and zero AI-tell density anywhere — so the codebase's foundations survived a 32-agent audit with
its design decisions intact. Second, every High traces to the same root: a fact stated in two
places where only one is checked. The checklist's fixes are mostly single-sourcing operations —
extract the scaffold, dedupe the figures, name the constant, delete the copy.
