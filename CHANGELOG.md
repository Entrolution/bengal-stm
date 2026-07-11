# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- Lost wakeup on `waitFor`/retry (H1): a transaction parking into the retry map left `activeTransactions` before it landed in the map, and wakes fire only from submission-time sweeps — a conflicting transaction whose sweep ran in that gap saw the parker in neither structure, so the commit that satisfied the predicate never woke it and the caller hung forever. Before parking, `submitTxnForRetry` now scans `activeTransactions` for footprint conflicts (catching conflictors that have not yet committed) and then re-compares the log's read set against live state (`hasChangedSinceRead` — a real comparison, unlike commit-time validation, which never checks read-only entries; this catches conflictors that already committed). Either check firing skips the park and resubmits. The check order is load-bearing — the retry semaphore serializes the region against wake sweeps but not against commits, so the staleness check must be the last read before the park. Found and verified by TLC (`specs/scheduler/SchedulerRetry.cfg`), including negative controls for each leg of the fix.
- Spurious self-wake spin on `waitFor`/retry: a parked transaction is footprint-incompatible with itself, so its own in-flight submission sweep woke it, and the re-park could be woken by the next sweep in turn — unbounded churn under adversarial scheduling. The retry map is now keyed by transaction id rather than by footprint, and a sweep skips the submitting transaction (its own submission cannot satisfy its own predicate — it retried rather than committed). Keying by id also removes the previous chaining of wakes for distinct transactions that happened to share a footprint.
- Double transaction execution (H4): a dirty resubmission shared its dependency tally, unsubscribe edges, and status refs with the previous incarnation via `this.copy`, while the completion cascade ran fire-and-forget — a stale cascade could drain the new incarnation's dependency edges (or a reversed edge could land on a transaction whose execute fiber was already spawned, with `registerRunning` re-checking nothing), spawning a second concurrent execution: the transaction's effects applied twice, its completion signal fired twice, and dependency tallies went negative. Fixed three ways: `admitForExecution` gates the execute window (status compare-and-set plus zero dependency tally, atomically under the graph semaphore — duplicate and stale spawns exit without side effects), every resubmission builds a `freshIncarnation` with new bookkeeping refs (old cascades drain dead refs harmlessly), and `triggerUnsub` fires exactly once per incarnation. Found by TLC model checking (`specs/scheduler/Scheduler.tla`); the fixed protocol verifies exhaustively at the same bounds that produced the counterexample, with deadlock detection enabled.
- Phantom write skew on `TxnVarMap`: a whole-map read was judged footprint-compatible with a concurrent new-key insert (the compatibility relation never tested a child-entry write against a parent-structure read), and since structure reads are not commit-validated, two "read all, then insert" transactions could both observe the pre-insert map and both commit — a non-serializable outcome measured in ~98% of contended runs. `IdFootprint.isCompatibleWith` now conflicts child-entry writes with parent-structure reads, so the scheduler serializes such transactions. Throughput trade-off: whole-map readers now serialize against all entry-level writers of that map — the price of making the idiom serializable. Confirmed via the TLA+ footprint-relation lemmas (`specs/common/FootprintLemmas.tla`) and regression-pinned by `SerializabilityOracleSpec`.

### Added
- Serializability oracle test suite (`SerializabilityOracleSpec`, 143 → 148 tests): generated concurrent workloads checked against all serial orders of a sequential reference model, an increment canary, the H5 phantom-write-skew regression test, a dirty-path stress test that drives the resubmission machinery through data-dependent map keys (it exercises the machinery the H4 fix hardened; the executable H4 gate is the TLC check), and a park/wake smoke test covering the retry-park leg
- TLA+ formal specifications under `specs/` for the footprint compatibility relation and the transaction scheduler protocol, with CI-pinned expectations (`.github/workflows/specs.yml`)

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
