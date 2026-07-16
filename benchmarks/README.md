# Throughput benchmarks

What did the correctness fixes cost?

```bash
sbt 'benchmarks/Jmh/run .*StmThroughputBench.*'
```

The defaults in `StmThroughputBench` **are** the protocol used below (`-f5 -wi 5 -i 10`). The
primary table under Results is the current post-rework measurement (2026-07-14); only the
dirty-check table further down is historical — see the banners on each. The benchmarks module
is **not aggregated** into the root project, so `sbt test` and CI never build it.

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
JIT-sensitive — still drifted **4.7%** at `-f5` in the published campaign (an earlier campaign
saw 6.0%), which cannot support a claim under ~9%. It was re-run at **`-f20`**, where it drifts
**1.6%**. That is the only reason its number below is believable.

A dedicated box costs about $0.13 and an hour.

## Method

Three trees, one box. The current campaign is **A → B → A** (2026-07-14, reworked harness); the
**C** comparison is a separate, earlier campaign preserved below (2026-07-13, pre-rework harness
— its C tree is not reconstructible against the current code).

| | |
|---|---|
| **A** | the current library |
| **B** | `4b0638d` — H4/H5/H1 fixed, but **not** H2, H3, H6, the absent-key lost wakeup, or the null fixes |
| **C** | identical to A **except** the commit-time dirty check is still there |

**A vs B** is what the correctness work cost. **A vs C** was what deleting the dirty check bought
(the historical table below). **A vs A** is the control: if the two A runs disagree, nothing else
here means anything.

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

Dedicated AMD Ryzen 9 5950X (16 cores; the vast.ai host exposes 30 of its 32 threads), JDK
21.0.11, Illinois, 2026-07-14 — measured with the **reworked harness** (steady-state inserts,
bounded under-declaration, 33-op crediting, `@Threads(1)`, teardown assertions), A → B → A.
`-f5 -wi 5 -i 10`, except `uncontendedCommit` at `-f20`. Per-transaction ops/s, ± the 99.9% CI.
`after` is the mean of the two A runs; drift is the disagreement between them, and an effect is
claimed only when it clears twice its row's drift.

Rows are **not comparable to the previously published table** (kept below for the dirty-check
comparison): `mapWriteConcurrent` and `underDeclaredConcurrent` measure changed workloads, and
"before" now carries every cost the old library had (the dirty check, the fiber-per-entry
footprint fold, hash-derived ids), so each row is the fix's cost **net of every subsequent win**.

### What the correctness work cost, net (current tree vs `4b0638d`)

| benchmark | before (`4b0638d`) | after | change | | drift |
|---|---:|---:|---:|:-:|---:|
| `underDeclaredConcurrent` | 8,437 ± 102 | 5,506 ± 72 | **−34.7%** | ● | 0.0% |
| `dataDependentKey` ‡ | 3,929 ± 424 | 4,518 ± 81 | **+15.0%** | ● | 0.6% |
| `wholeMapReadPlusInsert` | 964 ± 23 | 1,170 ± 26 | **+21.4%** | ● | 0.8% |
| `mapWriteConcurrent` | 7,337 ± 91 | 7,694 ± 95 | **+4.9%** | ● | 1.0% |
| `contendedConcurrent` | 5,740 ± 56 | 5,997 ± 80 | **+4.5%** | ● | 0.4% |
| `uncontendedCommit` ‡ | 17,834 ± 760 | 18,324 ± 341 (`-f20`) | +2.2% (`-f5`) | ○ | 4.7% (`-f5`) |
| `disjointConcurrent` | 8,670 ± 163 | 8,638 ± 166 | −0.4% | ○ | 2.6% |
| `crossMapInsert` | **deadlocks** † | 6,795 ± 93 | — | — | 5.7% |

● resolved · ○ **within this row's noise — no change detected, and none is claimed**

‡ `uncontendedCommit`'s cells mix protocols by design: `after` is the tighter `-f20` re-run,
while `change` and `drift` are from the `-f5` A→B→A campaign — the printed before/after
deliberately do not recompute to the printed change. And `dataDependentKey`'s before-column
carries a ±10.8% CI (its pre-fix run was noisy): the +15.0% direction is solid — the worst case
inside that CI still clears twice the row's drift — but the magnitude spans roughly +4% to +29%.

