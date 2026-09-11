# Telescope vs MapStruct — Head-to-Head Performance Analysis

Goal: measure telescope's codegen path against MapStruct's compile-time-generated output, identify any real overhead,
and propose remediations where the gap is structural.

## Headline finding

**Telescope codegen is in MapStruct's performance class, and allocates what MapStruct allocates on every tier
measured.** Separate runs land on runners of different speeds, so a ratio built from two of them is not a measurement —
hence the two columns below, read as the caption describes.

| Tier (forward, codegen vs codegen)  |        MapStruct |        telescope | ratio | across runs            | allocation        |
| ----------------------------------- | ---------------: | ---------------: | ----: | ---------------------- | ----------------- |
| flat (5 scalars)                    | 3.155 ± 0.019 ns | 3.362 ± 0.011 ns | 1.07x | ~1.07x (one run 1.13x) | 32 B/op both      |
| nested (one nested type)            | 4.361 ± 0.045 ns | 5.604 ± 0.033 ns | 1.29x | 1.04x–1.46x            | 48 B/op both      |
| deep (3 levels + 2 list hops)       |  62.38 ± 0.18 ns |  66.57 ± 0.52 ns | 1.07x | 1.06x–1.19x            | 376 B/op both     |
| container, Map-valued (100 entries) |     1262 ± 11 ns |     1244 ± 10 ns |   tie | measured once          | 7,528 B/op both\* |
| container, Set-valued (100 entries) |     1455 ± 21 ns |      1445 ± 9 ns |   tie | measured once          | 7,576 B/op both   |

GitHub Actions run 34470676359, `ubuntu-latest`, 10 measured iterations. The `ratio` column is this run alone, so every
figure in it is comparable with every other. The `across runs` column is what keeps a single cell from travelling out of
context: only flat holds its value between runs, and the container tiers exist on one run so far.

Read the two container rows carefully — telescope's mean is marginally lower on both, and on neither does that mean it
won. On Set the intervals overlap almost entirely (telescope's sits inside MapStruct's), which is a clean tie. On Map
they overlap by under 3 ns, so call it a tie but not a settled one: tighter bands could separate them either way.

\* The Map row once carried an allocation win — 6,712 against 7,528 bytes per operation — and it was not one. An
interface-typed `Map` field rebuilt as a `HashMap` where MapStruct builds a `LinkedHashMap`, so the two sides were
building different containers and the cheaper one was being read as the better result. Telescope now builds the same
container, its allocation lands on MapStruct's figure exactly, and an ordered source keeps its order across the
conversion. The timings in this row predate that change and need a re-run before they are quoted again; the allocation
figure does not, because allocation is deterministic rather than hardware-dependent.

