# Telescope vs MapStruct — Head-to-Head Performance Analysis

Goal: measure telescope's codegen path against MapStruct's compile-time-generated output, identify any real overhead,
and propose remediations where the gap is structural.

## Headline finding

**Telescope codegen is in MapStruct's performance class, and on a hash container it allocates less.** Separate runs land
on runners of different speeds, so a ratio built from two of them is not a measurement — hence the two columns below,
read as the caption describes.

| Tier (forward, codegen vs codegen)  |        MapStruct |        telescope | ratio | across runs            | allocation              |
| ----------------------------------- | ---------------: | ---------------: | ----: | ---------------------- | ----------------------- |
| flat (5 scalars)                    | 3.155 ± 0.019 ns | 3.362 ± 0.011 ns | 1.07x | ~1.07x (one run 1.13x) | 32 B/op both            |
| nested (one nested type)            | 4.361 ± 0.045 ns | 5.604 ± 0.033 ns | 1.29x | 1.04x–1.46x            | 48 B/op both            |
| deep (3 levels + 2 list hops)       |  62.38 ± 0.18 ns |  66.57 ± 0.52 ns | 1.07x | 1.07x–1.19x            | 376 B/op both           |
| container, Map-valued (100 entries) |     1262 ± 11 ns |     1244 ± 10 ns |   tie | measured once          | **7,528 vs 6,712 B/op** |
| container, Set-valued (100 entries) |     1455 ± 21 ns |      1445 ± 9 ns |   tie | measured once          | 7,576 B/op both         |

GitHub Actions run 34470676359, `ubuntu-latest`, 10 measured iterations. The `ratio` column is this run alone, so every
figure in it is comparable with every other. The `across runs` column is what keeps a single cell from travelling out of
context: only flat holds its value between runs, and the container tiers exist on one run so far.

Read the two container rows carefully — telescope's mean is marginally lower on both, and on neither does that mean it
won. On Set the intervals overlap almost entirely (telescope's sits inside MapStruct's), which is a clean tie. On Map
they overlap by under 3 ns, so call it a tie but not a settled one: tighter bands could separate them either way. What
is real on that row is the allocation — 6,712 against 7,528 bytes per operation, about 11% less — and allocation is
deterministic rather than hardware-dependent, which makes it the durable result.

The scalar tiers are unchanged in character: a 0.2 ns difference on flat, 4 ns on a 62 ns deep conversion, and both
outside their error bars, so each ratio is real within its own run. Only flat is also stable across runs, clustering at
~1.07x. Deep is not: this run's 1.07x is the low end of a 1.07x–1.19x spread whose middle sits nearer 1.14x, so read it
as a range too, and read this run as its optimistic edge. Nested is the widest of the three — 1.04x to 1.46x across CI
runs of this benchmark (the 1.46x end from Actions run 34148517680; this run is 34470676359), landing at 1.29x here. It
is a framework-overhead microbenchmark rather than a service-shaped workload; treat flat as the publishable figure, and
deep and nested as ranges.

Runtime tier, same run: flat 3.34x, nested 2.66x, deep 1.27x, and 1.04x–1.06x on the two container shapes. MapStruct has
no equivalent to this tier — it is what you get with no annotations and no build step — so these ratios say what the
convenience costs, not who wins.

### What the container tier is for

It did not exist until recently, and its absence is why a defect shipped: every tier here was List-only, a list has no
hash table, and the Bridge emitter passed the source element count straight into the hash container's `int` constructor
— which takes a table capacity, not an element count. Every benchmark was green while generated bridges allocated more
than the reflective path they exist to beat. The tier was added so that class of defect fails a measurement instead of
passing one.

## Methodology

`MapStructComparisonBenchmark.java` measures three implementations against the same source/target POJO pairs at three
nesting tiers:

- **flat** — 5 scalar fields, no nesting.
- **nested** — outer + 1 nested record/bean.
- **deep** — 3-level nesting + 2 list hops (2 dept × 3 teams = 6 leaves).

Four call shapes per tier:

- **`*_mapstruct_*`** — `INSTANCE.toRec(...)` / `INSTANCE.toBean(...)`. The interface-typed `INSTANCE` is the standard
  MapStruct entry point.
