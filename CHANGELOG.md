# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.14.0] - 2026-07-13

### Fixed
- Commit-lock ordering across transactional maps (H2): `withLock` acquired the write set's `commitLock`s in a sorted order to prevent circular waits, but it sorted the LOG ENTRIES rather than the LOCKS, and those are not the same thing. A map entry has two unrelated runtime ids — the existential id hashed from `(mapId, key)`, which the footprint always uses and which the log keys an absent key by, and the entry `TxnVar`'s own id, which the log keys it by once the key exists — while a new-key entry's lock resolves to the MAP's structural `commitLock`. So a new-key insert took the map's lock at a sort position derived from a hash of the key: the sort ordered one id space while the locks acquired lived in another. Two transactions inserting fresh keys into two maps have compatible footprints, so the scheduler runs them concurrently by design; both then hold `{M1.lock, M2.lock}` and hash-derived order could invert their acquisition order, deadlocking both fibers forever on `Semaphore.permit` with their callers hung on `completionSignal.get`. **This needed no fallback, no under-declaration and no scheduler bug — it was reachable on entirely accurate footprints.** `TxnLogEntry.lock` now returns each lock paired with the runtime id of the entity that OWNS it, and `withLock` sorts on that owner; owner → lock is injective, so a single ascending order is a global total order over locks and no circular wait can form. Found by TLC (`specs/commit/CommitH2.cfg`) and regression-pinned by `CommitLockOrderSpec`, which deadlocked and timed out against the unfixed code.
- Write skew when static analysis cannot determine the footprint (H3): `TxnRuntime.commit`'s error handler produced an under-approximated footprint — a partial one from `StaticAnalysisShortCircuitException`, or an empty one otherwise — and then used it as though it were complete. That is unsound rather than merely imprecise: reads are never commit-validated and hold no lock, so the scheduler's footprint check is the only defence against a stale read, and a footprint missing entries switches it off. It was unsound in **both** directions — the under-declared transaction reads what a peer overwrites, and its undeclared writes invalidate a correctly-declared peer's reads (a transaction with no reads at all still breaks its peer) — and it was reachable from ordinary code: the analysis pass executes real reads but never applies writes, so reading back a key the transaction just wrote yields `None` during analysis and `Some(v)` at run time, and a partial continuation on it (`.get`, a pattern match) throws during analysis and nowhere else. Measured at 198/200 contended reps skewed. `IdFootprint.isUnderApproximated` now flags such a footprint and the compatibility relation makes it conflict with **everything**, so the transaction is serialized against all others and runs alone — nothing can change under it, so its unvalidated reads are trivially safe; the dirty path then refines from the actual log, so the cost is paid once. Every producer that short-circuits the walker is flagged: both under-approximating branches of `TxnRuntime.commit`'s handler, and the three handlers in `TxnCompilerContext` that swallowed a failed key thunk and carried on with a silently incomplete footprint. One producer is **not** flagged — `TxnCompilerContext`'s `TxnHandleError` case, which on an inner analysis failure restores the footprint to its pre-block state and discards whatever ids that block had registered. The flag is not what catches that one: the declared footprint then fails to cover the actual log, so the H6 coverage check below refines and re-runs it before it publishes. **Throughput trade-off: measured at −34% against the same workload before the fix, and about 58% of what a comparable transaction with a fully-known footprint achieves — and it is steady-state, not a one-off a retry recovers — see the README's "Static analysis and transaction footprints" section for how to avoid triggering it.** Found by TLC (`specs/commit/CommitH3*.cfg`) and pinned by `StaticAnalysisFallbackSpec`.
- Data-dependent footprint divergence (H6): the static-analysis pass runs *before* `submitTxn` — outside `activeTransactions` and outside any conflict window — and computes the access set from live reads, so a transaction whose keys depend on a value it read could be scheduled on a footprint naming the **wrong ids** if that value changed in between. Nothing throws, so the H3 flag never fires and the scheduler simply trusts a footprint that does not describe the transaction; the same stale-read mechanism as H3 follows. Under the commit locks and *before publishing anything*, Bengal now checks that the declared footprint **covers** what the run actually touched, and if it does not, refines from the actual log and re-runs. Checking before the publish is what makes it sound in both directions: an undeclared write never lands, so a peer's unvalidated read of it stays valid. The coverage test is deliberately not a subset test — a whole-map read legitimately expands, in the log, into an entry per key — and its soundness is checked exhaustively over every footprint triple (`LemmaCoverageIsSound`). Found by TLC (`specs/commit/CommitH6.cfg`) and pinned deterministically by `DataDependentFootprintSpec`.
- Silent key loss when a `null` value is stored in a `TxnVarMap`: `map.set(k, null)` made the key **disappear** — `get(k)` reported it absent and a whole-map read dropped it entirely. `TxnVarMap.get(key)` encodes a live null as `Some(null)` (the key exists; its value is null), but the log's read entry built its `initial` with `Option(txnVal)`, which collapses null to `None` — and `None` in that position means "the key is not there". The live map and the transaction log therefore disagreed about a key that had just been written. Two things followed: the key vanished on read (`extractMap` only matches `Some(initial)`), and `hasChangedSinceRead` computed `Some(null) != None` — **perpetually true** — so the park-time staleness guard misfired on every null-valued key. The log now builds `initial` with `Some(...)`, agreeing with `TxnVarMap.get`; for any non-null value `Option(v) == Some(v)`, so nothing else moves. Same root cause as the null-result retry loop below: `Option(...)` conflating "null" with "absent". Pinned by `NullResultSpec`.
- Lost wakeup when a transaction parks on a map key that does not exist: reading an absent key recorded **no log entry at all** — `getVarMapValue`'s key-absent branch returned the log unchanged, where the key-present branch records a read-only entry. But `TxnLogValid.anyReadChangedSinceRead` folds over the log, and that fold is the **second** of H1's two park guards: the one that catches a conflictor which already committed and left `activeTransactions`. A read with no entry is invisible to it, so for an absent-key predicate that guard was structurally dead, and a conflictor completing inside the parker's `[registerCompletion → activeTransactions scan]` gap escaped both guards and left it asleep forever. The read was in the *declared* footprint the whole time — the static-analysis walker registers the key's existential id whether or not the key exists — so Contract C and the wake sweeps looked correct; only the log was missing it. Two id spaces again, as in H2. An absent-key read is now logged like any other read, which also restores it to the actual footprint and therefore to H6's coverage check, which was equally blind to it. **Not reachable by a behavioural test** — the window is the same one H1's was, and a conflictor that arrives early enough to be caught subscribes to the parker instead, which makes `hasDownstream` resubmit it rather than park. Confirmed and pinned by TLC: `specs/scheduler/SchedulerAbsentKey.cfg` is `SchedulerRetry.cfg` with the read unlogged and nothing else changed, and it deadlocks where `SchedulerRetry` verifies clean.
- Unbounded retry loop, re-applying effects on every lap, for any transaction whose result is `null`: `getTxnLogResult` wrapped the value with `Option(...)`, mapping a null result to `None` — but `AnalysedTxn.commit` also uses `None` to mean "the placement was unsound or the log went dirty; refine and re-run". The sound, non-dirty path publishes the write set *and then* yields that `None`, so the runtime read a legitimate null as a refinement request, re-ran the whole transaction, published again, and never terminated. Reachable from ordinary code — a `TxnVar[F, String]` holding `null`, read back out, hung forever. The value is now wrapped with `Some`, so a committed transaction always yields `Some` (of a possibly-null value) and `None` on that path can only mean refine. Pinned by `NullResultSpec`.
- Crash on committing a transaction with an empty log: `TxnLogValid.idFootprint` folded its entries with `reduce`, which throws on an empty list — and an empty log is perfectly ordinary (a transaction of `pure`/`delay` steps touches nothing). The bug was latent while the footprint was only forced on the dirty path, where the log is non-empty by construction.
- Lost wakeup on `waitFor`/retry (H1): a transaction parking into the retry map left `activeTransactions` before it landed in the map, and wakes fire only from submission-time sweeps — a conflicting transaction whose sweep ran in that gap saw the parker in neither structure, so the commit that satisfied the predicate never woke it and the caller hung forever. Before parking, `submitTxnForRetry` now scans `activeTransactions` for footprint conflicts (catching conflictors that have not yet committed) and then re-compares the log's read set against live state (`TxnLogValid.anyReadChangedSinceRead`, folding the per-entry `hasChangedSinceRead` over the log — a real comparison, unlike commit-time validation, which never checks read-only entries; this catches conflictors that already committed). Either check firing skips the park and resubmits. The check order is load-bearing — the retry semaphore serializes the region against wake sweeps but not against commits, so the staleness check must be the last read before the park. Found and verified by TLC (`specs/scheduler/SchedulerRetry.cfg`), including negative controls for each leg of the fix.
- Spurious self-wake spin on `waitFor`/retry: a parked transaction is footprint-incompatible with itself, so its own in-flight submission sweep woke it, and the re-park could be woken by the next sweep in turn — unbounded churn under adversarial scheduling. The retry map is now keyed by transaction id rather than by footprint, and a sweep skips the submitting transaction (its own submission cannot satisfy its own predicate — it retried rather than committed). Keying by id also removes the previous chaining of wakes for distinct transactions that happened to share a footprint.
- Double transaction execution (H4): a dirty resubmission shared its dependency tally, unsubscribe edges, and status refs with the previous incarnation via `this.copy`, while the completion cascade ran fire-and-forget — a stale cascade could drain the new incarnation's dependency edges (or a reversed edge could land on a transaction whose execute fiber was already spawned, with `registerRunning` re-checking nothing), spawning a second concurrent execution: the transaction's effects applied twice, its completion signal fired twice, and dependency tallies went negative. Fixed three ways: `admitForExecution` gates the execute window (status compare-and-set plus zero dependency tally, atomically under the graph semaphore — duplicate and stale spawns exit without side effects), every resubmission builds a `freshIncarnation` with new bookkeeping refs (old cascades drain dead refs harmlessly), and `triggerUnsub` fires exactly once per incarnation. Found by TLC model checking (`specs/scheduler/Scheduler.tla`); the fixed protocol verifies exhaustively at the same bounds that produced the counterexample, with deadlock detection enabled.
- Phantom write skew on `TxnVarMap` (H5): a whole-map read was judged footprint-compatible with a concurrent new-key insert (the compatibility relation never tested a child-entry write against a parent-structure read), and since structure reads are not commit-validated, two "read all, then insert" transactions could both observe the pre-insert map and both commit — a non-serializable outcome measured in ~98% of contended runs. `IdFootprint.isCompatibleWith` now conflicts child-entry writes with parent-structure reads, so the scheduler serializes such transactions. Throughput trade-off: whole-map readers now serialize against all entry-level writers of that map — the price of making the idiom serializable. Confirmed via the TLA+ footprint-relation lemmas (`specs/common/FootprintLemmas.tla`) and regression-pinned by `SerializabilityOracleSpec`.

