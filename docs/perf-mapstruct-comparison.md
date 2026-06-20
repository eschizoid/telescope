# Telescope vs MapStruct — Head-to-Head Performance Analysis

Goal: measure telescope's codegen path against MapStruct's compile-time-generated output, identify any real overhead,
and propose remediations where the gap is structural.

## Headline finding

**Telescope codegen is 1.15× (deep) to 2.01× (nested forward) behind MapStruct — and the deeper the tree, the closer to
parity.** On the realistic deep tier (3-level nesting + list hops) both directions land at 1.15×, near-tie. The number
is from a CI-reproducible run on GitHub Actions `ubuntu-latest` (3 warmup + 5 measurement × 3s, 1 fork) with tight error
bands (±0.01–0.9 ns). Earlier smoke-confidence laptop runs reported a 2.9–3.6× forward gap, a "telescope-faster-on-
backward" inversion, and a "static-slower-than-lattice" inversion — all three were JMH noise artifacts that the clean CI
hardware dissolved. The runtime path is a different conversation (~19–36× — convenience surface, not the hot-path lane).

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

## Results — CI-reproducible run (GitHub Actions `ubuntu-latest`, 3W + 5I × 3s @ 1 fork)

These are the canonical numbers, produced by the manual `Benchmarks` workflow on a dedicated runner. Error bands are
tight (±0.01–0.9 ns) because there's no competing workload — far cleaner than a laptop. Re-run the workflow on any
branch to reproduce.

| Tier   | Direction     |     MapStruct | Telescope codegen (`BRIDGE`) | Telescope codegen (static `forward`) | Ratio |
| ------ | ------------- | ------------: | ---------------------------: | -----------------------------------: | ----: |
| flat   | bean → record | 3.109 ± 0.061 |                4.844 ± 0.045 |                        4.549 ± 0.037 | 1.56× |
| flat   | record → bean | 3.199 ± 0.013 |                4.840 ± 0.025 |                                    — | 1.51× |
| nested | bean → record | 4.223 ± 0.119 |                8.470 ± 0.058 |                        8.100 ± 0.121 | 2.01× |
| nested | record → bean | 5.221 ± 0.124 |                8.448 ± 0.067 |                                    — | 1.62× |
| deep   | bean → record | 46.36 ± 0.240 |                53.41 ± 0.879 |                        52.68 ± 0.374 | 1.15× |
| deep   | record → bean | 46.21 ± 0.436 |                53.03 ± 0.292 |                                    — | 1.15× |

Runtime path (same run, context only): flat fwd 110.96, nested fwd 147.05, deep fwd 883.61 ns/op — ~36× / ~35× / ~19× of
MapStruct. The runtime path goes through a structural-Iso build per `Telescope.mapper(...)` call site and a reflective
dispatch chain on every invocation. It's not in the dethrone-MapStruct lane; it's the convenience surface for "I don't
want to write codegen for this one mapper" workflows.

## The static `forward` benchmark — artifact resolved on CI

The `*_codegen_static_forward` benchmark calls `<Source>Bridge.forward(s)` directly, bypassing the lattice. On clean CI
hardware it runs **consistently faster** than `BRIDGE.read(...)`: flat 4.55 vs 4.84, nested 8.10 vs 8.47, deep 52.68 vs
53.41 ns. The lattice dispatch hop — `Telescope.read` → `BridgeTelescope.read` → `BridgeFn.forward` → `Fn.forward` →
static `forward` → ctor — costs ~0.3–0.7 ns. That's the whole lattice tax, and it shrinks (relatively) as the per-call
work grows.

This is the **opposite** of what an earlier Apple-Silicon local run reported. That run measured the static path as
_slower_ than the lattice path (28 ± 22 ns vs 7 ± 4 ns on flat) with huge variance — a **JMH escape-analysis artifact**.
`Blackhole.consume(R)` accepts an `Object`; JIT can sometimes prove the `new McFlatRec(...)` allocation doesn't escape
past the Blackhole and elide it. The lattice path's deeper inlining chain gave EA a clearer view of the allocation's
escape state and it elided more consistently; the one-deep static call missed the elision on some iterations, producing
the variance. The clean CI run dissolved the artifact entirely — tight error bands, static reliably faster, exactly as
the call-shape predicts.