- **`*_telescope_codegen_*`** — `*BeanBridge.BRIDGE.read(...)` / `.set(...)`. Goes through the public `Telescope`
  lattice: `Telescope.read` → `BridgeTelescope.read` → `BridgeFn.forward` → static `forward`.
- **`*_telescope_codegen_static_forward`** — `*BeanBridge.forward(...)`. The static method the codegen already emits;
  bypasses every lattice dispatch hop. On clean CI hardware this is the floor for telescope codegen and isolates the
  lattice-dispatch tax; on a noisy laptop it picks up a JMH/EA artifact (see below).
- **`*_telescope_runtime_*`** — `Telescope.mapper(BeanCls.class, RecCls.class).forward/backward`. The runtime
  structural-Iso build path (no codegen).

## Results — earlier CI runs

> The tables in this section predate the current headline and are kept for the dispatch analysis that follows, which
> they are the evidence for. Where they disagree with the headline table, the headline is the current measurement. Both
> were GitHub Actions `ubuntu-latest`, 3 warmup + 5 iterations at 3s, single fork.

Two runs of the manual `Benchmarks` workflow on dedicated runners, taken because the first run's nested ratio looked too
good to publish on one sample. Absolute numbers differ between the runs (different runner generation) — read the ratios
and the within-run dispatch spread, not the raw ns. Both runs add the `BRIDGE_FN` one-interface-hop column across all
three tiers (earlier revisions measured it on flat only).

**Forward (bean → record), all four call shapes — Run 1:**

| Tier   |     MapStruct | codegen `BRIDGE.read` (lattice) | codegen `BRIDGE_FN` (1 hop) | codegen `static forward` (0 hop) | codegen/MapStruct |
| ------ | ------------: | ------------------------------: | --------------------------: | -------------------------------: | ----------------: |
| flat   | 2.527 ± 0.159 |                   2.692 ± 0.099 |               2.629 ± 0.294 |                    2.544 ± 0.077 |            1.065× |
| nested | 4.506 ± 0.366 |                   4.699 ± 0.146 |               4.722 ± 0.331 |                    4.503 ± 0.144 |            1.043× |
| deep   | 40.29 ± 0.801 |                   47.72 ± 1.503 |               48.41 ± 4.023 |                    47.40 ± 3.153 |            1.185× |

**Forward — Run 2 (tighter error bands):**

| Tier   |     MapStruct | codegen `BRIDGE.read` (lattice) | codegen `BRIDGE_FN` (1 hop) | codegen `static forward` (0 hop) | codegen/MapStruct |
| ------ | ------------: | ------------------------------: | --------------------------: | -------------------------------: | ----------------: |
| flat   | 3.104 ± 0.015 |                   3.326 ± 0.010 |               3.183 ± 0.013 |                    3.183 ± 0.010 |            1.072× |
| nested | 4.360 ± 0.349 |                   6.207 ± 0.086 |               5.896 ± 0.041 |                    5.902 ± 0.045 |            1.424× |
| deep   | 46.34 ± 0.269 |                   52.68 ± 0.162 |               52.59 ± 0.675 |                    51.92 ± 0.116 |            1.137× |

**Backward (record → bean), MapStruct vs codegen `BRIDGE.set` (Run 1):**

| Tier   |     MapStruct | codegen `BRIDGE.set` | codegen/MapStruct |
| ------ | ------------: | -------------------: | ----------------: |
| flat   | 2.708 ± 0.167 |        2.569 ± 0.093 |             0.95× |
| nested | 5.079 ± 0.468 |        5.023 ± 1.219 |             0.99× |
| deep   | 42.11 ± 1.575 |        49.54 ± 1.312 |             1.18× |

**What reproduces, and what doesn't** — as read at the time, from these two runs alone. Flat (1.065× / 1.072×) and deep
(1.185× / 1.137×) are stable across the pair. The headline run later measured deep at ~1.07×, the low end of a
1.07×–1.19× spread across all runs — so ~1.15× is what these two runs supported and roughly where the middle of that
spread still sits, not a figure that has since been superseded by a lower one. Nested swings 1.043× → 1.424×: the
`nested_mapstruct_forward` baseline carries a wide ±0.35 band both runs, so the nested ratio is a JMH-noisy figure, not
a real regression or improvement — don't publish a single number for it. On the dispatch spread, both runs agree:
`BRIDGE_FN` tracks `static forward` within error (run 2 flat: identical at 3.183), while `BRIDGE.read` sits a wrapper
tax above the floor that grows with depth — 0.14 → 0.31 → 0.77 ns across flat/nested/deep on the tight-band run, each
outside its error band. So `BRIDGE_FN` is the floor; the lattice wrapper is a small (≤0.8 ns) tax that scales with
nesting depth; and the deep residual over MapStruct is mostly the generated body (`static forward` alone is ~1.12× on
deep, ~5.6 ns of the ~6.3 ns gap).

