# Throughput benchmarks

What did the correctness fixes cost?

```bash
sbt 'benchmarks/Jmh/run .*StmThroughputBench.*'
```

The defaults in `StmThroughputBench` **are** the protocol used below (`-f5 -wi 5 -i 10`) — but
the tables themselves are historical since the harness rework; see the banner under Results. The benchmarks module is **not aggregated** into the root
project, so `sbt test` and CI never build it.

## Read this before trusting any number you produce here

**Two separate mistakes have been made measuring this, and both produced numbers that looked
fine.**

**Do not run these on a laptop.** The first attempt was taken on a MacBook and was *entirely
wrong* — it reported the uncontended commit path had regressed **−27%**, and that a candidate
optimisation made things *worse*. Both were thermal throttling. The tell was a control that
should have been run first: re-running **identical code** twenty minutes later scored
**16,400 → 7,236 ops/s**. A 2.3× swing from machine state alone, larger than any effect being
measured.

**And a stable machine is not enough — the instrument also has to resolve the effect you are
claiming.** The second attempt ran on a dedicated box and drifted ~7% between identical runs,
which felt trustworthy. It was then used to publish differences of **−1%, −4%, −6%**. Those were
*below the noise floor of the instrument that produced them*. Three of the seven published
figures were never resolvable at all, and were reported as findings anyway.

So the protocol is **A → B → A**, and the first thing to look at is not the result but the
**drift between the two A runs**. Every row below carries its own drift, and an effect is only
reported as real if it clears **twice** that.

**Forks, not iterations, are the lever.** A single fork measures one JVM's JIT and allocation
luck; more iterations just measure that same luck more precisely. Going `-f1 → -f5` cut the drift
from 6.5% to ~3%. And `uncontendedCommit` — the fastest benchmark here, so the most
JIT-sensitive — still drifted **6.0%** at `-f5`, which cannot support a claim under 12%. It was
re-run at **`-f20`**, where it drifts **1.6%**. That is the only reason its number below is
believable.

A dedicated box costs about $0.13 and an hour.

## Method

Three trees, four phases, one box: **A → B → C → A**.

| | |
|---|---|
| **A** | the current library |
| **B** | `4b0638d` — H4/H5/H1 fixed, but **not** H2, H3, H6, the absent-key lost wakeup, or the null fixes |
| **C** | identical to A **except** the commit-time dirty check is still there |

**A vs B** is what the correctness work cost. **A vs C** is what deleting the dirty check bought.
**A vs A** is the control: if the two A runs disagree, nothing else here means anything.

Every fix was `private[stm]`, so the same benchmark compiles unchanged against all three, and
only `src/main` is swapped.

`@OperationsPerInvocation(32)` on the batched benchmarks makes JMH report per-**transaction**
throughput, so they are comparable with each other. (`uncontendedCommit` runs one transaction per
invocation and needs no annotation; `dataDependentKey` declares **33**, because its keySrc bump is
a 33rd real commit inside the timed region that must stay concurrent with the readers.) That `32`
is written literally and must track `Batch`; change one without the other and every number here is
silently wrong by the ratio.

**One comparability caveat**: `uncontendedCommit` is driven synchronously from the JMH worker
thread — one `unsafeRunSync` per transaction, no fiber fan-out — while every batched benchmark
amortizes 1/32 of a batch-sized fan-out's spawn/join into each reported per-transaction figure. Cross-benchmark ratios against the baseline therefore conflate scheduler
admission cost with fiber-orchestration cost — compare batched rows with batched rows, and treat
`x / uncontendedCommit` ratios as indicative only.

## Results

> **These tables are HISTORICAL as of the harness rework.** Three workloads changed underneath
> their rows — `mapWriteConcurrent` now measures steady-state *inserts* with a two-owner write
> set (it used to degenerate to per-key updates after two invocations), `underDeclaredConcurrent`
> is bounded (the same-transaction remove keeps `scratch` empty; the old shape's unbounded growth
> biased the −34% cliff with GC drag), and `dataDependentKey` is credited at 33 ops — plus
> `crossMapInsert` is new with no baseline, `@Threads(1)` is pinned, and the map-entry id cost
> model changed since measurement (see "The optimisation that came out of this"). The numbers
> below remain a faithful record of what was measured **with the pre-rework harness on the
> pre-rework cost model**; re-measurement on a dedicated box with the full A→B→C→A protocol is
> pending, and no current-tree number should be quoted from this table.

Dedicated AMD Ryzen 9 5950X (16 cores), JDK 21.0.11. `-f5 -wi 5 -i 10`, except
`uncontendedCommit` at `-f20`. Per-transaction ops/s, ± the 99.9% CI. `after` is the mean of the
two A runs.

### What the correctness fixes cost

