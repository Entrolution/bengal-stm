# Bengal STM — Architecture

How the runtime is put together, in the order a transaction moves through it: the program
type, the two interpreters, the scheduler, the commit protocol, and the id spaces the
conflict detection runs on. Every claim of the form "this is verified" links to
[`specs/README.md`](../specs/README.md), which owns the proofs; user-facing semantics live
in the [README](../README.md). This document is the map, not a second source of truth.

## The program type

A transaction is a value: `Txn[V] = Free[TxnOrErr, V]`
([`model/package.scala`](../src/main/scala/bengal/stm/model/package.scala)), where
`TxnOrErr[V] = Either[TxnErratum, TxnAdt[V]]`. The `Left` channel carries the two terminal
errata — retry (from `waitFor`) and error (from `abort` or a failed operation) — and the
`Right` channel carries the operation ADT. Everything under `Txn` is `private[stm]`; user
code only ever sees `Txn[V]` itself.

The effect type `F` never appears in `Txn`. The ADT cases are path-dependent members of
`TxnAdtContext[F]`
([`model/TxnAdtContext.scala`](../src/main/scala/bengal/stm/model/TxnAdtContext.scala)), so
each case captures `F`-typed content (a `TxnVar[F, _]`, an `F[K]` key thunk) while the
`TxnAdt[V]` supertype erases it. That is what lets `Txn` values compose freely without
threading `F` through user signatures. The hierarchy is sealed: an ADT case can only be
declared in the file the interpreters live against, so an operation the interpreters do not
handle is a compile error, not a silent no-op.

`STM[F]` itself is a cake ([`STM.scala`](../src/main/scala/bengal/stm/STM.scala)) mixing
the ADT, log, compiler, runtime, and API contexts over one `F` — path-dependent types are
the reason it is a cake and not a bag of objects. The API context
([`api/internal/TxnApiContext.scala`](../src/main/scala/bengal/stm/api/internal/TxnApiContext.scala))
holds the smart constructors that lift ADT cases into `Free`;
[`syntax/all`](../src/main/scala/bengal/stm/syntax/all/package.scala) is the single public
extension surface over them. The model layer (`TxnVar`, `TxnVarMap`) depends on the runtime
only through `TxnIdAllocator` — the one-counter capability the factories need — so it
stands alone without a scheduler.

## The two interpreters, and why a transaction runs twice

[`runtime/TxnCompilerContext.scala`](../src/main/scala/bengal/stm/runtime/TxnCompilerContext.scala)
defines two `FunctionK` walks over the same program, and every commit attempt runs both:

1. **The static-analysis walker** folds the program into an `IdFootprint` — the set of ids
   the transaction reads and writes. Reads execute for real during this pass (a
   data-dependent key cannot be named without evaluating what it depends on); writes are
   never applied. A `delay`/`fromF` thunk therefore runs in both passes when the flow
   reaches it, which is why the README bans side effects in them.
2. **The log walker** is the real run: it folds the program into a `TxnLogValid` — one
   entry per touched entity, each carrying the initial value it observed and the value it
   intends to publish.

Both walkers stop at the first terminal erratum, so a step positioned after a `waitFor` or
`abort` runs in neither pass for that attempt. Errors short-circuit the log fold as a
stack-trace-free carrier exception that `handleErrorWith` catches and the runtime unwraps
at the boundary — the exception a caller sees is always the user's own.

The analysis pass has one load-bearing failure contract: if it **throws** (the ordinary
shape is a partial continuation on a read-your-own-write, which reads `None` during
analysis and `Some` at run time), the footprint is marked **under-approximated**, and an
under-approximated footprint conflicts with everything — the transaction runs alone, every
attempt. There is no sound way to compare against a set known to be incomplete, and the
measured price of serializing that path is in
[`benchmarks/README.md`](../benchmarks/README.md). The README's "Static analysis and
transaction footprints" section shows how to write transactions that never trigger it.

## The scheduler

[`runtime/TxnRuntimeContext.scala`](../src/main/scala/bengal/stm/runtime/TxnRuntimeContext.scala)
owns the concurrency contract the commit protocol assumes — **Contract C: two transactions
whose declared footprints are incompatible never overlap in their execute windows.**