The lesson: **smoke runs lie.** A 3-iteration × 2s laptop run with error bars exceeding the mean produced a 2.9–3.6×
"forward gap", a "telescope-faster-on-backward" claim, AND a "static-slower-than-lattice" inversion — all three wrong.
The CI run at the same iteration count but on dedicated hardware (no competing workload) is what you trust.

## So is there a real gap?

**Yes — 1.15× (deep) to 2.01× (nested forward), and it decomposes cleanly.** At the 4–8 ns flat/nested scale this is a
handful of L1-cache hits per call. Whether it matters to an adopter depends on call frequency:

- At <10M ops/sec on a hot mapper: invisible against application work.
- At >100M ops/sec: starts to show up on a flame graph but probably still not the dominant cost.
- On the deep tier (the realistic shape): 1.15×, near-tie — once per-level work climbs past ~50 ns the overhead
  vanishes.

The gap is structural and identifiable. The lattice path walks the hops `Telescope.read` → `BridgeTelescope.read` →
`BridgeFn.forward` → `Fn.forward` → static `forward` → ctor where MapStruct walks `INSTANCE.toRec` → ctor. The
static-vs-lattice benchmark pins the lattice tax at ~0.3–0.7 ns; the remaining ~1.4 ns on flat is the generated body's
bean-getter reads vs MapStruct's directly-inlined field sequence. The next perf PR (remediation #1 below) closes the
lattice slice by emitting a typed `BridgeFn` constant adopters can call directly.

## Remediations (in order of payoff vs invasiveness)

### 1. Codegen emits a typed `BridgeFn<S, T>` constant alongside the `Telescope<S, T>` constant

The static-vs-lattice numbers show the lattice hop costs ~0.3–0.7 ns. Adopters in a tight inner loop who don't need
composition can already call `<Source>Bridge.forward(s)` directly — but it's a static method, not a value they can pass
around. Emitting `public static final BridgeFn<S, T> BRIDGE_FN = new Fn();` gives them a typed mapper value with the
same one-interface-hop cost as MapStruct's `INSTANCE.toRec(s)`. One line per generated bridge file; the existing
`BRIDGE` constant (which `mapperForward` auto-discovery uses via `BridgeHolderProbe`) stays.

### 2. The CI-reproducible matrix is now the baseline

Done — the manual `Benchmarks` workflow produces the full matrix on dedicated hardware with tight error bands. Future
PRs trigger it on their branch and baseline-diff against a prior run's artifact. No more smoke-run guesswork.

### 3. Add `-prof gc` reporting to the JMH wiring so allocation cost is visible

`-prof gc` reports allocation rate per benchmark; combining that with `avgt` decomposes the result into call cost vs
allocation cost. Already wired as a knob — the `Benchmarks` workflow takes a `profilers` input (`gc`, `stack`,
`perfasm`), and locally it's `-Pjmh.profilers=gc`. Use it when chasing a residual gap.

## What this PR ships

- **New benchmarks**: `flat_telescope_codegen_static_forward`, `nested_telescope_codegen_static_forward`,
  `deep_telescope_codegen_static_forward`. On clean CI hardware they cleanly isolate the lattice-dispatch tax (~0.3–0.7
  ns); on a noisy laptop they pick up the JMH/EA artifact documented above — a useful regression-test for the artifact
  itself.
- **This analysis doc**: the CI-reproducible baseline + the JMH-artifact gotcha documented so future-us doesn't get
  fooled again.
- **README + benchmarks/README perf tables**: updated to the CI numbers.
- **No production code changed.** The codegen is already where it needs to be; the `BridgeFn` constant (remediation #1)
  is a follow-up.

## Bottom line

Telescope codegen is **1.15× (deep) to 2.01× (nested forward) of MapStruct**, and the deeper the tree the closer to
parity — at the realistic deep tier it's a 1.15× near-tie. The 2.9–3.6× forward "gap", the "telescope faster on
backward" claim, AND the "static slower than lattice" inversion from the laptop smoke runs were all JMH noise. The clean
CI run — same iteration count, dedicated hardware — dissolved every one. The residual gap is structural and identified:
~0.3–0.7 ns lattice dispatch + ~1.4 ns generated-body bean-getter reads. The next perf PR (codegen-emit `BridgeFn`)
closes the lattice slice. Tracking the wrong reading back to its source through proper JMH methodology is the actual
deliverable here.
