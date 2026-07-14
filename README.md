# Bengal STM

[![Build Status](https://github.com/Entrolution/bengal-stm/actions/workflows/ci.yml/badge.svg)](https://github.com/Entrolution/bengal-stm/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/ai.entrolution/bengal-stm_2.13)](https://maven-badges.herokuapp.com/maven-central/ai.entrolution/bengal-stm_2.13)
[![Scala 2.13](https://img.shields.io/badge/Scala-2.13-red.svg)](https://www.scala-lang.org/)
[![Scala 3](https://img.shields.io/badge/Scala-3-red.svg)](https://www.scala-lang.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

Software Transactional Memory for [Cats Effect](https://typelevel.org/cats-effect/) with intelligent scheduling.

Bengal STM lets you compose concurrent operations as in-memory transactions: you write the
sequence of reads and writes you want, and the library takes care of locking, retries,
semantic blocking and scheduling.

## Key Features

- **A scheduler that knows what each transaction will touch.** Before running a transaction,
  Bengal computes its **footprint** — the variables and map keys it will access — and runs
  only transactions that cannot conflict at the same time. A blindly optimistic STM lets
  conflicting transactions race and then throws the loser's work away; Bengal mostly avoids
  the collision. The footprint is more than a throughput device: reads take no locks and are
  never validated at commit time, so the footprint check is the *only* thing standing between
  a transaction and a stale read. See [Static analysis and transaction
  footprints](#static-analysis-and-transaction-footprints) — it also explains the one shape
  that costs you concurrency, and how to avoid it.

- **Transactional maps as a first-class structure.** `TxnVarMap` tracks conflicts per *key*,
  so two transactions writing different keys of the same map run concurrently. Wrapping a
  `Map` in a `TxnVar` makes every write conflict with every other.

- **Semantic blocking.** `waitFor` parks a transaction until its condition can hold, without
  blocking a thread. See [How `waitFor` wakes up](#how-waitfor-wakes-up) — the rule there is
  load-bearing, and getting it wrong parks a transaction permanently.

- **Built on Cats Effect**, polymorphic in `F[_]` over `Async`.

The commit and scheduling protocols are specified in TLA+ and model-checked in CI. That work
found eight concurrency defects in the shipped library — several of which no test could have
caught, and one of which it later proved a whole commit-time check was unreachable and could be
deleted. See [Correctness](#correctness).

## Requirements

- **Java**: 21 or later
- **Scala**: 2.13.x or 3.x

## Installation

Add the following dependency to your `build.sbt`:

```scala
libraryDependencies += "ai.entrolution" %% "bengal-stm" % "<version>"
```

See the [Maven Central badge](#bengal-stm) above for the latest version.

## Quick Start

```scala
import ai.entrolution.bengal.stm.STM
import ai.entrolution.bengal.stm.model._
import ai.entrolution.bengal.stm.syntax.all._
import cats.effect.{IO, IOApp}

object QuickStart extends IOApp.Simple {
  def run: IO[Unit] =
    STM.runtime[IO].flatMap { implicit stm =>
      for {
        counter <- TxnVar.of[IO, Int](0)
        _       <- counter.modify(_ + 1).commit
        value   <- counter.get.commit
        _       <- IO.println(s"Counter: $value")
      } yield ()
    }
}
```

All three imports are needed. `syntax.all._` is what brings `.get` / `.set` / `.modify` / `.commit`
into scope; without it `.get` fails with a `cannot be accessed` error rather than a missing-import
one, because the runtime's own `private[stm]` members shadow the extension methods.

## API Reference

| Example | Description | Type Signature | Notes |
|:--------|-------------|:---------------|:------|
| `STM.runtime[F]` | Creates a runtime in an `F[_]` container whose transaction results can be lifted into a container `F[_]` via `commit` | `def runtime[F[_]: Async]: F[STM[F]]` | |
| `txnVar.get.commit` | Commits a transaction and lifts the result into `F[_]` | `def commit: F[V]` | |
| `TxnVar.of[IO, List[Int]](List())` | Creates a transactional variable | `def of[F[_]: STM: Async, T](value: T): F[TxnVar[F, T]]` | |
| `TxnVarMap.of[IO, String, Int](Map())` | Creates a transactional map | `def of[F[_]: STM: Async, K, V](valueMap: Map[K, V]): F[TxnVarMap[F, K, V]]` | |
| `txnVar.get` | Retrieves value of transactional variable | `def get: Txn[V]` | |
| `txnVarMap.get` | Retrieves an immutable map (i.e. a view) representing transactional map state | `def get: Txn[Map[K, V]]` | Performance-wise it is better to retrieve individual keys instead of acquiring the entire map |
| `txnVarMap.get("David")` | Retrieves the value for a key, if present | `def get(key: => K): Txn[Option[V]]` | Returns `None` when the key does not exist — whether it was never created, or was deleted earlier in this transaction. It does **not** raise. |
| `txnVar.set(100)` | Sets the value of transactional variable | `def set(newValue: => V): Txn[Unit]` | |
| `txnVarMap.set(Map("David" -> 100))` | Uses an immutable map to set the transactional map state | `def set(newValueMap: => Map[K, V]): Txn[Unit]` | Better to set individual keys than the whole map. Creates/deletes key-values as needed. |
| `txnVarMap.set("David", 100)` | Upserts the key-value into the transactional map | `def set(key: => K, newValue: => V): Txn[Unit]` | Creates the key if it was not present |
| `txnVar.modify(_ + 5)` | Modifies the value of a transactional variable | `def modify(f: V => V): Txn[Unit]` | |
| `txnVarMap.modify("David", _ + 20)` | Modifies the value in a transactional map for a given key | `def modify(key: => K, f: V => V): Txn[Unit]` | Fails the transaction if the key is absent. Recoverable with `handleErrorWith`. |
| `txnVarMap.remove("David")` | Removes a key-value from the transactional map | `def remove(key: => K): Txn[Unit]` | Fails the transaction if the key is absent. Recoverable with `handleErrorWith`. |
| `STM[IO].pure(10)` | Lifts a value into a transactional monad | `def pure[V](value: V): Txn[V]` | |
| `STM[IO].delay(10 + 2)` | Lifts a by-name computation into a transactional monad | `def delay[V](value: => V): Txn[V]` | **Runs at least twice per commit attempt** — once in the analysis pass, once in the real run — and again on every retry. Must be free of side effects. See [Static analysis](#static-analysis-and-transaction-footprints). |
| `STM[IO].fromF(someIO)` | Lifts an `F[V]` into a transactional monad | `def fromF[V](spec: F[V]): Txn[V]` | Same multiple-evaluation rule as `delay`, and the same warning applies with more force: this is the only way to lift an arbitrary effect into a transaction. |
| `STM[IO].unit` | The unit transaction | `val unit: Txn[Unit]` | |
| `STM[IO].abort(new RuntimeException("foo"))` | Aborts the current transaction | `def abort(ex: Throwable): Txn[Unit]` | No changes are persisted. Surfaces as a failed `F` from `commit`, and is recoverable with `handleErrorWith`. |
| `txn.handleErrorWith(_ => STM[IO].pure("bar"))` | Absorbs an error/abort and remaps to another transaction | `def handleErrorWith(f: Throwable => Txn[V]): Txn[V]` | Recovers errors and aborts. It does **not** absorb a `waitFor` retry — a blocked transaction stays blocked. |
| `STM[IO].waitFor(value > 10)` | Semantically blocks a transaction until a condition is met | `def waitFor(predicate: => Boolean): Txn[Unit]` | No thread is blocked. **The predicate's inputs must be read from a `TxnVar`/`TxnVarMap` inside the same transaction** — see [How `waitFor` wakes up](#how-waitfor-wakes-up). A predicate that *throws* aborts the transaction rather than retrying it. |
| `txnVar.setF(Async[F].pure(100))` | Sets value via an effect `F[V]` | `def setF(newValue: F[V]): Txn[Unit]` | Requires `syntax.all._` import |
| `txnVar.modifyF(v => Async[F].pure(v + 1))` | Modifies value via an effectful function | `def modifyF(f: V => F[V]): Txn[Unit]` | Requires `syntax.all._` import |
| `txnVarMap.setF(Async[F].pure(Map("k" -> 1)))` | Sets map state via an effect | `def setF(newValueMap: F[Map[K, V]]): Txn[Unit]` | Requires `syntax.all._` import |
| `txnVarMap.modifyF(m => Async[F].pure(m))` | Modifies map via an effectful function | `def modifyF(f: Map[K,V] => F[Map[K,V]]): Txn[Unit]` | Requires `syntax.all._` import |
| `txnVarMap.setF(key, Async[F].pure(100))` | Upserts key-value via an effect | `def setF(key: => K, newValue: F[V]): Txn[Unit]` | Requires `syntax.all._` import |
| `txnVarMap.modifyF(key, v => Async[F].pure(v))` | Modifies key-value via an effectful function | `def modifyF(key: => K, f: V => F[V]): Txn[Unit]` | Requires `syntax.all._` import |
| `txn.handleErrorWithF(e => Async[F].pure(pure("fallback")))` | Effectful error recovery | `def handleErrorWithF(f: Throwable => F[Txn[V]]): Txn[V]` | Requires `syntax.all._` import |

**On `STM[F].` and the imports.** `pure`, `delay`, `fromF`, `unit`, `abort` and `waitFor` are
members of `STM[F]`, not free functions — call them as `STM[IO].pure(...)`. The extension
methods (`.get`, `.set`, `.modify`, `.commit`, and the `F`-variants) come from
`import ai.entrolution.bengal.stm.syntax.all._`. An implicit `STM[F]` in scope is **not**
enough on its own; you need the import.

**On effectful arguments.** The `F`-variants (`setF`, `modifyF`, `handleErrorWithF`) and the
`delay`/`fromF` combinators **must not encapsulate side effects** — but their evaluation
frequencies differ. `delay`/`fromF` thunks run in **both**
passes of every executed attempt that reaches them (the analysis pass and the log run), and
again on every retry; a flow already terminated at a `waitFor` retry or `abort` reaches them in
neither pass. The `F`-variant setter values are **not** forced during analysis: they run once
per executed attempt, plus once per retry. `handleErrorWithF`'s handler runs only on an error.
The common rule stands for all of them — a re-run must be harmless.

**On by-name arguments.** Every `=>` above is load-bearing rather than cosmetic. Whether an
expression is evaluated during the analysis pass — and can therefore throw there — depends on
exactly this; see [Static analysis](#static-analysis-and-transaction-footprints).

## Example: Bank Transfer

This example demonstrates transactional transfers between accounts with semantic blocking until the bank opens:

```scala
import ai.entrolution.bengal.stm.STM
import ai.entrolution.bengal.stm.model._
import ai.entrolution.bengal.stm.syntax.all._
import cats.effect.{IO, IOApp}
import scala.concurrent.duration._

object BankTransfer extends IOApp.Simple {

  def run: IO[Unit] = {
    def createAccount(
        name: String,
        initialBalance: Int,
        accounts: TxnVarMap[IO, String, Int]
    )(implicit stm: STM[IO]): IO[Unit] =
      accounts.set(name, initialBalance).commit

    def transferFunds(
        accounts: TxnVarMap[IO, String, Int],
        bankOpen: TxnVar[IO, Boolean],
        to: String,
        from: String,
        amount: Int
    )(implicit stm: STM[IO]): IO[Unit] =
      (for {
        balance    <- accounts.get(from)
        isBankOpen <- bankOpen.get
        _          <- STM[IO].waitFor(isBankOpen)
        _          <- STM[IO].waitFor(balance.exists(_ >= amount))
        _          <- accounts.modify(from, _ - amount)
        _          <- accounts.modify(to, _ + amount)
      } yield ()).commit

    def openBank(
        bankOpen: TxnVar[IO, Boolean]
    )(implicit stm: STM[IO]): IO[Unit] =
      for {
        _ <- IO.sleep(1000.millis)
        _ <- IO.println("Bank Open!")
        _ <- bankOpen.set(true).commit
      } yield ()

    def printAccounts(
        accounts: TxnVarMap[IO, String, Int]
    )(implicit stm: STM[IO]): IO[Unit] =
      for {
        accounts <- accounts.get.commit
        _ <- IO.println(accounts.toList.map { case (k, v) => s"$k: $v" }.mkString(", "))
      } yield ()

    STM.runtime[IO].flatMap { implicit stm =>
      for {
        bankOpen <- TxnVar.of(false)
        accounts <- TxnVarMap.of[IO, String, Int](Map())
        _        <- createAccount("David", 100, accounts)
        _        <- createAccount("Sasha", 0, accounts)
        _        <- printAccounts(accounts)
        _        <- openBank(bankOpen).start
        _        <- transferFunds(accounts, bankOpen, "Sasha", "David", 100)
        _        <- printAccounts(accounts)
      } yield ()
    }
  }
}
```

## Static analysis and transaction footprints

Bengal's scheduler is what makes it different from a blindly optimistic STM: before a
transaction runs, it works out that transaction's **footprint** — the set of variables and
map keys it will touch — and uses it to run only transactions that cannot conflict at the
same time. That is where the concurrency comes from.

To do that, Bengal runs your transaction through a static-analysis pass. It is a real
execution: reads happen, so values that later steps depend on are available. But **writes
are not applied**, because the transaction has not been scheduled yet.

That last point has a consequence worth knowing about.

### What the analysis pass actually evaluates

Not everything in your transaction runs during analysis, and the distinction decides whether
a given expression can throw there:

| Evaluated during analysis | Not evaluated during analysis |
|---|---|
| `delay` / `fromF` thunks | the **value** argument to `set` / `modify` |
| **map key** thunks (`map.get(k)`, `map.set(k, _)`, …) | `pure` values |

Both columns are by-name, so it is not something you can read off the call site — it is a
property of the combinator. The value you pass to `set` is suspended and the analyser never
looks at it; the *key* you pass to `set` is forced, because the footprint cannot be computed
without it.

### Reading back a value you just wrote

During the analysis pass, a read of something the transaction itself wrote earlier returns
the **pre-transaction** value, not the written one. If a step in the *forced* column cannot
cope with that, it throws — during analysis only, never at run time:

```scala
for {
  _   <- inventory.set(sku, 10)
  qty <- inventory.get(sku)                      // analysis sees None; the real run sees Some(10)
  n   <- STM[IO].delay(qty.get + 1)              // forced: `.get` on None throws, in analysis only
  _   <- restock.set(sku, n)
} yield ()
```

Note where the `.get` sits. Written as `restock.set(sku, qty.get + 1)` this transaction is
**fine** — the value argument is never evaluated by the analyser, so nothing throws and the
footprint is complete. Move the same expression into a `delay` and it becomes the problem
case. The hazard follows the combinator, not the expression.

The other way to hit it is a key thunk that throws — for example, computing a map key from
a value you read back and `.get`-ing an `Option` to do it.

Bengal handles this safely, and the safe answer costs throughput. When analysis cannot
determine the whole footprint, the transaction is marked as **under-approximated**, and an
under-approximated footprint is treated as conflicting with *everything*. The transaction
runs **alone**, and it keeps doing so on every attempt — this is not a one-off cost that a
retry recovers.

That is deliberate, and it is not conservatism for its own sake. Reads are not validated at
commit time and take no locks, so the scheduler's footprint check is the *only* thing
standing between a transaction and a stale read. A footprint that is missing entries does
not make the scheduler slightly less precise — it switches its protection off. Running such
a transaction on its own is what keeps it correct. (Before this was fixed, a pair of such
transactions produced non-serializable results in 198 of 200 contended runs.)

Measured cost: **−34.7%** throughput against the same workload before the fix (8,437 → 5,506
ops/s on the current instrument), roughly two-thirds of what a comparable transaction with a
fully-known footprint achieves. A data-dependent key (below) is a net **+15%** on the current
tree — the coverage check costs what it costs, and the id-registry and fold work that landed
since more than pays for it.

**Nothing else got slower.** The commit path sits inside its own noise against the pre-fix
baseline while carrying H6's coverage check on every commit, and a whole-map read plus insert is
**21% faster** — because the same work that added the coverage check also proved the older
commit-time *dirty* check could never fire, and deleting that (plus the id-registry and fold
work) was worth more than the coverage check costs. (Re-measured 2026-07-14 on a dedicated
Ryzen 9 5950X with the reworked harness — see [benchmarks](benchmarks/README.md).) Read that
README's opening before trusting any number you produce there yourself: it leads with the two
ways these measurements have already been got wrong.

### Avoiding it

Keep the throwing expression out of the forced positions:

```scala
// Best: keep the value you are about to write, instead of reading it back.
val qty = 10
for {
  _ <- inventory.set(sku, qty)
  _ <- restock.set(sku, qty + 1)
} yield ()

// Or make the forced expression total, so nothing throws during analysis.
for {
  _   <- inventory.set(sku, 10)
  qty <- inventory.get(sku)
  n   <- STM[IO].delay(qty.getOrElse(0) + 1)   // `.getOrElse`, not `.get`
  _   <- restock.set(sku, n)
} yield ()
```

The same applies to anything else that can throw in a forced position — partial pattern
matches, `.head` on a possibly-empty collection, and so on. If it throws only because a write
has not been applied yet, it costs you concurrency rather than correctness.

### Footprints computed from values you read

A related case: if a map key is computed from a value the transaction reads, the footprint
depends on that value — and the analysis pass runs *before* the transaction is scheduled, so
the value can change in between. Bengal detects this at commit time, discards the run before
it publishes anything, and re-runs with the correct footprint. It is safe; it costs a retry.

## How `waitFor` wakes up

`waitFor(predicate)` parks the transaction until the predicate can hold. What wakes it is
**not** the predicate — it is the transaction's **read set**. A parked transaction is woken
when another transaction commits a write that its footprint conflicts with; it then re-runs
from the top and re-evaluates the predicate against fresh reads.

The rule that follows is short, and getting it wrong parks a transaction permanently:

> **Everything the predicate depends on must be read from a `TxnVar` or `TxnVarMap` inside the
> same transaction.**

```scala
// CORRECT — `isOpen` is read inside the transaction, so it is in the read set,
// and a commit to `bankOpen` wakes this.
for {
  isOpen <- bankOpen.get
  _      <- STM[IO].waitFor(isOpen)
  _      <- from.modify(_ - amount)
} yield ()

// WRONG — the read is hoisted out. The transaction's read set is empty, nothing
// can ever conflict with it, and no commit will ever wake it. It parks forever.
val isOpen = bankOpenRef.get.unsafeRunSync()
for {
  _ <- STM[IO].waitFor(isOpen)
  _ <- from.modify(_ - amount)
} yield ()
```

Two further details:

- A predicate that **throws** aborts the transaction; it does not retry it.
- `handleErrorWith` does **not** absorb a `waitFor` retry. Wrapping a blocking transaction in
  an error handler will not make it stop blocking.

## Cancelling a commit

Cancelling the `F` returned by `commit` — a `timeout`, a lost `race`, supervisor shutdown —
**abandons** the transaction. Once the cancellation completes:

- the transaction never begins executing again;
- a parked `waitFor` transaction is removed from the retry machinery — no later commit can wake
  it, so it cannot run and publish after its caller has gone;
- all scheduler bookkeeping is released, and transactions queued behind the abandoned one
  proceed.

One carve-out: the **atomic commit window** itself is uncancelable. A window already executing
when cancellation arrives runs to completion, and its writes may be published — cancellation
does not interrupt a window in flight; it prevents every future one, promptly, without waiting
on the in-flight one. `commit.timeout(d)` therefore composes safely with `waitFor`: the timeout
fires, the transaction is abandoned, and nothing leaks.

## One runtime per variable set

Every `TxnVar` and `TxnVarMap` belongs to the `STM` runtime in scope when it was created, and
all transactions touching it must be committed through that runtime. Sharing a variable across
two runtimes is **undefined**: each scheduler enforces its conflict guarantees only over its own
transactions, so two conflicting transactions committed under different runtimes can interleave
unchecked — stale reads and lost updates with no error raised. One `STM.runtime` per
application (or per isolated variable set) is the rule.

## Correctness

Bengal's commit and scheduling protocols are specified in TLA+ and model-checked in CI on
every change. The specs live in [`specs/`](specs/README.md).

That work found a lock-order deadlock, a write skew, a phantom read, a double execution, two
distinct lost wakeups, a data-dependent footprint divergence, and an unbounded retry spin —
all in the shipped library. Every one is fixed, and every one is pinned by a CI expectation,
so its counterexample would come back if the fix regressed.

The part worth knowing, if you are weighing a library like this one:

> **Several of these cannot be found by running the code at all.** A lost wakeup needs a
> conflicting transaction's entire submit-and-commit to land inside a window a couple of
> microseconds wide. Randomized testing did not produce one across millions of operations,
> and neither did a soak built specifically to try. The model finds it in seconds.

That is measured, not asserted: every fix was reverted in turn and the behavioural suites
re-run to see which would have caught it. The table is in
[`specs/README.md`](specs/README.md). The conclusion it points to is worth stating plainly —
**a green test suite is not evidence that these protocols are correct. The pinned models
are.**

## Background

For an introduction to STM concepts, see [Beautiful Concurrency](https://www.microsoft.com/en-us/research/wp-content/uploads/2016/02/beautiful.pdf) by Simon Peyton Jones.

## FAQ

### Why another STM implementation?

Under high contention, a blindly optimistic STM does a lot of work it then throws away. In
production that pushed us toward running transactions sequentially, which gives up the point of
STM. Bengal's scheduler works out what each transaction will touch before running it, so
conflicting transactions are kept apart instead of colliding and retrying.

Bengal also treats `Map` as a first-class transactional structure, roughly the way a database
treats an index. That makes structural updates an interesting scheduling problem — a whole-map
read has to conflict with a new-key insert somewhere else in the map, or you get phantoms — and
it is worth the trouble in practice.

### How does Bengal differ from cats-stm?

[cats-stm](https://timwspence.github.io/cats-stm/) is an excellent STM for Cats Effect. Bengal
differs in:

- **Scheduling**: cats-stm executes optimistically and retries on conflict. Bengal computes each
  transaction's footprint up front and schedules around conflicts.
- **Maps**: `TxnVarMap` tracks conflicts per key, rather than per map.
- **Implementation**: [Free monads](https://typelevel.org/cats/datatypes/freemonad.html) with two
  interpreters — one for the static-analysis pass, one for the transaction log.
- **API**: cats-stm has `orElse` to bypass a retry; Bengal omits it (see below).
- **Verification**: Bengal's commit and scheduling protocols are specified in TLA+ and
  model-checked in CI. See [Correctness](#correctness).

### Why is there no way to bypass `waitFor`?

Because Bengal abandons a transaction the instant a `waitFor` predicate fails, rather than running
it to completion and discarding the result. That short-circuit is only sound if nothing downstream
could still rescue the transaction — which is exactly what an `orElse` would be. Adding one would
mean checking, on every failed predicate, whether some later branch might recover it. `waitFor`
means *block until this holds*, and nothing else.

### Why 'Bengal'?

Bengals are a very playful and active cat breed. The name fits a library built on Cats.

## Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

Bengal STM is licensed under the [Apache License 2.0](LICENSE).

Copyright 2023 Greg von Nessi