| benchmark | before (`4b0638d`) | after | change | | drift |
|---|---:|---:|---:|:-:|---:|
| `underDeclaredConcurrent` | 7,244 ± 363 | 4,759 ± 233 | **−34%** | ● | 3.1% |
| `dataDependentKey` | 4,444 ± 217 | 3,983 ± 193 | **−10%** | ● | 0.3% |
| `wholeMapReadPlusInsert` | 905 ± 50 | 1,020 ± 49 | **+13%** | ● | 0.6% |
| `uncontendedCommit` | 17,356 ± 587 | 18,415 ± 660 | **+6%** | ● | 1.6% |
| `contendedConcurrent` | 5,254 ± 253 | 5,517 ± 274 | **+5%** | ● | 1.3% |
| `disjointConcurrent` | 8,031 ± 418 | 8,209 ± 445 | +2% | ○ | 1.1% |
| `mapWriteConcurrent` | 8,153 ± 422 | 7,948 ± 411 | −3% | ○ | 4.4% |

● resolved · ○ **within this row's noise — no change detected, and none is claimed**

### What deleting the commit-time dirty check bought

Same code either way; the *only* difference is the check. It was removed because it could never
fire — see `CoverageSubsumesDirty` in `specs/README.md`.

| benchmark | with the check | without | gain | |
|---|---:|---:|---:|:-:|
| `wholeMapReadPlusInsert` | 837 ± 39 | 1,020 | **+22.0%** | ● |
| `uncontendedCommit` | 16,292 ± 498 | 18,415 | **+13.0%** | ● |
| `underDeclaredConcurrent` | 4,424 ± 182 | 4,759 | **+7.6%** | ● |
| `dataDependentKey` | 3,800 ± 252 | 3,983 | **+4.8%** | ● |
| `contendedConcurrent` | 5,398 ± 291 | 5,517 | +2.2% | ○ |
| `mapWriteConcurrent` | 7,814 ± 334 | 7,948 | +1.7% | ○ |
| `disjointConcurrent` | 8,161 ± 458 | 8,209 | +0.6% | ○ |

## What the numbers say

**The one real cost is `underDeclaredConcurrent`, at −34%, and that is the H3 fix working as
designed.** A transaction whose static analysis threw now carries a footprint flagged as
incompatible with everything, so it runs alone. That path used to be *silently unsound* — it
skewed 198 of 200 contended reps. Serialising it is the price of correctness, it is confined to
that path, and it is **steady-state, not a one-off a retry recovers**: such a transaction runs
alone every time, so nothing moves underneath it and nothing refines it. The README's "Static
analysis and transaction footprints" explains how to avoid triggering it at all.

**`dataDependentKey` pays −10%** for the coverage check plus the re-runs it triggers when the
declared footprint does not describe the transaction. The soak reports around 80% of
data-dependent operations diverging, so this path really is doing that work.

**Everything else got FASTER, including the commit path itself.** That is not a rounding error
and it deserves stating plainly: the library now commits a simple transaction **6% faster than
it did before any of this correctness work**, and a whole-map read plus insert **13% faster** —
while carrying H6's coverage check on every commit and H5's whole-map/insert conflict, neither of
which it had before.

The reason is the second table. **The check that H6 made redundant cost more than H6 does.** The
commit-time dirty check forked a fiber, allocated a `Deferred`, and cancelled and joined it on
*every commit* — and a whole-map read expands into one log entry **per key**, so that fan-out
scaled with the size of the map. Removing it is worth **+13% on the commit path and +22% on
whole-map reads**, and it more than pays for what the coverage check costs.

**Three workloads show no measurable change at all.** Not "a small change" — *nothing detectable*
at their own resolution. Do not read the +2% and −3% as findings; they are the noise floor, and
they are printed only so nobody re-derives them and believes them.

## The optimisation that came out of this

`TxnLogValid.idFootprint` used `parTraverse`, spawning a fiber per log entry. Every entry's
footprint is trivial — a cached `runtimeId` for a var; at the time these figures were taken, a
UUID hash for a map entry (since replaced by a registry lookup that allocates only on a key's
first touch, which lowers the per-entry cost further) — so there was nothing to overlap and the
fibers were pure overhead. A whole-map read expands into one log entry *per key*, so the fiber
count tracked the map size. Switching to `traverse` is part of why `wholeMapReadPlusInsert` is
faster than the baseline rather than 21% slower in the published (pre-rework) measurement.

The laptop measured that same change as making things *worse across the board*. It was throttling.

**The instrument has to be verified before its output means anything** — and "verified" means both
that it is stable *and* that it can resolve the effect you intend to report. That is the same
discipline the TLA+ negative controls and the soaks' fix-reversion checks apply everywhere else in
this project, and it is the reason two of the three tables above have a drift column.

## Known, accepted contamination

Unjoined scheduler fibers — wake sweeps spawned at submission, unsub cascades spawned at
completion — can still be draining when an invocation's timer stops, bleeding CPU into the next
invocation's window. There is no quiescence API to await them. The tail is bounded (the fibers
close over the finished runtime and touch only bookkeeping), believed below the noise floor at
these scales, and recorded here so the next dedicated-box session can verify that rather than
assume it.