† `crossMapInsert` is the H2 topology — two transactions inserting fresh keys into two maps.
Run against the pre-H2 library, its **first fork deadlocked** (both fibers holding one
structural lock and waiting on the other, forever) and had to be killed ~38 minutes in: the
model's counterexample, reproduced live by the instrument built to measure it. The `4b0638d`
phase therefore ran with this benchmark excluded, and its row has no "before" by construction.

The headline is unchanged and now measured on the bounded workload: the H3 cliff is **−34.7%**
(drift 0.0% — the old harness's unbounded allocation moved the absolute numbers, not the ratio).
`dataDependentKey` **flipped sign**: the H6 coverage check's cost on this workload is now more
than paid for by the id-registry, fold, and dirty-check-deletion wins that landed after
`4b0638d`. The commit path itself sits inside its own noise against the pre-fix baseline while
carrying the coverage check on every commit.

### What deleting the commit-time dirty check bought (historical)

> Measured 2026-07-13 with the pre-rework harness on the 0.14-era tree — the dirty-check-restored
> "C" tree is not reconstructible against the current code, so this table is a preserved record,
> not a current measurement. Its question was settled: the check was removed because it could
> never fire — see `CoverageSubsumesDirty` in `specs/README.md`.

Same code either way; the *only* difference was the check.

| benchmark | with the check | without | gain | |
|---|---:|---:|---:|:-:|
| `wholeMapReadPlusInsert` | 837 ± 39 | 1,020 | **+21.9%** | ● |
| `uncontendedCommit` | 16,292 ± 498 | 18,415 | **+13.0%** | ● |
| `underDeclaredConcurrent` | 4,424 ± 182 | 4,759 | **+7.6%** | ● |
| `dataDependentKey` | 3,800 ± 252 | 3,983 | **+4.8%** | ● |
| `contendedConcurrent` | 5,398 ± 291 | 5,517 | +2.2% | ○ |
| `mapWriteConcurrent` | 7,814 ± 334 | 7,948 | +1.7% | ○ |
| `disjointConcurrent` | 8,161 ± 458 | 8,209 | +0.6% | ○ |

## What the numbers say

**The one real cost is `underDeclaredConcurrent`, at −34.7%, and that is the H3 fix working as
designed.** A transaction whose static analysis threw now carries a footprint flagged as
incompatible with everything, so it runs alone. That path used to be *silently unsound* — it
skewed 198 of 200 contended reps. Serialising it is the price of correctness, it is confined to
that path, and it is **steady-state, not a one-off a retry recovers**: such a transaction runs
alone every time, so nothing moves underneath it and nothing refines it. The README's "Static
analysis and transaction footprints" explains how to avoid triggering it at all. The figure
survived the harness rework unchanged — bounding the workload moved the absolute numbers, not
the ratio — which is what an effect that belongs to the *protocol* rather than the *instrument*
looks like.

**`dataDependentKey` is now a net +15.0%** — a sign flip from the −10% first measured. The
coverage check and its refinement re-runs still cost what they cost (the soak reports ~78% of
data-dependent operations diverging, so the path really does that work); what changed is
everything underneath it since `4b0638d`: allocator-issued ids instead of a UUID hash per map
entry, a `traverse` fold instead of a fiber per log entry, and no dirty-check fiber on any
commit. The "before" column carries all of those old costs, so this row now reads as the fix's
price net of the wins that followed it.

**Every resolved row except the cliff is a gain.** A whole-map read plus insert is **+21.4%**,
steady-state map inserts **+4.9%**, full write contention **+4.5%** — all while carrying H6's
coverage check on every commit and H5's whole-map/insert conflict, neither of which `4b0638d`
had. The commit path itself sits **inside its own noise** against the pre-fix baseline (+2.2%
against a 4.7% drift at `-f5`; 18,324 ± 341 at `-f20`): the honest claim is "not slower", not
"faster", and the earlier +6% headline came from a comparison that no longer isolates the same
things.

**`crossMapInsert` has no before-column and never will**: the pre-H2 library deadlocks on the
workload (see the † note above). Its 6,795 ops/s is a baseline for the future, and the deadlock
itself is the strongest fix-validation this suite has produced.

**Two workloads show no measurable change at all.** Not "a small change" — *nothing detectable*
at their own resolution. Do not read +2.2% or −0.4% as findings; they are the noise floor, and
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
