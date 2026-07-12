# Throughput benchmarks

What did the H1–H6 correctness fixes cost?

```bash
sbt 'benchmarks/Jmh/run -f1 -wi 8 -i 12 .*StmThroughputBench.*'
```

The benchmarks module is **not aggregated** into the root project, so `sbt test` and
CI never build it.

## Read this before trusting any number you produce here

**Do not run these on a laptop.** The first attempt at this measurement was taken on a
MacBook and produced confident, precise-looking numbers that were **entirely wrong** —
it reported the uncontended commit path had regressed **-27%**, and that a candidate
optimisation made things *worse*. Both conclusions were artifacts of thermal
throttling.

The tell was a control that should have been run first: re-running **identical code**
twenty minutes later scored **16,400 → 7,236 ops/s**. A 2.3× swing from machine state
alone, which is larger than any effect being measured.

So the protocol is **A → B → A**, and the first thing to look at is not the result but
whether the two A runs agree:

| | worst drift between identical runs |
|---|---|
| MacBook (thermal throttling) | **130%** — unusable |
| vast.ai, dedicated Ryzen 9 5900X, 24 cores | **6.9%** — usable |

A dedicated box costs about $0.07/hr and forty minutes. There is no version of this
measurement that is worth doing on hardware that throttles.

## Method

`before` is `4b0638d` — the tree with H4/H5/H1 already fixed, but **not** H2, H3 or H6.
`after` is the current library. Every fix in the workstream was `private[stm]`, so the
same benchmark compiles unchanged against both:

```bash
git archive 4b0638d src/main/scala | tar -x -C /tmp/before
# swap /tmp/before/src/main over src/main, sbt clean, re-run, swap back
```

`@OperationsPerInvocation(32)` makes JMH report per-**transaction** throughput, so all
benchmarks are directly comparable.

## Results

Dedicated Ryzen 9 5900X (24 cores), JDK 21, `-f1 -wi 8 -i 12`. Per-transaction ops/s.

| benchmark | before | after | change | what it measures |
|---|---:|---:|---:|---|
| `uncontendedCommit` | 14,178 | 13,207 | **−7%** | pure per-commit overhead |
| `disjointConcurrent` | 6,951 | 6,694 | **−4%** | max concurrency, no conflicts |
| `contendedConcurrent` | 4,482 | 4,440 | **−1%** | full conflict, scheduler hand-off |
| `mapWriteConcurrent` | 6,814 | 6,771 | **−1%** | map writes on distinct keys |
| `wholeMapReadPlusInsert` | 716 | 676 | **−6%** | the H5 idiom (large logs) |
| `dataDependentKey` | 3,653 | 3,223 | **−12%** | H6 coverage check + refinement |
| `underDeclaredConcurrent` | 6,332 | 3,756 | **−41%** | **the H3 cliff** |

Three of the seven were measured before the `traverse` optimisation below landed; their
logs are small, so it does not move them measurably.

## What the numbers say

**Ordinary workloads pay 1–7%.** The H6 coverage check computes the log's footprint on
every commit — it used to be forced only on the dirty path — and that is genuinely cheap
when the log is small. The laptop's claim of −27% here was noise.

**`underDeclaredConcurrent` pays −41%, and that is the fix working as designed.** A
transaction whose static analysis threw now has a footprint flagged as incompatible with
everything, so it runs alone. That path used to be *silently unsound* — it skewed 198/200
contended reps. Serialising it is the price of correctness, it is confined to that path,
and it is worth paying.

**`dataDependentKey` pays −12%** for the coverage check plus the re-runs it triggers when
the declared footprint does not describe the transaction. The soak reports that around
80% of data-dependent operations diverge, so this path really is doing work.

## One optimisation this measurement bought

`TxnLogValid.idFootprint` used `parTraverse`, spawning a fiber per log entry. Every
entry's footprint is a **pure computation** (a cached `runtimeId` for a var, a UUID hash
for a map entry), so there is nothing to overlap and the fibers were pure overhead — and
a whole-map read expands into one log entry *per key*, so the fiber count tracked the map
size.

Switching to `traverse` took `wholeMapReadPlusInsert` from **566 → 676 ops/s (+19%)`,
turning that workload's cost from **−21% into −6%**. Nothing else moved outside noise.

Note that the laptop measured this same change as making things *worse across the board*.
It was throttling. The instrument has to be verified before the result means anything —
the same discipline the TLA+ negative controls and the soak's fix-reversion checks apply
everywhere else in this project.