**The whole of the following paragraph is superseded** — the runtime tier now lands at ~3.3× / ~2.7× / ~1.3× and
~1.04–1.06× on containers, and backward no longer trails forward. It is kept because the dispatch analysis below was
written against it. As measured on these two runs: forward flat 40.14, nested 64.37, deep 315.23 ns/op — ~16× / ~14× /
~8× of MapStruct — with backward higher still (flat 108.31, nested 148.63, deep 626.39), because building a bean
(allocate + N setters) is structurally heavier than a record's canonical-ctor invoke. At those numbers the runtime path
was the convenience surface for "I don't want to write codegen for this one mapper" rather than a contender; the lattice
sharpenings that closed the gap are recorded in `benchmarks/README.md`.

## Dispatch — `BRIDGE_FN` is the floor, and the lattice wrapper is a sub-nanosecond tax

The `*_codegen_static_forward` (zero dispatch), `*_bridgefn_forward` (one interface hop), and `*_codegen_forward`
(`BRIDGE.read`, full lattice) benchmarks isolate the dispatch cost by call shape. The `wrapper tax` column is the raw
`BRIDGE.read − static forward` gap (ns); the last column flags whether the two benchmarks' error bands are disjoint:

| Run·Tier  | `static forward` (0 hop) | `BRIDGE_FN` (1 hop) | `BRIDGE.read` (lattice) | wrapper tax | outside bands?   |
| --------- | -----------------------: | ------------------: | ----------------------: | ----------: | ---------------- |
| R1 flat   |                    2.544 |               2.629 |                   2.692 |     0.15 ns | no (±0.08–0.10)  |
| R2 flat   |                    3.183 |               3.183 |                   3.326 |     0.14 ns | yes (±0.01)      |
| R1 nested |                    4.503 |               4.722 |                   4.699 |     0.20 ns | no (±0.15)       |
| R2 nested |                    5.902 |               5.896 |                   6.207 |     0.31 ns | yes (±0.04–0.09) |
| R1 deep   |                    47.40 |               48.41 |                   47.72 |     0.32 ns | no (±1.5–3.2)    |
| R2 deep   |                    51.92 |               52.59 |                   52.68 |     0.77 ns | yes (±0.12–0.16) |
| R3 flat   |                    3.161 |               3.162 |                   3.362 |     0.20 ns | yes (±0.01–0.03) |
| R3 nested |                    5.913 |               5.908 |                   5.604 |    −0.31 ns | yes (±0.03)      |
| R3 deep   |                    66.27 |               69.22 |                   66.57 |     0.30 ns | no (±0.27–0.52)  |

R1's bands are 2–27× wider than the others' and every one of its rows reads "no" — too wide to resolve any of these
gaps. R2 resolves all three tiers; R3 resolves flat and nested but not deep. Where both resolve they agree on flat and
disagree on nested, which is itself the finding. Two things hold:

First, **`BRIDGE_FN` tracks the `static forward` floor within error on every tier**: identical on R2 flat (both 3.183),
tied on R2 nested (5.896 vs 5.902), and on R2 deep its ±0.675 band overlaps static (52.586 − 0.675 = 51.91 ≈ static
51.92). The one-interface-hop constant is monomorphic (one concrete `Fn` per bridge) and the JIT inlines it to the raw
static call. On R1 and R2 nothing measures below it; R3 nested is the one row where the lattice value does.

Second, **the full-lattice `BRIDGE.read` sits a small wrapper tax above that floor** — on R2, 0.14 ns (flat) → 0.31 ns
(nested) → 0.77 ns (deep), each outside the tight error bands, which read as the lattice composition depth showing
through: more nesting, more `Iso.then(...)` hops the wrapper carries.

