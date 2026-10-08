# Telescope vs MapStruct — Head-to-Head Performance Analysis

Goal: measure telescope's codegen path against MapStruct's compile-time-generated output, identify any real overhead,
and propose remediations where the gap is structural.

## Headline finding

**Telescope codegen is in MapStruct's performance class, and allocates what MapStruct allocates on every tier
measured.** Separate runs land on runners of different speeds, so a ratio built from two of them is not a measurement —
hence the two columns below, read as the caption describes.

The latest full run, with four forks on every row, is [Run 8](#run-8-2026-10-08-every-tier-four-forks). The README's
figures come from it.

| Tier (forward, codegen vs codegen)  |        MapStruct |        telescope | ratio | across runs            | allocation        |
| ----------------------------------- | ---------------: | ---------------: | ----: | ---------------------- | ----------------- |
| flat (5 scalars)                    | 3.155 ± 0.019 ns | 3.362 ± 0.011 ns | 1.07x | ~1.07x (one run 1.13x) | 32 B/op both      |
| nested (one nested type)            | 4.361 ± 0.045 ns | 5.604 ± 0.033 ns | 1.29x | 1.04x–1.46x            | 48 B/op both      |
| deep (3 levels + 2 list hops)       |  62.38 ± 0.18 ns |  66.57 ± 0.52 ns | 1.07x | 1.06x–1.18x            | 376 B/op both     |
| container, Map-valued (100 entries) |     1262 ± 11 ns |     1244 ± 10 ns |   tie | measured once          | 7,528 B/op both\* |
| container, Set-valued (100 entries) |     1455 ± 21 ns |      1445 ± 9 ns |   tie | measured once          | 7,576 B/op both   |

GitHub Actions run 34470676359, `ubuntu-latest`, 10 measured iterations. The `ratio` column is this run alone, so every
figure in it is comparable with every other. The `across runs` column is what keeps a single cell from travelling out of
context: only flat holds its value between runs, and the container tiers exist on one run so far.

Read the two container rows carefully — telescope's mean is marginally lower on both, and on neither does that mean it
won. On Set the intervals overlap almost entirely (telescope's sits inside MapStruct's), which is a clean tie. On Map
they overlap by under 3 ns, so call it a tie but not a settled one: tighter bands could separate them either way. Both
container rows' timings predate the Map-container change described in the footnote and are pending a re-run.

\* The Map row once carried an allocation win — 6,712 against 7,528 bytes per operation — and it was not one. An
interface-typed `Map` field rebuilt as a `HashMap` where MapStruct builds a `LinkedHashMap`, so the two sides were
building different containers and the cheaper one was being read as the better result. Telescope now builds the same
container and an ordered source keeps its order across the conversion. The 7,528 figure for telescope is measured, but
by a later run than the timings beside it — Actions 34611507811, which ran this filter against the changed code and
reported both sides at 7,528 B/op. **The timings in this row predate the change and should not be quoted until a re-run
replaces them**, which applies to the tie reading above and to the container line in `README.md` as much as to the table
cell.

The scalar tiers are unchanged in character: a 0.2 ns difference on flat, 4 ns on a 62 ns deep conversion, and both
outside their error bars, so each ratio is real within its own run. Only flat is also stable across runs, clustering at
~1.07x. Deep is not: this run's 1.07x sits near the low end of a 1.06x–1.18x spread — the four tabulated runs measured
1.06x, 1.07x, 1.14x and 1.18x — so read it as a range too, and read this run as near its optimistic edge. Nested is the
widest of the three — 1.04x to 1.46x across CI runs of this benchmark (the 1.46x end from Actions run 34148517680; this
run is 34470676359), landing at 1.29x here. It is a framework-overhead microbenchmark rather than a service-shaped
workload; treat flat as the publishable figure, and deep and nested as ranges.

Runtime tier, same run: flat 3.34x, nested 2.66x, deep 1.27x, and 1.04x–1.06x on the two container shapes — the Map
shape there predates the same change, since it moved the runtime allocator too. MapStruct has no equivalent to this tier
— it is what you get with no annotations and no build step — so these ratios say what the convenience costs, not who
wins.

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
| deep   | 40.29 ± 0.801 |                   47.72 ± 1.503 |               48.41 ± 4.023 |                    47.40 ± 3.153 |            1.184× |

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
(1.184× / 1.137×) are stable across the pair. The headline run later measured deep at ~1.07× and run 4 at ~1.06×, so
~1.15× is what these two runs supported and 1.06×–1.18× is the spread across the four tabulated runs — not a figure that
has since been superseded by a single lower one. Nested swings 1.043× → 1.424×: the `nested_mapstruct_forward` baseline
carries a wide ±0.35 band both runs, so the nested ratio is a JMH-noisy figure, not a real regression or improvement —
don't publish a single number for it. On the dispatch spread, both runs agree: `BRIDGE_FN` tracks `static forward`
within error (run 2 flat: identical at 3.183), while `BRIDGE.read` sits a wrapper tax above the floor that grows with
depth — 0.14 → 0.31 → 0.77 ns across flat/nested/deep on the tight-band run, each outside its error band. So `BRIDGE_FN`
is the floor on these two runs — a reading run 4 appeared to reverse on deep, see the dispatch section; the lattice
wrapper is a small (≤0.8 ns) tax that scales with nesting depth; and on deep the residual over MapStruct sits below the
first dispatch hop (`static forward` alone is ~1.12× on deep, ~5.6 ns of the ~6.3 ns gap). What that residual _is_ was
read at the time as the emitted body doing more work; it is not — see
[So is there a real gap?](#so-is-there-a-real-gap).

**The whole of the following paragraph is superseded** — the runtime tier now lands at ~3.3× / ~2.7× / ~1.3× and
~1.04–1.06× on containers, and backward no longer trails forward. It is kept because the dispatch analysis below was
written against it. As measured on these two runs: forward flat 40.14, nested 64.37, deep 315.23 ns/op — ~16× / ~14× /
~8× of MapStruct — with backward higher still (flat 108.31, nested 148.63, deep 626.39), because building a bean
(allocate + N setters) is structurally heavier than a record's canonical-ctor invoke. At those numbers the runtime path
was the convenience surface for "I don't want to write codegen for this one mapper" rather than a contender; the lattice
sharpenings that closed the gap are recorded in `benchmarks/README.md`.

## Dispatch — `BRIDGE_FN` at or near the floor on every tier, and a sub-nanosecond lattice tax

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
| R5 flat   |                    2.390 |               2.385 |                   2.687 |     0.30 ns | yes (±0.01–0.02) |
| R5 nested |                    4.279 |               4.279 |                   4.409 |     0.13 ns | yes (±0.01)      |
| R5 deep   |                   53.047 |              52.766 |                  54.096 |     1.05 ns | no (±0.51–1.36)  |
| R6 deep   |                   66.490 |              67.530 |                  67.530 |     1.04 ns | no (±0.70–1.47)  |
| R7 deep   |                   38.231 |              39.338 |                  37.964 |    −0.27 ns | no (±0.56–0.60)  |

\* R3 deep's `BRIDGE_FN` carries a ±5.3 ns band against `static forward`'s ±0.27 — about twenty times wider. R3 ran two
forks, and that row's forks averaged 65.89 and 72.54 ns: one at the floor and one in the slower compiled shape
[Run 5](#run-5--deep-forward-across-forks) describes.

† R4 ran a single fork, and that fork compiled the benchmark into a slower shape that any of the three deep rows can
land in. Run 5 shows the same thing happening to `static forward` and `BRIDGE.read`; see
[Run 5](#run-5--deep-forward-across-forks).

R1, R2 and R4 ran one fork each, R3 two, R5 three, and R6 and R7 eight. R1's bands are 2–27× wider than the others' and
every one of its rows reads "no" — too wide to resolve any of these gaps. R2 resolves all three tiers; R3 and R5 resolve
flat and nested but not deep. Three things hold:

First, **`BRIDGE_FN` tracks the `static forward` floor on flat and nested, and stays within about a nanosecond of it on
deep**. It is identical on R2 flat (both 3.183) and R5 nested (both 4.279), tied on R2 and R3 nested, and within error
on R5 flat. The one-interface-hop constant is monomorphic (one concrete `Fn` per bridge) and the JIT inlines it to the
raw static call; the inlining log in Run 5 shows that hop inlined in every fork, deep included. On deep, the three
multi-fork runs put `BRIDGE_FN` 0.28 ns below the floor (R5) and 1.0–1.1 ns above it (R6, R7), and on none of them do
the two bands separate. R4's 74.114 is the one deep cell that does separate, and it was a single fork in a compiled
shape that is not specific to `BRIDGE_FN`: R6 caught `BRIDGE.read` at 74.26 ns and `static forward` at 69.71 ns in that
same shape. R3 nested is the one row where the lattice value measures below `BRIDGE_FN` with disjoint bands.

Second, **the full-lattice `BRIDGE.read` sits a small wrapper tax above that floor** — on R2, 0.14 ns (flat) → 0.31 ns
(nested) → 0.77 ns (deep), each outside the tight error bands, which read as the lattice composition depth showing
through: more nesting, more `Iso.then(...)` hops the wrapper carries.

**R3 and R5 do not reproduce that climb, so treat the depth-scaling as provisional.** R3 measures +0.20 ns on flat
(real), −0.31 ns on nested — the lattice value _below_ the static floor, outside the bands, which is the
"static-slower-than-lattice" shape the lesson below separates from the genuine laptop artifacts — and +0.30 ns on deep,
inside the bands and therefore not a measurement at all. R5 measures +0.30 ns on flat and +0.13 ns on nested, both
outside the bands, so its flat tax is larger than its nested one. On deep, R5, R6 and R7 all land inside the bands. What
survives every run is the magnitude: the tax is under a nanosecond wherever it is resolvable, and on deep it is small
against the residual over MapStruct anyway (see [So is there a real gap?](#so-is-there-a-real-gap)). What does not
survive is the monotonic ordering.

An earlier run reported a ~0.3–0.7 ns "lattice slice" and proposed closing it by emitting a directly-callable
`BRIDGE_FN` constant. `BRIDGE_FN` shipped (#182) and lands at the `static forward` floor on flat and nested and within
about a nanosecond of it on deep, so an adopter who wants a fast passable value has it on every tier. The tax that
remains sits only on the _composable_ `BRIDGE.read` value and is sub-nanosecond wherever it resolves at all; the
type-specialized subclass (remediation #2) would remove only that, for only the narrow case of hot-looping the
composable value while refusing to switch to `BRIDGE_FN`. Not worth it.

The lesson stands: **smoke runs lie, and one CI run can too, and so can one fork.** Run 1's 1.04× nested looked like a
headline until run 2 returned 1.42× on the same branch — the nested MapStruct baseline is JMH-noisy (±0.35). Laptop
smoke runs earlier produced a 2.9–3.6× "forward gap" that clean CI hardware dissolved, plus two claims that survived it
— see [Superseded and retracted](#superseded-and-retracted). Run 4's `BRIDGE_FN` cell was a single fork that compiled
into a slower shape; its band was tight because the iterations within one fork agree with each other, not because the
fork was representative. Trust the numbers that reproduce across runs and across forks: flat ~1.07×, deep 1.06×–1.18×,
and `BRIDGE_FN` at the floor on flat and nested and within about a nanosecond of it on deep.

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

**Allocation is identical — 376 B/op on every row.** Not merely close: the same figure for MapStruct, for all three
telescope codegen call shapes, and for the runtime path that builds its conversion reflectively. Allocation is
deterministic rather than hardware-dependent, so unlike the timings this one figure is not a property of the runner.
Whatever separates these rows, it is not that one of them allocates more.

**Backward runs the other way on this run.** Telescope 67.298 against MapStruct 70.666, bands disjoint — 0.95×, a win —
while forward on the same run is 1.06× the other way, also disjoint: the same generator, on the same shape, at identical
allocation, trailing by six percent in one direction and leading by five in the other. Run 1 measured the same tier at
1.18× in MapStruct's favour, its bands disjoint too, so deep backward is as run-dependent as deep forward. What is new
is that telescope now leads one of the two.

**`BRIDGE_FN` lands ~7 ns above the floor**, at 74.114 against `static forward`'s 67.155 and `BRIDGE.read`'s 67.133,
disjoint from both. This run used a single fork, and [Run 5](#run-5--deep-forward-across-forks) shows that one fork can
compile any of the three deep rows into a shape that runs 3.5 to 8 ns slower. Across forks, `BRIDGE_FN` stays within
about a nanosecond of the floor.

## Run 5 — deep forward across forks

Run 4's `BRIDGE_FN` cell was dispatched again to find out whether it reproduces and, if it does, why. Three runs carry
the timings, all on GitHub Actions `ubuntu-latest` with `-Pjmh.profilers=gc` and 5 warmup iterations of 2 s:

- **R5** — Actions run 37753841536, main at `a94ce9a3`, the forward rows of all three tiers, 3 forks × 8 measured
  iterations of 2 s.
- **R6** — Actions run 37756968239, the four deep forward rows only, 8 forks × 5 iterations of 2 s.
- **R7** — Actions run 37755457582, the same as R6.

R6 and R7 ran from a branch whose benchmark sources and generated code are main's; it adds only the workflow's
`jvm_args` input. R7 landed on a much faster runner — its MapStruct control row reads 38.2 ns against R6's 62.2 — so
compare its rows with each other, never with R6's.

R6's per-fork means show what a single pooled figure hides:

| Deep row (R6)           | per-fork means, ns/op                                   |   pooled mean | allocation |
| ----------------------- | ------------------------------------------------------- | ------------: | ---------: |
| MapStruct forward       | 62.36 62.27 61.97 63.11 62.05 61.89 61.85 61.77         | 62.160 ± 0.28 |   376 B/op |
| `static forward`        | 66.16 66.03 66.04 66.09 66.07 **69.71** 65.85 65.97     | 66.490 ± 0.70 |   376 B/op |
| `BRIDGE_FN`             | **72.50** 66.24 66.06 66.53 66.88 66.06 **69.91** 66.05 | 67.530 ± 1.29 |   376 B/op |
| `BRIDGE.read` (lattice) | 66.54 66.18 66.48 **74.26** 66.44 66.91 66.80 66.64     | 67.530 ± 1.47 |   376 B/op |

**The forks are bimodal, and the slow mode is not specific to `BRIDGE_FN`.** Most forks of all three telescope rows land
between 65.8 and 66.9 ns. A few land 3.5 to 8 ns higher, and that happened to `static forward` and `BRIDGE.read` as well
as to `BRIDGE_FN`; the slowest fork in the run is a `BRIDGE.read` fork at 74.26, the same figure run 4 recorded for
`BRIDGE_FN`. Run 4 ran a single fork, so its cell was one draw from this distribution, and its ±0.110 band measured how
well the iterations of that one fork agree with each other.

**The slow mode is a race between C2 compiles, not the interface hop.** Actions run 37758385903 repeated the
`static forward` and `BRIDGE_FN` rows with 10 forks each and
`jvm_args: -XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation -XX:+PrintInlining`. Three forks landed slow —
`BRIDGE_FN` fork 10 at 72.29 ns, `static forward` fork 1 at 71.12 and fork 7 at 70.92 — and those three are the only
forks in the run whose final compile of JMH's measurement loop refuses to inline the `@Benchmark` method, with
`failed to inline: already compiled into a big method`. In those forks C2 had already compiled the benchmark method on
its own, into code larger than `InlineSmallCode`, so the loop calls it out of line. In every other fork the loop inlines
the benchmark method and the conversion down to the bridge's `forward`, and calls the separately compiled
`__fwd_departments` list helper. Which compile finishes first depends on background-compilation timing, so it varies
from fork to fork while the code stays the same. In all ten `BRIDGE_FN` forks, slow and fast, both `Fn.forward` frames
are inlined (`inline (hot)`): the constant is devirtualized and inlined on deep exactly as on the shallower tiers.

A control run supports the reading. With `jvm_args: -XX:InlineSmallCode=6000` and otherwise the same configuration as R6
(Actions run 37755467862), no `static forward` or `BRIDGE_FN` fork measured above 67.4 ns, and the two rows agree at
66.958 ± 0.142 and 66.796 ± 0.227, against MapStruct at 64.703 ± 0.492. That flag changes every compile in the JVM, so
it is a diagnostic here, not a recommendation.

Nothing in flat or nested shows the same split: R5's per-fork means agree within 0.03 ns on every flat and nested
codegen row.

Three things follow:

- **`BRIDGE_FN` is not measurably slower than the static call on deep.** Across R5, R6 and R7 it measures 0.28 ns below
  the floor once and 1.0–1.1 ns above it twice, with overlapping bands each time. Reaching for it in a tight loop is as
  sound on deep as on flat and nested.
- **A deep figure from a single fork can be 3.5–8 ns high on any codegen row.** Deep figures worth publishing come from
  runs with several forks, read per fork before they are pooled.
- **This is not the residual over MapStruct.** On R6 every MapStruct fork sits between 61.77 and 63.11 ns, and the fast
  forks of the three telescope rows still sit about 4 ns above that. See
  [So is there a real gap?](#so-is-there-a-real-gap).

## Run 8, 2026-10-08: every tier, four forks

GitHub Actions run 37761344960 measured every row of `MapStructComparisonBenchmark` on `main` at `f7be3f30`. It used
`ubuntu-latest`, 4 forks, 4 warmup and 8 measured iterations of 2 s each, and `-Pjmh.profilers=gc`. It is the first full
run after same-typed containers began to be copied rather than shared. The README's figures come from it.

MapStruct's rows are the control, and every ratio below is read within this run. A range replaces a single ratio where
the two rows' error bands overlap.

| Row, codegen against MapStruct | MapStruct (ns/op) | telescope `BRIDGE.read` (ns/op) | ratio        | allocation, B/op       |
| ------------------------------ | ----------------: | ------------------------------: | ------------ | ---------------------- |
| flat forward                   |     3.135 ± 0.011 |                   3.359 ± 0.019 | 1.07         | 32 both                |
| flat backward                  |     3.226 ± 0.010 |                   3.355 ± 0.013 | 1.04         | 32 both                |
| nested forward                 |     4.321 ± 0.024 |                   5.580 ± 0.012 | 1.29         | 48 both                |
| nested backward                |     5.288 ± 0.023 |                   5.414 ± 0.015 | 1.02         | 48 both                |
| deep forward                   |    62.215 ± 0.176 |                  68.245 ± 2.062 | 1.10         | 376 both               |
| deep backward                  |    65.792 ± 0.421 |                  69.136 ± 3.019 | 1.00 to 1.10 | 376 both               |
| Map, 100 entries, forward      |     1340.6 ± 76.8 |                   1523.4 ± 56.8 | 1.14         | 7,528 both             |
| Map, 100 entries, backward     |      1307.6 ± 8.8 |                    1437.4 ± 4.6 | 1.10         | 7,528 both             |
| Set, 100 entries, forward      |     1493.8 ± 55.8 |                    1450.2 ± 5.7 | 0.93 to 1.01 | 7,568 and 7,576, below |
| Set, 100 entries, backward     |     3114.5 ± 12.0 |                   3189.6 ± 22.9 | 1.02         | 7,544 both             |

The other codegen call shapes on the same run, in ns/op: `static forward` measured 3.128 on flat, 5.911 on nested and
66.222 on deep. `BRIDGE_FN` measured 3.131, 5.907 and 68.634. Every one of them allocated what MapStruct allocated on
its tier.

The runtime rows, as ratios to MapStruct on the same run:

| Row                       | runtime (ns/op) | ratio | allocation, B/op                 |
| ------------------------- | --------------: | ----: | -------------------------------- |
| flat forward              |  10.470 ± 0.061 |  3.34 | 32, same as MapStruct            |
| flat backward             |   9.217 ± 0.029 |  2.86 | 32, same as MapStruct            |
| nested forward            |  11.567 ± 0.022 |  2.68 | 48, same as MapStruct            |
| nested backward           |  10.646 ± 0.127 |  2.01 | 48, same as MapStruct            |
| deep forward              |  78.824 ± 0.202 |  1.27 | 376, same as MapStruct           |
| deep backward             |  81.799 ± 0.193 |  1.24 | 376, same as MapStruct           |
| Map, 100 entries, forward |    1499.9 ± 3.9 |  1.12 | 7,560, 32 more than MapStruct    |
| Set, 100 entries, forward |    1536.6 ± 3.7 |  1.03 | 7,576, same as telescope codegen |

### Reading the run per fork

Four rows are bimodal across forks, and the pooled figure hides it.

- **Deep forward.** Three telescope forks measured 66.3 to 66.5 ns and one measured 73.7, the slower compiled shape
  [Run 5](#run-5--deep-forward-across-forks) describes. MapStruct's four forks measured 62.0 to 62.4. The three fast
  forks put deep forward at about 1.07 times, and the pooled figure is 1.10. Deep backward has the same split, with one
  telescope fork at 77.1 ns.
- **Deep forward through `BRIDGE_FN`.** Its forks measured 72.4, 65.9, 70.2 and 66.1 ns. The two fast forks sit with
  `static forward`, which measured 66.1 to 66.3 in every fork, and the pooled figure is 68.634.
- **Map forward.** MapStruct's forks measured 1544, 1268, 1271 and 1279 ns. Telescope's measured 1435, 1614, 1438
  and 1606. Comparing the fast forks of each side gives about 1.13 times, close to the pooled 1.14. Map backward has no
  split, and it measured 1.10 times.
- **Set forward.** MapStruct's forks measured 1443, 1642, 1448 and 1442 ns, and telescope's all measured 1447 to 1455.
  The one slow MapStruct fork is what pulls the pooled ratio below one. Comparing the fast forks gives about 1.00 times.

Set forward is also the one row where allocation differs. Telescope allocated 7,576 B/op in every fork. MapStruct
allocated 7,576 in three forks and 7,544 in the fourth, which is the same fork that ran slow, so its pooled figure is
7,568. Telescope never allocated more than MapStruct's usual figure on this row.

### The Map row across five runs

The headline run, Actions 34470676359, measured the Map row as a tie at 1262 against 1244 ns. Run 8 measured it at 1.14
times forward and 1.10 times backward, with disjoint bands. Four more runs, each with 4 forks, then measured only the
`container_map.*` rows, to compare `main` with the v1.9.0 release.

| Actions run | ref    | codegen forward | codegen backward | runtime forward | allocation, codegen and MapStruct |
| ----------- | ------ | --------------: | ---------------: | --------------: | --------------------------------- |
| 37761344960 | main   |            1.14 |             1.10 |            1.12 | 7,528 B/op                        |
| 37789674703 | main   |            1.02 |             0.97 |            1.05 | 7,528 B/op                        |
| 37789690617 | main   |            1.07 |             0.98 |            1.06 | 7,528 B/op                        |
| 37789681271 | v1.9.0 |            1.04 |             1.10 |            1.11 | 7,528 B/op                        |
| 37789695822 | v1.9.0 |            1.03 |             0.95 |            1.05 | 7,528 B/op                        |

Each ratio is telescope's time over MapStruct's time on the same run. The runs on `main` used `f7be3f30` for Run 8 and
`d5f7c0b8` for the other two, and the runs on the release used its tag commit `6886f19d`. The release's forward and
runtime ratios fall inside `main`'s spread, and its backward ratios (0.95 and 1.10) bracket `main`'s 0.97 to 1.10. These
five runs show no regression between v1.9.0 and `main`. Across them, the generated mapper and MapStruct both allocated
7,528 B/op on every run. The runtime mapper allocated 7,560 B/op on every run.

The old tie predates the change that rebuilds an interface-typed `Map` field as a `LinkedHashMap` sized with
`LinkedHashMap.newLinkedHashMap(size)`. That change makes telescope build the same container class as MapStruct, which
the footnote under the headline table describes.

## So is there a real gap?

**A small one on deep forward, and nothing in the emitted code explains it.** The ratios are in the headline table; this
section is where the deep residual is characterised; the Bottom line and Remediation 2 summarise it and should move with
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
1.18× across the four — and deep backward has been resolved twice in opposite directions, 1.18× on run 1 against 0.95×
on run 4. Part of that movement is per fork: the runs before run 5 used one or two forks, and
[Run 5](#run-5--deep-forward-across-forks) shows a single fork can land a telescope row in a compiled shape that runs
3.5 to 8 ns slower. That does not account for the residual itself, because on R6 the fast forks of all three telescope
rows still sit about 4 ns above every MapStruct fork. Code alignment, inlining decisions and profile pollution remain
live candidates, and none of them is visible in source or in bytecode. Separating them needs a `perfasm` run. Until one
names a mechanism, no deep-tier emitter change has a target, and the wrapper is not what an adopter would be paying for
either way.

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
tables above show it measures **at the `static forward` floor on flat and nested, and within about a nanosecond of it on
deep** — the JIT inlines the monomorphic hop to the raw static call, and the inlining log in
[Run 5](#run-5--deep-forward-across-forks) shows it doing so on deep in every fork. Run 4's reading of ~7 ns above the
floor on deep was a single fork in a slower compiled shape that `static forward` and `BRIDGE.read` land in too. So it is
as sound a choice for a tight inner loop on deep as on the shallower tiers, and calling the static `forward` directly
buys nothing that the multi-fork runs can resolve. Against `BRIDGE.read` it is usually the faster of the two by the
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
number is not known — the two generated bodies do the same work, with the same guards on the same paths and the same
allocations, so there is no emitter change with a target until a `perfasm` run names a mechanism. See
[So is there a real gap?](#so-is-there-a-real-gap).

### 3. The CI-reproducible matrix is the baseline

The manual `Benchmarks` workflow produces the full matrix on dedicated hardware with tight error bands. Future PRs
trigger it on their branch and baseline-diff against a prior run's artifact. `-prof gc` is wired as a `profilers` input
(`gc`, `stack`, `perfasm`; locally `-Pjmh.profilers=gc`) for decomposing call cost vs allocation when chasing a
residual. A `jvm_args` input (locally `-Pjmh.jvmArgsAppend=...`) passes flags to every forked benchmark JVM, such as
`-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining` to read the inlining decisions behind a figure on the runner that
measured it. Use several forks for any deep-tier figure: a single fork can land in a slower compiled shape and still
report a tight band.

## What this revision ships

- **`BRIDGE_FN` benchmarked across all three tiers** (`nested_*_bridgefn_forward`, `deep_*_bridgefn_forward`; flat
  already existed). This is what lets the forward tables compare all four call shapes — static, one-hop, lattice,
  MapStruct — on each run, which is what let run 4 and run 5 between them show that a single deep fork can land any of
  the three codegen shapes in a slower compiled shape.
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
floor on flat and nested and within about a nanosecond of it on deep, so that half is done; the Remediations section
above records why the second proposal was declined.

**The deep residual was attributed to the generated body**, as "six leaf conversions, two list allocations and per-field
null-guards against MapStruct's directly-inlined field sequence". A line-by-line read of the two generated classes found
the guards and the allocations matched 12 to 12 on the same paths and objects, with telescope's bytecode the smaller of
the two, and run 4 measured allocation identical at 376 B/op on every deep row. The residual over MapStruct on deep
forward is still measured; the mechanism is retracted, and [So is there a real gap?](#so-is-there-a-real-gap) is the one
place that now says so.

**`BRIDGE_FN` was published as the floor on every tier**, and then, after run 4 measured it ~7 ns _above_ the floor on
deep with disjoint bands, as the floor on flat and nested only. Neither form holds. Run 4 was a single fork that
compiled into a slower shape any of the three deep codegen rows can land in; across forks, `BRIDGE_FN` is at the floor
on flat and nested and within about a nanosecond of it on deep, with the bands overlapping on every multi-fork run. See
[Run 5](#run-5--deep-forward-across-forks).

**Deep was briefly published as ~1.07× flat-out.** The number is this run's measurement and still stands in the headline
table; what was wrong was presenting it as deep's figure rather than as one end of a spread, now 1.06×–1.18× with run
4's 1.059× at the bottom. The `across runs` column is the honest form.

## Bottom line

Telescope codegen is in MapStruct's performance class. The headline table has the figures and says which of them are
stable across runs and which are ranges; the short version is that flat is settled, deep and nested are ranges, and the
two container shapes allocate identically, with their timings pending a re-run.

On dispatch, one half is settled. `BRIDGE_FN` is the floor on flat and nested and within about a nanosecond of it on
deep, because the JIT inlines the monomorphic hop on every tier; the ~7 ns run 4 measured on deep was a single fork in a
slower compiled shape that the static call and the lattice value land in too. The full-lattice `BRIDGE.read` carries a
sub-nanosecond wrapper tax wherever it resolves at all, but its size and even its sign move between runs, so how it
scales with depth is provisional and only the magnitude is durable. Both proposed remediations are settled either way:
one shipped and reached the floor, and the other would remove only that tax, which is small against the deep residual.

What remains over MapStruct on deep forward is not dispatch: the zero-dispatch floor is itself above parity. Nor are the
emitted bodies doing more work — they carry the same guards on the same paths and allocate the same objects, and run 4
measured their allocation identical. Deep backward has been resolved in both directions across runs. So the residual is
real and unexplained, and no emitter change has a target until a `perfasm` run names a mechanism.