### Changed
- **The commit-time dirty check is gone.** It could never fire. Since the H6 fix a transaction only publishes if its declared footprint *covers* what it actually touched — and if that holds, the write set cannot have moved underneath it: a peer can only move it by publishing to something the transaction writes; that peer only publishes if *its* coverage holds, so it declared that write too; two footprints that both declare a write to the same entity are **incompatible**; and Contract C keeps incompatible transactions out of each other's execute windows entirely. Asserted as `CoverageSubsumesDirty` in `specs/commit/CommitProtocol.tla` (checked by all seven configs on every push) on top of `LemmaCoWriteImpliesIncompatible` (exhaustive over every complete footprint pair). The invariant is non-vacuous and non-trivial: `DirtyRestart` did fire, in a state where the write set really had moved — and in that state coverage had *already* failed. It never once caught anything coverage did not. `NeedsRefinement` is now just `~CoverageOk`, and every commit is spared a fiber fork, a `Deferred`, a cancel and a join.
- **Contract C is now checked against the running code** (`ContractCSpec`), not only in the model. The dirty check was the last thing between a Contract-C violation and a silently lost update, and deleting it on the strength of a *model* proof — in a library whose history is the code and the model quietly disagreeing — would have been a bad trade. So the backstop moved rather than vanished: eight transactions with pairwise-incompatible footprints must never be in their bodies at once, with an anti-vacuity arm proving the same meter *does* see compatible transactions overlap. Disable `isCompatibleWith` and it fails, reporting 2–4 conflicting transactions running together.
- `TxnLogContext` resolves a map key to its log id in **one** place (`resolveMapKey`) rather than five. The five copies were not identical, and that was the point: four recorded a log entry for an absent key and one did not — which was the lost wakeup above. An omission in one of five places nobody diffs is invisible; an omission in the one place is a compile error or a failing test. The write paths turned out to implement the same rule as each other once unified (new value differs from the initial ⇒ record an update), which the old shape had obscured well enough that a reviewer read them as contradicting.
- `HistorySpec`'s scaling test no longer asserts a wall-clock budget (`millis < 5000`). That measures the machine, not the algorithm: it passes idle and fails under a `sbt +test` cross-build, which is what `CONTRIBUTING.md` tells contributors to run. A test whose verdict depends on what else is running is a flaky test, and flaky tests get switched off — which is how the two `TxnLogDirtySpec` park/wake tests spent four months disabled while faithfully reporting H1. The claim is that cycle detection is tractable at a scale the O(n!) permutation oracle cannot touch, so **completing** is the assertion, under a generous `failAfter`.
- `TxnLogValid.idFootprint` now folds its entries with `traverse` rather than `parTraverse`. Each entry's footprint is a pure computation, so there is nothing to overlap and a fiber per entry was pure overhead — and a whole-map read expands into one log entry per key, so the fiber count tracked the map size. It is what keeps the whole-map-read + insert workload at −8% rather than the −21% it would otherwise pay for the H5 fix (see `benchmarks/README.md`).

