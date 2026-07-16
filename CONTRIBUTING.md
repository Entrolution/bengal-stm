# Contributing to Bengal STM

Thank you for your interest in contributing to Bengal STM! This document provides guidelines and information for contributors.

## Code of Conduct

This project adheres to a [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

## How to Contribute

### Reporting Issues

Do not report security vulnerabilities as public issues — see [SECURITY.md](SECURITY.md) for the disclosure process.

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

GITHUB_ACTIONS=true sbt scalafmtCheckAll scalafmtSbtCheck headerCheckAll 'scalafixAll --check' +test
sbt mimaReportBinaryIssues      # binary compatibility against the last release
```

Two things about that order are not arbitrary:

**Scalafix must run before scalafmt.** `scalafixAll` *rewrites* your imports
(`OrganizeImports`: grouped `java`/`scala`/rest, ASCII order, unused ones
deleted), and those rewrites are not formatted. Run `scalafmtAll scalafixAll` in
that order and you push files that scalafix has left unformatted — `scalafmtCheckAll`
then fails on a tree you just formatted. This repo has made that mistake.

**`GITHUB_ACTIONS=true` is not decoration.** sbt-typelevel keys **fatal warnings** off the
`GITHUB_ACTIONS` environment variable — not `CI`; `CI=true` compiles with warnings
non-fatal and green-lights a build that real CI fails — so a warning that is merely a
warning on your machine is an **error** on the build. If you want to know what CI will
say, ask it the way CI asks.

### Releasing

Releases are tag-driven: pushing a `v*` tag runs the sbt-typelevel release workflow, which
publishes to Maven Central. There are no snapshots (`tlCiReleaseBranches` is empty — only
tags release), and MiMa gates binary compatibility against the previous release within the
minor line as part of the same run.

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
- The suite includes a soak/oracle layer (`src/test/scala/soak/`): randomized
  serializability rounds checked by a cycle-detecting history oracle (`History`), plus a
  bounded-buffer retry soak. They run as part of the ordinary suite — no tag, no exclusion —
  and are the behavioural counterpart to the TLA+ specs; `specs/README.md` leans on them for
  its fix-reversion evidence
- A local coverage report: `sbt coverage test coverageReport`

## Formal Specifications

The scheduler and commit protocols carry TLA+ specifications under `specs/`
(see `specs/README.md`). PRs touching `TxnRuntimeContext.scala`,
`TxnLogContext.scala`, `IdFootprint.scala`, or `TxnVarRuntimeId.scala` must
update the specs to match — or state in the PR description why no protocol
behaviour changed.

```bash
./specs/expectations.sh --list          # validate the registry (no TLC; instant)
./specs/expectations.sh                 # model-check the push-gated expectations
./specs/negative_controls.sh            # break each fix, require its red to come back
                                        # (a spec edit that touches a mutation site must
                                        # regenerate the affected specs/nc/ patches)
./specs/verify_anchors.sh               # every // SPEC: anchor maps to a row
```

**Every config declares its own expected verdict**, in a `\* @expect` directive at
the top of the `.cfg`. That is the only place a verdict is stated — CI derives its
steps from the directory rather than from a hand-maintained list, so a config with
no verdict, or a config nothing runs, both fail the build.

Do **not** restate the verdict in the config's prose. `--list` rejects it. Explain
*why* a config verifies as it does at any length; just do not say *what* it
verifies as in a second place. Two copies of a verdict is exactly how the last set
drifted: four headers read "EXPECTED RED" for months while CI asserted clean, and
nothing could see the contradiction because one was a sentence and the other was a
command-line argument.

State counts are **generated, not remembered** (`specs/measured.tsv`). If a spec
change moves the state space, CI regenerates the file, the diff fails the build,
and you update `specs/README.md`'s table to match. Do not hand-edit either.

If a pinned counterexample stops reproducing, the protocol changed. Update the
spec, the config's `@expect`, and the verdict table in `specs/README.md`.

## License

By contributing to Bengal STM, you agree that your contributions will be licensed under the Apache License 2.0.

## Questions?

If you have questions about contributing, feel free to open an issue for discussion.