Every submission walks one scaffold (`registerAndSweep`), and the order inside the graph
semaphore is load-bearing: the scan over `activeTransactions` snapshots the peer set
*before* the transaction inserts itself (a writer's footprint is incompatible with itself),
every dependency edge is complete *before* the readiness test reads the tally, and the
readiness test stays inside the semaphore that serializes it against the admission gate.
Incompatible peers are linked by dependency edges — the downstream side's tally rises, the
upstream side holds the release — and a transaction executes only when its tally is zero,
re-checked atomically at admission. Completion drains the transaction's edges exactly once,
which is what wakes whoever was waiting on it.

Two submission paths differ only in edge direction. A first submission always waits for its
incompatible peers. A dirty-path resubmission — the transaction ran, and its log or
footprint turned out stale — takes a **reversed** edge against peers that have not started:
they wait for it. The reversal is anti-starvation (re-queueing behind ever-arriving peers
could starve the refining transaction indefinitely), and it is sound because admission
re-tests the tally under the same semaphore that raised it.

Blocking (`waitFor`) parks the transaction in a retry map, and the park protocol closes the
lost-wakeup window with two guards in a fixed order: scan `activeTransactions` for a
conflictor that has not yet committed, then re-compare the log's read set against live
state for one that already has. Wakes fire from submission-time sweeps, before the
submitting transaction publishes. Cancelling a `commit` abandons the transaction through a
flag shared across every incarnation, re-checked inside every semaphore region that could
re-enter it.

The scheduler protocol — parking, wakes, abandonment, edge bookkeeping — is model-checked;
see the Scheduler rows in [`specs/README.md`](../specs/README.md)'s verdict table and its
variable-mapping appendix for the TLA+-to-Scala correspondence.

## The commit protocol

[`runtime/TxnLogContext.scala`](../src/main/scala/bengal/stm/runtime/TxnLogContext.scala)
publishes a log in three moves, all under the entities' commit locks:

1. **Lock in owner order.** The write set's locks are acquired in ascending order of the
   id of the entity that *owns* each lock. Owner → lock is injective, so one ascending sort
   is a global total order and no circular wait can form. Sorting by anything else is not
   equivalent: a fresh map key's entry is logged under the key's id but locks the *map's*
   structural lock — two different id spaces — and sorting by log key is precisely the
   deadlock the H2 negative control pins.
2. **Check coverage before publishing.** The declared footprint must `cover` what the run
   actually touched. Coverage is deliberately not a subset test — a whole-map read
   legitimately expands into an entry per key — and its soundness is machine-checked over
   every footprint triple (`LemmaCoverageIsSound`). If coverage fails, nothing publishes:
   the transaction refines its footprint from the actual log and goes around again via the
   reversed-edge resubmission above. This check is also why there is no commit-time dirty
   check: coverage subsumes it (`CoverageSubsumesDirty`).
3. **Publish.** Read-only entries commit as unit; writes are a `Ref.set` per var, or a
   structural-lock-guarded update per map entry, applied sequentially — serializability
   rests on Contract C plus the locks, not on commit interleaving.

## The id spaces

Conflict detection runs on
[`IdFootprint`](../src/main/scala/bengal/stm/model/runtime/IdFootprint.scala) over
[`TxnVarRuntimeId`](../src/main/scala/bengal/stm/model/runtime/TxnVarRuntimeId.scala)s.
Every id is a `Long` from one global allocator per runtime — entities and map keys draw
from the same counter, so ids never alias across kinds. A map key's id carries the map's id
as its **parent**, and the hierarchy is exactly two levels deep; every relation that walks
it is one-hop and says so.

The compatibility relation conflicts raw-id overlap against writes, and one-hop
parent-child in both directions — a whole-map read conflicts with any single-key write, and
a single-key write conflicts with a whole-map read. The coverage relation is asymmetric on
purpose: declaring a parent *write* covers child reads and writes alike, while a parent
*read* covers only child reads — reading a map does not announce that you will write a key
in it. A key keeps its id forever, deletes included: a parked transaction's footprint holds
that id, and evict-and-reallocate would wake nobody.

## Where the proofs live

| Claim | Where it is checked |
|---|---|
| Contract C, parking, wakes, abandonment | `specs/scheduler/` — Scheduler rows in [`specs/README.md`](../specs/README.md) |
| Lock order, coverage gate, refine loop | `specs/commit/` — Commit rows in the same table |
| Coverage soundness over all footprint triples | `specs/common/FootprintLemmas.tla` (`LemmaCoverageIsSound`) |
| Each fix's red reproduces when reverted | `specs/negative_controls.sh` — ten committed mutation patches, CI-run |
| Behavioural counterpart under randomized load | the soak layer in `src/test/scala/soak/` (serializability oracle, retry soak) |

The spec↔code anchor mapping is enforced in both directions by `specs/verify_anchors.sh`;
[`specs/README.md`](../specs/README.md) is the single source of truth for verdicts, state
counts, and the per-defect history.
