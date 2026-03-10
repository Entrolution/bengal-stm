# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
- Repository moved from `gvonness-apolitical` to `Entrolution` organisation
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