### Added
- TLA+ specification of the **commit protocol** (`specs/commit/CommitProtocol.tla`, "Spec A"): the log run, lock resolution and acquisition, the commit-time dirty check, publish, release and the dirty-refinement path, verified under an assumed Contract C that the scheduler spec proves. Twelve CI-pinned TLC expectations across the workstream: ten expected-clean, and two pinned RED — `specs/scheduler/SchedulerAborts.cfg` (a `CompletionAtMostOnce` counterexample under failure injection, `workflow_dispatch`-gated) and `specs/scheduler/SchedulerAbsentKey.cfg` (a deadlock pin, run on every push — it is the negative control for the park guard the absent-key fix restored, and it must keep deadlocking)
- Scalable serializability soak (`soak/`): conflict-serializability by cycle detection in the dependency graph (after Adya and Jepsen's Elle) rather than by trying every serial order, which is O(n!) and caps out around four transactions. Drives the full operation surface — read-only reads, whole-map reads, new-key inserts, two-map transactions, data-dependent keys, under-declared transactions — and reports each anomaly by class. The checker is itself calibrated against hand-built histories whose verdicts are known by construction
- Retry/park/wake soak (`RetrySoakSpec`): a bounded buffer that parks in both directions, checking liveness, exactly-once delivery and bounded re-execution across the retry machinery — a path that previously had no randomized coverage at all
- JMH throughput benchmarks (`benchmarks/`, not aggregated into the root project): seven, measured A -> B -> C -> A on a dedicated box. **The only real cost in this release is H3's serialization cliff (−34%) and the data-dependent coverage path (−10%). Everything else got FASTER** — the commit path by **+6%** and a whole-map read plus insert by **+13%**, against the tree *before* any of this correctness work, while now carrying H6's coverage check on every commit. Deleting the commit-time dirty check is why: it was worth +13% on the commit path and +22% on whole-map reads, and it more than pays for what coverage costs. Three workloads show **no measurable change at all**, and the README says so rather than reporting the noise as a result — an earlier version of that table published −1%, −4% and −6% figures that were below the noise floor of the instrument that produced them. `uncontendedCommit` drifted 6% between identical runs at `-f5` and was re-measured at `-f20` (1.6%) rather than published unresolved. Nothing here isolates H1, H4 or the self-wake spin.
- Serializability oracle test suite (`SerializabilityOracleSpec`): generated concurrent workloads checked against all serial orders of a sequential reference model, an increment canary, the H5 phantom-write-skew regression test, a dirty-path stress test that drives the resubmission machinery through data-dependent map keys (it exercises the machinery the H4 fix hardened; the executable H4 gate is the TLC check), and a park/wake smoke test covering the retry-park leg
- TLA+ formal specifications under `specs/` for the footprint compatibility relation and the transaction scheduler protocol, with CI-pinned expectations (`.github/workflows/specs.yml`)
- 42 new tests (143 → 185, **none ignored**): the suites above, plus the two `TxnLogDirtySpec` park/wake tests that v0.12.0 tagged flaky and ignored. They were not flaky — they were reporting H1. A lost wakeup corrupts nothing, it hangs, so an intermittent timeout is the only symptom it can produce, and "under CI load" is exactly the preemption H1 needs to open its window. Re-enabled after 80/80 clean reps of each body

## [0.13.0] - 2026-03-10

### Fixed
- `TxnRetryException` caught by generic `handleErrorWith` in the `TxnHandleError` compiler case, causing `retry` inside `handleErrorWith` to fail instead of retrying
- `TxnVarMap` commit-time lock returning `None` for new keys, leaving commits unlocked when the underlying `TxnVar` doesn't exist yet — now falls back to the map's structural `commitLock`
- TOCTOU race in `TxnVarMap.addOrUpdate`: check for key existence and the add/update were not atomic — moved inside `internalStructureLock`
- TOCTOU race in `TxnVarMap.delete`: same pattern — moved the check-then-act inside the lock
- `completionSignal` not completed on unexpected exceptions in `execute`, causing caller deadlocks — wrapped in `handleErrorWith` that ensures `registerCompletion` and signal completion
- Unsupervised fiber in `commit`: errors from `submitTxn` before `execute` is reached now complete the signal with `Left` to prevent hangs

### Added
- 4 new tests (139 → 143): retry-through-handleErrorWith, concurrent new-key set/delete, new-key lock fallback, error-completes-not-hangs

## [0.12.0] - 2026-02-27

### Fixed
- Multi-key map bug in `TxnLogContext` where `setVarMapValue` and `modifyVarMapValue` used the map's structure runtime ID instead of the per-key runtime ID, causing multiple set/modify operations for different keys in the same transaction to overwrite each other's log entries

### Added
- Scalafix CI enforcement with import ordering checks
- Code coverage reporting in CI
- 77 new tests (62 → 139, 2 ignored as flaky): targeted coverage for `TxnVarMap`, `TxnVar`, `waitFor`, `handleErrorWith`, `pure`/`delay`, `TxnLogEntry` types, and multi-key regression tests

### Changed
- Scala 3 bumped from 3.3.4 to 3.3.7 LTS
- ScalaCheck bumped from 1.18.1 to 1.19.0
- cats-effect-testing bumped from 1.6.0 to 1.7.0
- sbt-typelevel bumped from 0.8.4 to 0.8.5
- Refactored `TxnLogContext`: extracted `getLogEntry` helper, unified duplicate entry/value write methods (−12% lines)
- Adjusted CI stress test timeouts for reliability
- Repository moved from `gvonnessi` to `Entrolution` organisation
- Updated CONTRIBUTING.md with cross-build guidance and CODE_OF_CONDUCT.md contact

## [0.11.0] - 2026-02-15

### Changed
- Cross-build for Scala 2.13 and Scala 3, publishing `_2.13` and `_3` artifacts
- Changed `asyncF` visibility to `private[stm]` for Scala 3 compatibility
- Used sbt-typelevel for common compiler flags (removed from `build.sbt`)

## [0.10.1] - 2026-02-15

### Fixed
- Fixed lock ordering deadlock in transaction commit
- Fixed semaphore leak via bracket pattern in transaction runtime
- Fixed `MutableMap` race condition by switching to `TrieMap`
- Fixed `registerRunning` no-op bug in transaction scheduler

### Added
- FUNDING.yml for GitHub Sponsors and Patreon

## [0.10.0] - 2026-02-15

### Breaking
- Removed unused `internalSignalLock` field from `TxnVarMap` (binary-incompatible)

### Changed
- Bumped Scala to 2.13.16
- Bumped cats-effect to 3.6.3, cats-free to 2.13.0
- Bumped ScalaCheck to 1.18.1, ScalaTest to 3.2.19, cats-effect-testing to 1.6.0
- Bumped sbt-scoverage to 2.4.1, Scalafmt to 3.8.6
- Added explicit ScalaTest dependency
- Updated sbt-typelevel to 0.8.4
- Updated deprecated scalafmt config keys
- Removed orphaned `docs/assets/logo.svg`

### Added
- Comprehensive test suite: IdFootprint unit & property tests, concurrency stress tests (62 total)
- CONTRIBUTING.md, CODE_OF_CONDUCT.md, SECURITY.md
- Bug report and feature request issue templates

## [0.9.6] - 2026-01-10

### Changed
- Updated sbt-typelevel to 0.8.1 and require Java 21
- Updated Scalafmt to Scala 3 dialect parser

## [0.9.5] - 2023-08-13

### Changed
- Migrated to Typelevel SBT plugin (`sbt-typelevel`) for build management, CI, and publishing
- Code cleanup: modifier ordering and style improvements

## [0.9.4] - 2023-08-06

### Changed
- Code cleanup: modifier ordering improvements

## [0.9.3] - 2023-07-31

### Changed
- General code cleanup and internal improvements

## [0.9.2] - 2023-07-12

### Changed
- Improved retry efficiency in the transaction runtime scheduler

## [0.9.1] - 2023-06-27

### Fixed
- Fixed serialisation issue in transaction log handling

## [0.9.0] - 2023-06-26

### Changed
- Scala version update

## [0.8.0] - 2023-06-26

### Added
- Reactive graph-based transaction scheduler for smarter retry scheduling

## [0.7.0] - 2022-09-06

### Changed
- Refactored suspended effect handling to use `F[_]` instead of thunks

## [0.6.0] - 2022

### Changed
- Build cleanup and README updates

## [0.5.0] - 2022

### Changed
- API refactor for cleaner public interface

## [0.4.0] - 2022

### Changed
- Higher-kinded type updates
- Encapsulated `TxnVar` internal parameters from public API

## [0.3.x] - 2022

### Added
- Initial public release
- Software Transactional Memory implementation for Cats Effect
- `TxnVar` for transactional mutable variables
- `TxnVarMap` for transactional mutable maps
- Intelligent runtime scheduler with static analysis phase
- Semantic blocking via `waitFor` / retry mechanism
- `handleErrorWith` for transaction-level error recovery

[Unreleased]: https://github.com/Entrolution/bengal-stm/compare/v0.13.0...HEAD
[0.14.0]: https://github.com/Entrolution/bengal-stm/compare/v0.13.0...v0.14.0
[0.13.0]: https://github.com/Entrolution/bengal-stm/compare/v0.12.0...v0.13.0
[0.12.0]: https://github.com/Entrolution/bengal-stm/compare/v0.11.0...v0.12.0
[0.11.0]: https://github.com/Entrolution/bengal-stm/compare/v0.10.1...v0.11.0
[0.10.1]: https://github.com/Entrolution/bengal-stm/compare/v0.10.0...v0.10.1
[0.10.0]: https://github.com/Entrolution/bengal-stm/compare/v0.9.6...v0.10.0
[0.9.6]: https://github.com/Entrolution/bengal-stm/compare/v0.9.5...v0.9.6
[0.9.5]: https://github.com/Entrolution/bengal-stm/compare/v0.9.4...v0.9.5
[0.9.4]: https://github.com/Entrolution/bengal-stm/compare/v0.9.3...v0.9.4
[0.9.3]: https://github.com/Entrolution/bengal-stm/compare/v0.9.2...v0.9.3
[0.9.2]: https://github.com/Entrolution/bengal-stm/compare/v0.9.1...v0.9.2
[0.9.1]: https://github.com/Entrolution/bengal-stm/compare/v0.9.0...v0.9.1
[0.9.0]: https://github.com/Entrolution/bengal-stm/compare/v0.8.0...v0.9.0
[0.8.0]: https://github.com/Entrolution/bengal-stm/compare/v0.7.0...v0.8.0
[0.7.0]: https://github.com/Entrolution/bengal-stm/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/Entrolution/bengal-stm/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/Entrolution/bengal-stm/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/Entrolution/bengal-stm/compare/v0.3.10...v0.4.0
