# Contributing to Bengal STM

Thank you for your interest in contributing to Bengal STM! This document provides guidelines and information for contributors.

## Code of Conduct

This project adheres to a [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

## How to Contribute

### Reporting Issues

Before creating an issue, please check if a similar issue already exists. When reporting bugs, include:

- A clear, descriptive title
- Steps to reproduce the issue
- Expected vs actual behavior
- Scala version, Java version, and Bengal STM version
- Minimal code example if applicable

### Suggesting Features

Feature suggestions are welcome! Please provide:

- A clear description of the feature
- The problem it solves or use case it enables
- Any implementation ideas you have

### Pull Requests

1. **Fork the repository** and create your branch from `main`
2. **Write tests** for any new functionality
3. **Run the pre-push gate below** — it is what CI runs, in the order CI needs
4. **Update documentation** if you're changing public APIs
5. **Submit a pull request** with a clear description of your changes

### The pre-push gate

CI enforces five things beyond the tests, and each of them can fail a build that
compiles and passes locally. Run this before you push:

```bash
sbt headerCreateAll             # Apache header on every new file
sbt scalafixAll                 # BEFORE scalafmt — it rewrites imports
sbt scalafmtAll scalafmtSbt     # scalafmtAll does not cover build.sbt; scalafmtSbt does

CI=true sbt scalafmtCheckAll scalafmtSbtCheck headerCheckAll 'scalafixAll --check' +test
sbt mimaReportBinaryIssues      # binary compatibility against the last release
```

Two things about that order are not arbitrary:

**Scalafix must run before scalafmt.** `scalafixAll` *rewrites* your imports
(`OrganizeImports`: grouped `java`/`scala`/rest, ASCII order, unused ones
deleted), and those rewrites are not formatted. Run `scalafmtAll scalafixAll` in
that order and you push files that scalafix has left unformatted — `scalafmtCheckAll`
then fails on a tree you just formatted. This repo has made that mistake.

**`CI=true` is not decoration.** sbt-typelevel keys **fatal warnings** off the `CI`
environment variable, so a warning that is merely a warning on your machine is an
**error** on the build. If you want to know what CI will say, ask it the way CI asks.

## Development Setup

### Requirements

- Java 21 or later
- sbt

### Cross-Build

Bengal STM is cross-compiled for Scala 2.13 and Scala 3; the exact versions are
pinned in `project/Dependencies.scala`. Use the `+` prefix to run a command
against both:

```bash
sbt +compile
sbt +test
```

To target one version during development, use the **unpinned** selectors. They
resolve against `crossScalaVersions`, so they cannot rot the next time a version
is bumped — and they are what CI uses:

```bash
sbt ++2.13 test
sbt ++3 test
```

## Code Style

Formatting is [Scalafmt](https://scalameta.org/scalafmt/) (`.scalafmt.conf`), checked in
CI. Three conventions beyond formatting are worth knowing before they surprise you:

**Imports are ordered by scalafix, not by hand.** `OrganizeImports` (`.scalafix.conf`)
groups them `java`/`scala`/everything-else, sorts ASCII within a group, and **deletes
unused ones**. `scalafixAll --check` fails CI on a violation, so run `scalafixAll` rather
than hand-fixing — and run it *before* `scalafmtAll`, per the pre-push gate above.

**`// SPEC:` anchors are load-bearing comments, not decoration.** Each one ties a TLA+
invariant to the code that upholds it, and `specs/verify_anchors.sh` checks them in **both**
directions: every invariant row in `specs/README.md` must have its anchor in the declared
file, *and* every `// SPEC:` anchor in `src/` must have a row. Deleting an anchor fails CI;
adding one without a README row fails CI too. See "Formal Specifications" below.

**Where a test lives is a semantic decision, not an organisational one.** Inside the `stm`
package, `private[stm]` members are visible — and a member always beats an implicit
conversion, so they **shadow the public extension methods**. `TxnVar` has a
`private[stm] lazy val get: F[T]` (`TxnVar.scala`); the API users call is
`TxnVarOps.get: Txn[V]` (`syntax/all/package.scala`). So:

- Tests that drive the **public API** (anything importing `bengal.stm.syntax.all._`) must
  live **outside** `stm` — `ai.entrolution.model`, `.runtime`, `.spec`, `.soak`, `.syntax.all`.
  Put one inside `stm` and `txnVar.get` silently resolves to the `F[T]` member instead of the
  `Txn[V]` extension, and you get a type error or a "cannot be accessed" error that says
  nothing about the real cause.
- Tests that **white-box `private[stm]` internals** must live inside it — `IdFootprintSpec`,
  `IdFootprintPropertySpec` and `TxnLogEntrySpec` are in `bengal.stm.*` deliberately, because
  the members they exercise are invisible from anywhere else. They do not touch the syntax
  package, so nothing is shadowed.

## Testing

- All new features should include tests
- Bug fixes should include a test that would have caught the bug
- Tests are located in `src/test/scala`
- Run tests with `sbt test`

## Formal Specifications

The scheduler and commit protocols carry TLA+ specifications under `specs/`
(see `specs/README.md`). PRs touching `TxnRuntimeContext.scala`,
`TxnLogContext.scala`, `IdFootprint.scala`, or `TxnVarRuntimeId.scala` must
update the specs to match — or state in the PR description why no protocol
behaviour changed. CI enforces two things on these paths: `// SPEC:` anchors
listed in `specs/README.md` must exist, and pinned model-checking
expectations must reproduce (a pinned counterexample that stops reproducing
means the protocol changed — update the spec and `specs/README.md`'s verdict
table together).

`specs/README.md` is the **single source of truth for verdicts**. Do not record a
verdict anywhere else; two copies of a verdict is how the last set of them drifted
apart.

## License

By contributing to Bengal STM, you agree that your contributions will be licensed under the Apache License 2.0.

## Questions?

If you have questions about contributing, feel free to open an issue for discussion.