**R3 does not reproduce that climb, so treat the depth-scaling as provisional.** On the same clean CI hardware it
measures +0.20 ns on flat (real), −0.31 ns on nested — the lattice value _below_ the static floor, outside the bands,
which is the "static-slower-than-lattice" shape the lesson below separates from the genuine laptop artifacts — and +0.30
ns on deep, inside the bands and therefore not a measurement at all. What survives all three runs is the magnitude: the
tax is under a nanosecond wherever it is resolvable, and on deep it is dwarfed by the generated-body gap anyway (see
next section). What does not survive is the monotonic ordering.

An earlier run reported a ~0.3–0.7 ns "lattice slice" and proposed closing it by emitting a directly-callable
`BRIDGE_FN` constant. `BRIDGE_FN` shipped (#182) — and it lands at the `static forward` floor, so an adopter who wants
the fastest passable value already has it on every row but R3 nested. The tax that remains sits only on the _composable_
`BRIDGE.read` value and is sub-nanosecond wherever it resolves at all; the type-specialized subclass (remediation #2)
would remove only that, for only the narrow case of hot-looping the composable value while refusing to switch to
`BRIDGE_FN`. Not worth it.

The lesson stands: **smoke runs lie, and one CI run can too.** Run 1's 1.04× nested looked like a headline until run 2
returned 1.42× on the same branch — the nested MapStruct baseline is JMH-noisy (±0.35). Laptop smoke runs earlier
produced a 2.9–3.6× "forward gap", a "telescope-faster-on-backward" claim, and a "static-slower-than-lattice" inversion,
all wrong — though R3 above measures that same inversion on nested with disjoint bands, so the shape is not exclusively
a laptop artifact. Trust the numbers that reproduce across runs: flat ~1.07×, deep 1.07×–1.19×, and `BRIDGE_FN` at the
floor.

## So is there a real gap?

**A small one on deep, and it is mostly not dispatch.** On the headline run deep measured ~1.07× forward — a 4.19 ns gap
on a 62 ns conversion, the low end of the 1.07×–1.19× spread. The split is the durable part: the zero-dispatch
`static forward` floor is _already_ ~1.06× on deep (3.90 ns of the 4.19, the generated **body** — six leaf conversions,
two list allocations, and per-field null-guards vs MapStruct's directly-inlined field sequence), with the lattice
wrapper adding the remainder. That remainder is 0.30 ns and sits inside the error bands, so on this run it cannot be
separated from zero and the body is effectively the whole gap; on R2, where the wrapper did resolve, the body led it
about 7:1. Either way the wrapper is not what an adopter would be paying for. Whether it matters at all:

- At <10M ops/sec on a hot mapper: invisible against application work.
- At >100M ops/sec on the deep tier: a ~4 ns per-call structural cost — measurable on a flame graph, rarely dominant.
- On flat: no gap to speak of (~1.07×). On nested the ratio is JMH-noisy run-to-run (1.04×–1.46×) — near-parity, but the
  noisy MapStruct baseline means no single figure is trustworthy.

## Remediations — status

### 1. Codegen emits a typed `BridgeFn<S, T>` constant — **shipped, measured, no perf effect**

`public static final BridgeFn<S, T> BRIDGE_FN = new Fn();` ships per generated bridge (asserted in
`BridgeProcessorTest`). It gives adopters a passable one-hop mapper value instead of a static method. The benchmark
tables above show it measures **at the `static forward` floor** on every run (run 2 flat: identical at 3.183; on R3 deep
only within a ±5.3 ns band, so that cell settles little either way) — the JIT inlines the monomorphic hop to the raw
static call, so it is the fastest passable value there is. Against `BRIDGE.read` it is usually the faster of the two by
the sub-nanosecond lattice-wrapper tax, though not always: on R3 nested the lattice value measured below it with
disjoint bands. So it is the ergonomic value (a `BridgeFn` you can pass around) and, on most rows, marginally the fast
one.

### 2. Type-specialized bridge subclass whose `read(S)` is the inlined body — **measured and declined**

The idea was to emit a `Telescope`/bridge subclass that removes the `BridgeFn` field and the `invokeinterface` wrapper
so `read(S)` _is_ the generated body. The data shrinks the premise to nothing worth building: the wrapper tax it would
remove is sub-nanosecond wherever it resolves at all, and it applies **only to the composable `BRIDGE.read` value** —
adopters who want the floor already have `BRIDGE_FN`, which sits there. Worse, on R2's deep tier, where the tax was at
its largest (~0.8 ns), the generated-body gap (~5.6 ns) led it about 7:1; on R3's deep tier the tax does not separate
from zero at all. Either way removing it barely moves the ratio. It would add ~100 LOC of `BridgeProcessor` complexity
to shave a sub-nanosecond tax off one of two already-shipped call shapes. **Not building it.** The only thing that would
move the deep number vs MapStruct is matching its generated _body_ (fewer null-guards, inlined leaf conversions) — a
separate, finer optimization, adopter-gated on someone actually hitting the deep tier above 100M ops/sec.

### 3. The CI-reproducible matrix is the baseline

The manual `Benchmarks` workflow produces the full matrix on dedicated hardware with tight error bands. Future PRs
trigger it on their branch and baseline-diff against a prior run's artifact. `-prof gc` is wired as a `profilers` input
(`gc`, `stack`, `perfasm`; locally `-Pjmh.profilers=gc`) for decomposing call cost vs allocation when chasing a
residual.

## What this revision ships

- **`BRIDGE_FN` benchmarked across all three tiers** (`nested_*_bridgefn_forward`, `deep_*_bridgefn_forward`; flat
  already existed). This is what lets the forward tables compare all four call shapes — static, one-hop, lattice,
  MapStruct — on each run and pin `BRIDGE_FN` to the floor.
- **The container tier**, Set- and Map-valued, which is where this revision's actual finding lives: a tie on time and
  about 11% less allocated on the Map shape. Every tier before it was List-only, which is how a sizing defect in the
  container emitter shipped green.
- **This analysis doc, corrected against three runs.** Prior revisions claimed a ~0.3–0.7 ns lattice slice and proposed
  two remediations to close it; a first fresh run then over-corrected to "dispatch is free everywhere". What is settled
  now is one half of that: `BRIDGE_FN` is the floor on every run, and the nested ratio is JMH-noisy. The lattice
  wrapper's tax is sub-nanosecond wherever it resolves, but its depth-scaling did not survive the third run and is
  marked provisional. The doc records the parity result, the ranges, and the measured-and-declined subclass decision.
- **No production code changed.** `BRIDGE_FN` already ships; the codegen is at parity as-is.

## Bottom line

Telescope codegen is in MapStruct's performance class: 1.07x flat, 1.07x deep on this run against a 1.07x–1.19x spread
across runs, a tie on the Set container and a near-tie on Map, where it also allocates about 11% less. Nested stays the
unstable measurement — across CI runs of this benchmark it has ranged from 1.04x to 1.46x — so it is reported as a range
rather than a figure.

On dispatch, one half is settled and one is not. `BRIDGE_FN` is the floor: it tracks the zero-dispatch `static forward`
within error on every tier and on every run, because the JIT inlines the monomorphic hop — though on R3 deep it clears
that bar only on a ±5.3 ns band, some twenty times wider than `static forward`'s, so that one cell proves little. The
full-lattice `BRIDGE.read` carries a wrapper tax under a nanosecond wherever it resolves at all — but its size and even
its sign move between runs (R2 read 0.14 → 0.31 → 0.77 ns with depth; R3 read +0.20, −0.31, and an unresolvable +0.30),
so the depth-scaling story is provisional and the magnitude is the only durable part. Either way both proposed
remediations are settled: `BRIDGE_FN` shipped and lands at the floor, and the type-specialized subclass would remove
only that sub-nanosecond tax, swamped several-fold by the body gap on deep — about 7:1 on R2, and on R3 not separable
from zero at all. Declined.

What remains over MapStruct on the deep tier is a few nanoseconds of generated-body work rather than dispatch —
`static forward` alone is above parity — and closing it means matching MapStruct's inlined body. That stays
adopter-gated on a real deep-tier hot loop that measures it.

Two things in this document's history are worth keeping visible. Of the three claims a laptop produced, only the
2.9-3.6x forward gap dissolved on clean CI hardware. The other two survived it: R3 measured the
static-slower-than-lattice inversion on nested with disjoint bands, and telescope does measure faster than MapStruct on
nested backward — 5.355 against 5.983 ns, disjoint — though not on flat or deep backward, which is what makes the
blanket wording fail rather than the claim. And every tier here was List-only until recently, which let a sizing defect
in the container emitter ship green — the container rows exist so that class of defect fails a measurement rather than
passing one.
