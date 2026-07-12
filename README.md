# Bengal STM

[![Build Status](https://github.com/Entrolution/bengal-stm/actions/workflows/ci.yml/badge.svg)](https://github.com/Entrolution/bengal-stm/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/ai.entrolution/bengal-stm_2.13)](https://maven-badges.herokuapp.com/maven-central/ai.entrolution/bengal-stm_2.13)
[![Scala 2.13](https://img.shields.io/badge/Scala-2.13-red.svg)](https://www.scala-lang.org/)
[![Scala 3](https://img.shields.io/badge/Scala-3-red.svg)](https://www.scala-lang.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

Software Transactional Memory for [Cats Effect](https://typelevel.org/cats-effect/) with intelligent scheduling.

Bengal STM is a library for writing composable concurrency operations based on in-memory transactions. The library handles all aspects of concurrency management including locking, retries, semantic blocking, and optimised transaction scheduling. STM provides a higher-level concurrency abstraction that offers a safe, efficient, and composable alternative to locks, mutexes, and other low-level primitives.

## Key Features

- **Intelligent Runtime Scheduler**: Unlike blindly optimistic STM implementations, Bengal's runtime uses a custom scheduler that performs fast static analysis of transaction variable domains to reduce retry likelihood. This ensures consistent performance even for highly-contentious transactional variables.

- **First-Class Transactional Maps**: In addition to transactional variables (`TxnVar`), Bengal includes performant transactional maps (`TxnVarMap`) as a core API data structure, providing performance benefits over wrapping an entire map in a transactional variable.

- **Cats Effect Integration**: Built on Cats Effect for seamless integration with the Typelevel ecosystem.

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
import bengal.stm.STM
import bengal.stm.model._
import bengal.stm.syntax.all._
import cats.effect.{IO, IOApp}

object QuickStart extends IOApp.Simple {
  def run: IO[Unit] =
    STM.runtime[IO].flatMap { implicit stm =>
      for {
        counter <- TxnVar.of(0)
        _       <- counter.modify(_ + 1).commit
        value   <- counter.get.commit
        _       <- IO.println(s"Counter: $value")
      } yield ()
    }
}
```

## API Reference

| Example | Description | Type Signature | Notes |
|:--------|-------------|:---------------|:------|
| `STM.runtime[F]` | Creates a runtime in an `F[_]` container whose transaction results can be lifted into a container `F[_]` via `commit` | `def runtime[F[_]: Async]: F[STM[F]]` | |
| `txnVar.get.commit` | Commits a transaction and lifts the result into `F[_]` | `def commit: F[V]` | |
| `TxnVar.of[IO, List[Int]](List())` | Creates a transactional variable | `def of[F[_]: STM: Async, T](value: T): F[TxnVar[F, T]]` | |
| `TxnVarMap.of[IO, String, Int](Map())` | Creates a transactional map | `def of[F[_]: STM: Async, K, V](valueMap: Map[K, V]): F[TxnVarMap[F, K, V]]` | |
| `txnVar.get` | Retrieves value of transactional variable | `def get: Txn[V]` | |
| `txnVarMap.get` | Retrieves an immutable map (i.e. a view) representing transactional map state | `def get: Txn[Map[K, V]]` | Performance-wise it is better to retrieve individual keys instead of acquiring the entire map |
| `txnVarMap.get("David")` | Retrieves optional value depending on whether key exists in the map | `def get(key: K): Txn[Option[V]]` | Will raise an error if the key is never created (previously or current transaction). A `None` is returned if the value has been deleted in the current transaction. |
| `txnVar.set(100)` | Sets the value of transactional variable | `def set(newValue: V): Txn[Unit]` | |
| `txnVarMap.set(Map("David" -> 100))` | Uses an immutable map to set the transactional map state | `def set(newValueMap: Map[K, V]): Txn[Unit]` | Performance-wise it is better to set individual keys instead of setting the entire map. This operation will create/delete key-values as needed. |
| `txnVarMap.set("David", 100)` | Upserts the key-value into the transactional map | `def set(key: K, newValue: V): Txn[Unit]` | Will create the key-value in the transactional map if the key was not present |
| `txnVar.modify(_ + 5)` | Modifies the value of a transactional variable | `def modify(f: V => V): Txn[Unit]` | |
| `txnVarMap.modify("David", _ + 20)` | Modifies the value in a transactional map for a given key | `def modify(key: K, f: V => V): Txn[Unit]` | Will throw an error if the `key` is not present in the map |
| `txnVarMap.remove("David")` | Removes a key-value from the transactional map | `def remove(key: K): Txn[Unit]` | Will throw an error if the key doesn't exist in the map |
| `pure(10)` | Lifts a value into a transactional monad | `def pure[V](value: V): Txn[V]` | |
| `delay(10+2)` | Lifts a computation into a transactional monad (by-name value) | `def delay[V](value: => V): Txn[V]` | Argument will be evaluated every time a transaction is attempted. Not advised for side effects. |
| `abort(new RuntimeException("foo"))` | Aborts the current transaction | `def abort(ex: Throwable): Txn[Unit]` | Variables/Maps changes will not be persisted if the transaction is aborted |
| `txn.handleErrorWith(_ => pure("bar"))` | Absorbs an error/abort and remaps to another transaction | `def handleErrorWith(f: Throwable => Txn[V]): Txn[V]` | |
| `waitFor(value > 10)` | Semantically blocks a transaction until a condition is met | `def waitFor(predicate: => Boolean): Txn[Unit]` | Blocking is semantic (no thread locking). Implemented via retries initiated by variable/map updates. |
| `txnVar.setF(Async[F].pure(100))` | Sets value via an effect `F[V]` | `def setF(newValue: F[V]): Txn[Unit]` | Requires `syntax.all._` import |
| `txnVar.modifyF(v => Async[F].pure(v + 1))` | Modifies value via an effectful function | `def modifyF(f: V => F[V]): Txn[Unit]` | Requires `syntax.all._` import |
| `txnVarMap.set(Async[F].pure(Map("k" -> 1)))` | Sets map state via an effect | `def set(newValueMap: F[Map[K, V]]): Txn[Unit]` | Requires `syntax.all._` import |
| `txnVarMap.modifyF(m => Async[F].pure(m))` | Modifies map via an effectful function | `def modifyF(f: Map[K,V] => F[Map[K,V]]): Txn[Unit]` | Requires `syntax.all._` import |
| `txnVarMap.setF(key, Async[F].pure(100))` | Upserts key-value via an effect | `def setF(key: => K, newValue: F[V]): Txn[Unit]` | Requires `syntax.all._` import |
| `txnVarMap.modifyF(key, v => Async[F].pure(v))` | Modifies key-value via an effectful function | `def modifyF(key: => K, f: V => F[V]): Txn[Unit]` | Requires `syntax.all._` import |
| `txn.handleErrorWithF(e => Async[F].pure(pure("fallback")))` | Effectful error recovery | `def handleErrorWithF(f: Throwable => F[Txn[V]]): Txn[V]` | Requires `syntax.all._` import |

**Note on F-variant methods:** The methods suffixed with `F` (e.g. `setF`, `modifyF`, `handleErrorWithF`) are available via the `import bengal.stm.syntax.all._` import. The `F[_]` arguments passed to these methods **must not encapsulate side effects** — they are evaluated during transaction attempts and may be retried.

## Example: Bank Transfer

This example demonstrates transactional transfers between accounts with semantic blocking until the bank opens:

```scala
import bengal.stm.STM
import bengal.stm.model._
import bengal.stm.syntax.all._
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

### Reading back a value you just wrote

During the analysis pass, a read of something the transaction itself wrote earlier returns
the **pre-transaction** value, not the written one. If the next step cannot cope with that,
it throws — during analysis only, never at run time:

```scala
for {
  _   <- inventory.set(sku, 10)
  qty <- inventory.get(sku)      // analysis sees None; the real run sees Some(10)
  _   <- restock.set(sku, qty.get + 1)  // `.get` on None throws — but only during analysis
} yield ()
```

Bengal handles this safely, and the safe answer costs throughput. When analysis cannot
determine the whole footprint, the transaction is marked as **under-approximated**, and an
under-approximated footprint is treated as conflicting with *everything*. The transaction
runs **alone**.

That is deliberate, and it is not conservatism for its own sake. Reads are not validated at
commit time and take no locks, so the scheduler's footprint check is the *only* thing
standing between a transaction and a stale read. A footprint that is missing entries does
not make the scheduler slightly less precise — it switches its protection off. Running such
a transaction on its own is what keeps it correct. (Before this was fixed, the transaction
above could produce non-serializable results in ~99% of contended runs.)

Measured cost: such a transaction commits at roughly **half** the throughput of one whose
footprint is fully known. Everything else is unaffected.

### Avoiding it

Write the step so it does not depend on reading back your own write, or make it total:

```scala
// Keep the value you are about to write, instead of reading it back:
val qty = 10
for {
  _ <- inventory.set(sku, qty)
  _ <- restock.set(sku, qty + 1)
} yield ()

// Or make the continuation total, so nothing throws during analysis:
for {
  _   <- inventory.set(sku, 10)
  qty <- inventory.get(sku)
  _   <- restock.set(sku, qty.getOrElse(0) + 1)   // `.getOrElse`, not `.get`
} yield ()
```

The same applies to anything else that can throw inside a transaction's *analysis* —
partial pattern matches, `.head` on a possibly-empty collection, and so on. If it throws
only because a write has not been applied yet, it costs you concurrency rather than
correctness.

### Footprints computed from values you read

A related case: if a map key is computed from a value the transaction reads, the footprint
depends on that value — and the analysis pass runs *before* the transaction is scheduled, so
the value can change in between. Bengal detects this at commit time, discards the run before
it publishes anything, and re-runs with the correct footprint. It is safe; it costs a retry.

## Background

For an introduction to STM concepts, see [Beautiful Concurrency](https://www.microsoft.com/en-us/research/wp-content/uploads/2016/02/beautiful.pdf) by Simon Peyton Jones.

## FAQ

### Why another STM implementation?

Blindly optimistic execution strategies can lead to poor performance in high-contention scenarios. In production, this sometimes required falling back to sequential transaction execution, negating the benefits of STM. Bengal addresses this with a scheduler that performs static analysis to reduce contention, enabling genuine concurrency even in high-contention scenarios.

Additionally, Bengal treats `Map` as a fundamental transactional data structure (analogous to a database index), which presents interesting scheduling challenges around structural updates but proves very useful in practice.

### How does Bengal differ from cats-stm?

[cats-stm](https://timwspence.github.io/cats-stm/) is an excellent STM implementation for Cats Effect. Bengal differs in:

- **Implementation**: Bengal uses [Free Monads](https://typelevel.org/cats/datatypes/freemonad.html) with different interpreters for static analysis and building transactional logs
- **API design**: cats-stm has `orElse` for bypassing retries; Bengal intentionally omits this for clearer `waitFor` semantics
- **Initialization**: `TxnVar` and `TxnVarMap` initialization occurs outside the `Txn[_]` monad

### Why is there no way to bypass `waitFor`?

`waitFor` is designed to have clear semantic delineation from conditional `if` statements. Bengal short-circuits monadic evaluation on failed `waitFor` predicates as a performance optimization, which wouldn't be possible if bypass mechanisms needed to be checked.

### Why 'Bengal'?

Bengals are a very playful and active cat breed. The name fits a library built on Cats.

## Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

Bengal STM is licensed under the [Apache License 2.0](LICENSE).

Copyright 2023 Greg von Nessi
