# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- Updated deprecated `continuationIndent` keys to `indent` in scalafmt config
- Added security contact email to SECURITY.md
- Removed orphaned `docs/assets/logo.svg`

## [0.9.6] - 2026-01-10

### Changed
- Updated sbt-typelevel to 0.8.1 and require Java 21
- Updated Scalafmt to Scala 3 dialect parser

## [0.9.5] - 2023-08-13

### Changed
- Migrated to Typelevel SBT plugin (`sbt-typelevel`) for build management, CI, and publishing
- Code cleanup: modifier ordering and style improvements

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