The scalar tiers are unchanged in character: a 0.2 ns difference on flat, 4 ns on a 62 ns deep conversion, and both
outside their error bars, so each ratio is real within its own run. Only flat is also stable across runs, clustering at
~1.07x. Deep is not: this run's 1.07x sits near the low end of a 1.06x–1.19x spread — the four runs of this benchmark
measured 1.06x, 1.07x, 1.14x and 1.19x — so read it as a range too, and read this run as near its optimistic edge.
Nested is the widest of the three — 1.04x to 1.46x across CI runs of this benchmark (the 1.46x end from Actions run
34148517680; this run is 34470676359), landing at 1.29x here. It is a framework-overhead microbenchmark rather than a
service-shaped workload; treat flat as the publishable figure, and deep and nested as ranges.

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
(1.185× / 1.137×) are stable across the pair. The headline run later measured deep at ~1.07× and run 4 at ~1.06×, so
~1.15× is what these two runs supported and 1.06×–1.19× is the spread across all four — not a figure that has since been
superseded by a single lower one. Nested swings 1.043× → 1.424×: the `nested_mapstruct_forward` baseline carries a wide
±0.35 band both runs, so the nested ratio is a JMH-noisy figure, not a real regression or improvement — don't publish a
single number for it. On the dispatch spread, both runs agree: `BRIDGE_FN` tracks `static forward` within error (run 2
flat: identical at 3.183), while `BRIDGE.read` sits a wrapper tax above the floor that grows with depth — 0.14 → 0.31 →
0.77 ns across flat/nested/deep on the tight-band run, each outside its error band. So `BRIDGE_FN` is the floor; the
lattice wrapper is a small (≤0.8 ns) tax that scales with nesting depth; and on deep the residual over MapStruct sits
below the first dispatch hop (`static forward` alone is ~1.12× on deep, ~5.6 ns of the ~6.3 ns gap). What that residual
_is_ was read at the time as the emitted body doing more work; it is not — see
[So is there a real gap?](#so-is-there-a-real-gap).

**The whole of the following paragraph is superseded** — the runtime tier now lands at ~3.3× / ~2.7× / ~1.3× and
~1.04–1.06× on containers, and backward no longer trails forward. It is kept because the dispatch analysis below was
written against it. As measured on these two runs: forward flat 40.14, nested 64.37, deep 315.23 ns/op — ~16× / ~14× /
~8× of MapStruct — with backward higher still (flat 108.31, nested 148.63, deep 626.39), because building a bean
(allocate + N setters) is structurally heavier than a record's canonical-ctor invoke. At those numbers the runtime path
was the convenience surface for "I don't want to write codegen for this one mapper" rather than a contender; the lattice
sharpenings that closed the gap are recorded in `benchmarks/README.md`.

## Dispatch — `BRIDGE_FN` at the floor on flat and nested, and a sub-nanosecond lattice tax

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
| R3 deep   |                    66.27 |             69.22\* |                   66.57 |     0.30 ns | no (±0.27–0.52)  |
| R4 deep   |                   67.155 |             74.114† |                  67.133 |    −0.02 ns | no (±0.11–0.61)  |

\* R3 deep's `BRIDGE_FN` carries a ±5.3 ns band against `static forward`'s ±0.27 — about twenty times wider — so that
one cell neither confirms nor contradicts the floor claim below.

† R4 deep's `BRIDGE_FN` is the first deep measurement of that cell tight enough to resolve, and it lands ~7 ns **above**
the floor rather than at it, bands disjoint. See [Run 4](#run-4--the-deep-tier-with-allocation-profiling).

R1's bands are 2–27× wider than the others' and every one of its rows reads "no" — too wide to resolve any of these
gaps. R2 resolves all three tiers; R3 resolves flat and nested but not deep. Where both resolve they agree on flat and
disagree on nested, which is itself the finding. Two things hold:

First, **`BRIDGE_FN` tracks the `static forward` floor on flat and nested**: identical on R2 flat (both 3.183), tied on
R2 nested (5.896 vs 5.902), and the same on R3. The one-interface-hop constant is monomorphic (one concrete `Fn` per
bridge) and the JIT inlines it to the raw static call. On R1 and R2 nothing measures below it; R3 nested is the one row
where the lattice value does.

**Deep is the exception, and it took four runs to see it.** No earlier run resolved that cell: R1's ±4.0 and R3's ±5.3
bands are far too wide, and R2's ±0.675 merely overlaps static rather than matching it. R4 is the first deep measurement
tight enough to separate them, and it puts `BRIDGE_FN` ~7 ns above both the static floor and the full lattice, bands
disjoint. One resolving run is not a settled result, but it is the only evidence there is on this tier, and it points
the opposite way to the claim.

Second, **the full-lattice `BRIDGE.read` sits a small wrapper tax above that floor** — on R2, 0.14 ns (flat) → 0.31 ns
(nested) → 0.77 ns (deep), each outside the tight error bands, which read as the lattice composition depth showing
through: more nesting, more `Iso.then(...)` hops the wrapper carries.

**R3 does not reproduce that climb, so treat the depth-scaling as provisional.** On the same clean CI hardware it
measures +0.20 ns on flat (real), −0.31 ns on nested — the lattice value _below_ the static floor, outside the bands,
which is the "static-slower-than-lattice" shape the lesson below separates from the genuine laptop artifacts — and +0.30
ns on deep, inside the bands and therefore not a measurement at all. What survives all three runs is the magnitude: the
tax is under a nanosecond wherever it is resolvable, and on deep it is small against the residual over MapStruct anyway
(see [So is there a real gap?](#so-is-there-a-real-gap)). What does not survive is the monotonic ordering.

An earlier run reported a ~0.3–0.7 ns "lattice slice" and proposed closing it by emitting a directly-callable
`BRIDGE_FN` constant. `BRIDGE_FN` shipped (#182) — and on flat and nested it lands at the `static forward` floor, so an
adopter who wants the fastest passable value has it on every row there but R3 nested. On deep, run 4 puts it ~7 ns above
the floor. The tax that remains sits only on the _composable_ `BRIDGE.read` value and is sub-nanosecond wherever it
resolves at all; the type-specialized subclass (remediation #2) would remove only that, for only the narrow case of
hot-looping the composable value while refusing to switch to `BRIDGE_FN`. Not worth it.

The lesson stands: **smoke runs lie, and one CI run can too.** Run 1's 1.04× nested looked like a headline until run 2
returned 1.42× on the same branch — the nested MapStruct baseline is JMH-noisy (±0.35). Laptop smoke runs earlier
produced a 2.9–3.6× "forward gap" that clean CI hardware dissolved, plus two claims that survived it — see
[Superseded and retracted](#superseded-and-retracted). Trust the numbers that reproduce across runs: flat ~1.07×, deep
1.06×–1.19×, and `BRIDGE_FN` at the floor on flat and nested.

## Run 4 — the deep tier with allocation profiling

The deep residual above was published with a mechanism attached: telescope's emitted body doing more work than
MapStruct's. A line-by-line read of the two generated classes did not support it — the same null guards on the same
paths, the same allocations of the same objects, telescope's per-element call an `invokestatic` against MapStruct's
`invokevirtual`, and telescope's total bytecode the smaller of the two. This run was dispatched to settle the part a
source read cannot: whether the two sides allocate differently at run time.

GitHub Actions run 34561782655, `ubuntu-latest`, main at `f2885c81`, 5 warmup + 10 measured iterations,
`-Pjmh.profilers=gc`. Deep tier only, so it says nothing about flat or nested.

| Deep row (run 4)                 |   time (ns/op) | allocation |
| -------------------------------- | -------------: | ---------: |
| MapStruct forward                | 63.418 ± 0.769 |   376 B/op |
| codegen `static forward` (0 hop) | 67.155 ± 0.612 |   376 B/op |
| codegen `BRIDGE.read` (lattice)  | 67.133 ± 0.110 |   376 B/op |
| codegen `BRIDGE_FN` (1 hop)      | 74.114 ± 0.110 |   376 B/op |
| MapStruct backward               | 70.666 ± 0.220 |   376 B/op |
| codegen `BRIDGE.set` (lattice)   | 67.298 ± 0.101 |   376 B/op |
| runtime forward                  | 81.163 ± 0.251 |   376 B/op |
| runtime backward                 | 85.352 ± 0.151 |   376 B/op |

Three things fall out, and two of them change what this document can claim.

**Allocation is identical — 376 B/op on every row, to the profiler's last digit.** Not merely close: the same figure for
MapStruct, for all three telescope codegen call shapes, and for the runtime path that builds its conversion
reflectively. Allocation is deterministic rather than hardware-dependent, so unlike the timings this one figure is not a
property of the runner. Whatever separates these rows, it is not that one of them allocates more.

**Backward runs the other way on this run.** Telescope 67.298 against MapStruct 70.666, bands disjoint — 0.95×, a win —
while forward on the same run is 1.06× the other way, also disjoint: the same generator, on the same shape, at identical
allocation, trailing by six percent in one direction and leading by five in the other. Run 1 measured the same tier at
1.18× in MapStruct's favour, its bands disjoint too, so deep backward is as run-dependent as deep forward. What is new
is that telescope now leads one of the two.

**`BRIDGE_FN` lands ~7 ns above the floor**, at 74.114 against `static forward`'s 67.155 and `BRIDGE.read`'s 67.133,
disjoint from both. That is the opposite of what the dispatch section concludes; the qualification is recorded there,
next to the claim.

## So is there a real gap?

**A small one on deep forward, and nothing in the emitted code explains it.** The ratios are in the headline table; this
section is the one place that says what the deep residual is and is not, so a correction here does not have to be chased
through the document.

**It is not dispatch.** The zero-dispatch `static forward` floor is itself above MapStruct — 67.155 against 63.418 on
run 4, bands disjoint — so the residual is there before any lattice hop. The wrapper tax cannot account for it either:
it measures −0.02 ns on run 4, inside the bands, and at most 0.77 ns on the one earlier run that resolves deep at all,
against deep gaps of 3.7 to 7.4 ns.

**It is not the emitted body doing more work, which is what this document used to say it was.** The retracted sentence
read "six leaf conversions, two list allocations and per-field null-guards against MapStruct's directly-inlined field
sequence" — a description of telescope doing work MapStruct does not. A line-by-line read of the two generated classes
matched the guards 12 to 12 on the same paths and the allocations 12 to 12 on the same objects, with telescope's
bytecode the smaller of the two; run 4 then measured allocation identical at 376 B/op across every deep row. The
asymmetry the sentence described is not there.

**What is still open.** Deep forward's residual is real within a run and moves between them — 1.06×, 1.07×, 1.14× and
1.19× across the four — and deep backward has been resolved twice in opposite directions, 1.18× on run 1 against 0.95×
on run 4. Code alignment, inlining decisions and profile pollution remain live candidates, and none of them is visible
in source or in bytecode. Separating them needs a `perfasm` run. Until one names a mechanism, no deep-tier emitter
change has a target, and the wrapper is not what an adopter would be paying for either way.

Whether the gap matters at all:

- At <10M ops/sec on a hot mapper: invisible against application work.
- At >100M ops/sec on the deep tier: a few nanoseconds of per-call structural cost — measurable on a flame graph, rarely
  dominant.
- On flat there is no gap to speak of. On nested the MapStruct baseline is JMH-noisy run-to-run, so no single figure is
  trustworthy — read the range in the headline table, not a number.

## Remediations — status

### 1. Codegen emits a typed `BridgeFn<S, T>` constant — **shipped, measured, no perf effect**

`public static final BridgeFn<S, T> BRIDGE_FN = new Fn();` ships per generated bridge (asserted in
`BridgeProcessorTest`). It gives adopters a passable one-hop mapper value instead of a static method. The benchmark
tables above show it measures **at the `static forward` floor on flat and nested** — the JIT inlines the monomorphic hop
to the raw static call, so on those tiers it is the fastest passable value there is. Deep is the exception and the only
run that resolves it disagrees: run 4 puts `BRIDGE_FN` ~7 ns above the floor with disjoint bands, where R1, R2 and R3
all carried bands too wide to separate the two. Against `BRIDGE.read` it is usually the faster of the two by the
sub-nanosecond lattice-wrapper tax, though not always: on R3 nested the lattice value measured below it with disjoint
bands. So it is the ergonomic value (a `BridgeFn` you can pass around) and, on most rows, marginally the fast one.

### 2. Type-specialized bridge subclass whose `read(S)` is the inlined body — **measured and declined**

The idea was to emit a `Telescope`/bridge subclass that removes the `BridgeFn` field and the `invokeinterface` wrapper
so `read(S)` _is_ the generated body. The data shrinks the premise to nothing worth building: the wrapper tax it would
remove is sub-nanosecond wherever it resolves at all, and it applies **only to the composable `BRIDGE.read` value** —
adopters who want the floor already have `BRIDGE_FN`, which sits there. Worse, on R2's deep tier, where the tax was at
its largest, the residual below the floor led it about 7:1; on R3's and R4's deep tiers the tax does not separate from
zero at all. Either way removing it barely moves the ratio. It would add ~100 LOC of `BridgeProcessor` complexity to
shave a sub-nanosecond tax off one of two already-shipped call shapes. **Not building it.** What _would_ move the deep
number is not known — the two generated bodies are equivalent in source and bytecode and identical in allocation, so
there is no emitter change with a target until a `perfasm` run names a mechanism. See
[So is there a real gap?](#so-is-there-a-real-gap).

### 3. The CI-reproducible matrix is the baseline

The manual `Benchmarks` workflow produces the full matrix on dedicated hardware with tight error bands. Future PRs
trigger it on their branch and baseline-diff against a prior run's artifact. `-prof gc` is wired as a `profilers` input
(`gc`, `stack`, `perfasm`; locally `-Pjmh.profilers=gc`) for decomposing call cost vs allocation when chasing a
residual.

## What this revision ships

- **`BRIDGE_FN` benchmarked across all three tiers** (`nested_*_bridgefn_forward`, `deep_*_bridgefn_forward`; flat
  already existed). This is what lets the forward tables compare all four call shapes — static, one-hop, lattice,
  MapStruct — on each run, which is what later let run 4 separate `BRIDGE_FN` from the floor on deep.
- **The container tier**, Set- and Map-valued — see the headline table for the figures and
  [What the container tier is for](#what-the-container-tier-is-for) for why it was added. It was published carrying an
  allocation win on the Map shape; the footnote on that row records what became of it.
- **This analysis doc, corrected against three runs.** What each correction retracted is recorded under
  [Superseded and retracted](#superseded-and-retracted) rather than restated here.
- **No production code changed.** `BRIDGE_FN` already ships; the codegen is at parity as-is.

## Superseded and retracted

Claims and figures an earlier revision of this document got wrong or overstated, kept so a reader who meets one
elsewhere can tell what happened to it. Where a number here is still live, it says so.

**Three claims came out of laptop smoke runs.** Only one dissolved on clean CI hardware: the 2.9–3.6x forward gap. The
other two survived. The static-slower-than-lattice inversion was reproduced on nested with disjoint bands in the
headline run's dispatch table. And telescope codegen does measure faster than MapStruct on nested backward — 5.355
against 5.983 ns, disjoint, on the `benchmarks/README` run — while this document's Run 1 backward table shows the same
direction at 0.99× without resolving it. On that same README run flat and deep backward go the other way, which is what
made the blanket "all wrong" wording fail rather than the claim itself.

**The wrapper tax was once reported as growing monotonically with depth**, at 0.14 → 0.31 → 0.77 ns across flat, nested
and deep. That ordering did not survive the third run, which measured +0.20, −0.31 and an unresolvable +0.30 on the same
tiers. The magnitude — under a nanosecond wherever it resolves — is what reproduces; the ordering is not, and the
dispatch table above carries both runs so the disagreement stays visible.

**An earlier revision proposed closing a ~0.3–0.7 ns "lattice slice"** by emitting a directly-callable constant, and a
first fresh run then over-corrected the other way to "dispatch is free everywhere". `BRIDGE_FN` shipped and lands at the
floor on flat and nested, so that half is done; the Remediations section above records why the second proposal was
declined.

**The deep residual was attributed to the generated body**, as "six leaf conversions, two list allocations and per-field
null-guards against MapStruct's directly-inlined field sequence". A line-by-line read of the two generated classes found
the guards and the allocations matched 12 to 12 on the same paths and objects, with telescope's bytecode the smaller of
the two, and run 4 measured allocation identical at 376 B/op on every deep row. The residual over MapStruct on deep
forward is still measured; the mechanism is retracted, and [So is there a real gap?](#so-is-there-a-real-gap) is the one
place that now says so.

**`BRIDGE_FN` was published as the floor on every tier.** It is, on flat and nested. On deep no run resolved that cell
until run 4, which measured it ~7 ns _above_ the floor with disjoint bands — so the claim is now scoped to the tiers
that support it.

**Deep was briefly published as ~1.07× flat-out.** The number is this run's measurement and still stands in the headline
table; what was wrong was presenting it as deep's figure rather than as one end of a spread, now 1.06×–1.19× with run
4's 1.059× at the bottom. The `across runs` column is the honest form.

## Bottom line

Telescope codegen is in MapStruct's performance class. The headline table has the figures and says which of them are
stable across runs and which are ranges; the short version is that flat is settled, deep and nested are ranges, and the
two container shapes tie on time and allocate identically.

On dispatch, one half is settled on two tiers of three. `BRIDGE_FN` is the floor on flat and nested — it tracks the
zero-dispatch static call there on every run within error, because the JIT inlines the monomorphic hop. On deep the only
run whose bands resolve it puts it ~7 ns above the floor instead. The full-lattice `BRIDGE.read` carries a
sub-nanosecond wrapper tax wherever it resolves at all, but its size and even its sign move between runs, so how it
scales with depth is provisional and only the magnitude is durable. Both proposed remediations are settled either way:
one shipped and reached the floor on flat and nested, and the other would remove only that tax, which is small against
the deep residual — the one tier where the residual is big enough to be worth chasing.

What remains over MapStruct on deep forward is not dispatch — the zero-dispatch floor is itself above parity — and it is
the emitted bodies are not doing more work either: the two are equivalent in source and bytecode and allocate
identically, and deep backward has been resolved in both directions across runs. No emitter change has a target until a
`perfasm` run names a mechanism.
