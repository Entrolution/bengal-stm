# Quality Cycles

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
