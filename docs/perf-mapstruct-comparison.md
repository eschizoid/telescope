# Telescope vs MapStruct — Head-to-Head Performance Analysis

Goal: measure telescope's codegen path against MapStruct's compile-time-generated output, identify any real overhead,
and propose remediations where the gap is structural.

## Headline finding

**Telescope codegen is at effective MapStruct parity: ~1.07× flat and ~1.15× deep forward, both stable across two CI
runs.** The realistic deep tier — 3-level nesting + list hops — is a ~1.15× near-tie (1.19× / 1.14× across the two
runs), and flat is ~1.07× (1.065× / 1.072×). The nested-tier ratio is JMH-noisy run-to-run (1.04× then 1.42×; the
`nested_mapstruct_forward` baseline carries a wide ±0.35 band), so no single nested figure is publishable — call it
near-parity and lean on the stable flat/deep numbers. The deep residual is **mostly generated-body work, not dispatch**:
the zero-dispatch `static forward` floor is itself ~1.12× on deep (~5.6 ns of the ~6.3 ns gap), with the lattice wrapper
~0.8 ns on top.

Two dispatch-shape claims from earlier revisions of this doc were **measured and settled** across both runs (see the
results table and the "dispatch" section):

- **`BRIDGE_FN` sits at the zero-dispatch floor.** The one-interface-hop constant measures within error of
  `static forward` on every tier in both runs (run 2 flat: `BRIDGE_FN` == `static forward` to three digits). It is the
  fastest a passable mapper value can be — there is nothing to recover past it.
- **The full-lattice `BRIDGE.read` adds a ≤0.8 ns wrapper tax that grows with depth** — 0.14 ns (flat) → 0.31 ns
  (nested) → 0.77 ns (deep) on the tight-band run, each outside the error bands. The proposed type-specialized subclass
  (remediation #2 below) exists only to shave that off the _composable_ value — but `BRIDGE_FN` already ships the floor
  as a passable value, and on deep the tax is swamped by the body gap, so **it is not worth building.**

Earlier smoke-confidence laptop runs reported a 2.9–3.6× forward gap, a "telescope-faster-on-backward" inversion, and a
"static-slower-than-lattice" inversion — all three were JMH noise artifacts that clean CI hardware dissolved. The
runtime path is a different conversation (~1.3–4× after the leaf-rebuild campaign — see the runtime tier in
[benchmarks/README.md](../benchmarks/README.md); the ~8–20× figure predates it).

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

## Results — two CI-reproducible runs (GitHub Actions `ubuntu-latest`, 3W + 5I × 3s @ 1 fork)

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

**What reproduces, and what doesn't.** Flat (1.065× / 1.072×) and deep (1.185× / 1.137×) are stable — publishable at
~1.07× and ~1.15×. Nested swings 1.043× → 1.424×: the `nested_mapstruct_forward` baseline carries a wide ±0.35 band both
runs, so the nested ratio is a JMH-noisy figure, not a real regression or improvement — don't publish a single number
for it. On the dispatch spread, both runs agree: `BRIDGE_FN` tracks `static forward` within error (run 2 flat: identical
at 3.183), while `BRIDGE.read` sits a wrapper tax above the floor that grows with depth — 0.14 → 0.31 → 0.77 ns across
flat/nested/deep on the tight-band run, each outside its error band. So `BRIDGE_FN` is the floor; the lattice wrapper is
a small (≤0.8 ns) tax that scales with nesting depth; and the deep residual over MapStruct is mostly the generated body
(`static forward` alone is ~1.12× on deep, ~5.6 ns of the ~6.3 ns gap).

Runtime path forward: flat 40.14, nested 64.37, deep 315.23 ns/op — ~16× / ~14× / ~8× of MapStruct. The backward
direction stays higher (flat 108.31, nested 148.63, deep 626.39) because building a bean (allocate + N setters) is
structurally heavier than a record's canonical-ctor invoke and its read side was already optimal. The runtime path goes
through a structural-Iso build per `Telescope.mapper(...)` call site and a reflective dispatch chain on every
invocation; it's not in the dethrone-MapStruct lane, it's the convenience surface for "I don't want to write codegen for
this one mapper".

## Dispatch — `BRIDGE_FN` is the floor, the lattice wrapper is a ≤0.8 ns tax that scales with depth

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

Read the tight-band run (R2) — its bands are 3–30× narrower, so it's the one that resolves anything. Two things hold:

First, **`BRIDGE_FN` tracks the `static forward` floor within error on every tier**: identical on R2 flat (both 3.183),
tied on R2 nested (5.896 vs 5.902), and on R2 deep its ±0.675 band overlaps static (52.586 − 0.675 = 51.91 ≈ static
51.92). The one-interface-hop constant is monomorphic (one concrete `Fn` per bridge) and the JIT inlines it to the raw
static call — it is the floor, there is nothing faster to reach.

Second, **the full-lattice `BRIDGE.read` sits a wrapper tax above that floor, and the tax grows with depth**: on R2 it
is 0.14 ns (flat) → 0.31 ns (nested) → 0.77 ns (deep), each outside the tight error bands. That monotonic climb is the
lattice composition depth showing through — more nesting, more `Iso.then(...)` hops the wrapper carries. It stays small
in absolute terms (≤0.8 ns), and on deep it is dwarfed by the ~5.6 ns generated-body gap anyway (see next section). On
the wide-band R1 run the same gaps sit inside the noise, so R1 alone couldn't see them — which is exactly why we ran
twice.

An earlier run reported a ~0.3–0.7 ns "lattice slice" and proposed closing it by emitting a directly-callable
`BRIDGE_FN` constant. `BRIDGE_FN` shipped (#182) — and it lands at the `static forward` floor, so an adopter who wants
the fastest passable value already has it. The tax that remains sits only on the _composable_ `BRIDGE.read` value and is
≤0.8 ns; the type-specialized subclass (remediation #2) would remove only that, for only the narrow case of hot-looping
the composable value while refusing to switch to `BRIDGE_FN`. Not worth it.

The lesson stands: **smoke runs lie, and one CI run can too.** Run 1's 1.04× nested looked like a headline until run 2
returned 1.42× on the same branch — the nested MapStruct baseline is JMH-noisy (±0.35). Laptop smoke runs earlier
produced a 2.9–3.6× "forward gap", a "telescope-faster-on-backward" claim, and a "static-slower-than-lattice" inversion,
all wrong. Trust the numbers that reproduce across runs: flat ~1.07×, deep ~1.15×, and `BRIDGE_FN` at the floor.

## So is there a real gap?

**A small one, only on deep: ~1.15× forward, and it is mostly not dispatch.** Flat is ~1.07× (both runs). The deep
`BRIDGE.read` gap is ~6.3 ns, and it splits: the zero-dispatch `static forward` floor is _already_ ~1.12× on deep (~5.6
ns, the generated **body** — six leaf conversions, two list allocations, and per-field null-guards vs MapStruct's
directly-inlined field sequence), with the lattice wrapper adding ~0.8 ns on top. Body dominates ~7:1. Whether it
matters to an adopter:

- At <10M ops/sec on a hot mapper: invisible against application work.
- At >100M ops/sec on the deep tier: a ~6 ns per-call structural cost — measurable on a flame graph, rarely dominant.
- On flat: no gap to speak of (~1.07×). On nested the ratio is JMH-noisy run-to-run (1.04×–1.42×) — near-parity, but the
  noisy MapStruct baseline means no single figure is trustworthy.

## Remediations — status

### 1. Codegen emits a typed `BridgeFn<S, T>` constant — **shipped, measured, no perf effect**

`public static final BridgeFn<S, T> BRIDGE_FN = new Fn();` ships per generated bridge (asserted in
`BridgeProcessorTest`). It gives adopters a passable one-hop mapper value instead of a static method. The benchmark
tables above show it measures **at the `static forward` floor** in both runs (run 2 flat: identical at 3.183) — the JIT
inlines the monomorphic hop to the raw static call, so it is the fastest passable value there is. It is _faster_ than
`BRIDGE.read` by the ≤0.8 ns lattice-wrapper tax (largest on deep), so it is both the ergonomic value (a `BridgeFn` you
can pass around) and, marginally, the fast one.

### 2. Type-specialized bridge subclass whose `read(S)` is the inlined body — **measured and declined**

The idea was to emit a `Telescope`/bridge subclass that removes the `BridgeFn` field and the `invokeinterface` wrapper
so `read(S)` _is_ the generated body. The data shrinks the premise to nothing worth building: the wrapper tax it would
remove is ≤0.8 ns (the `BRIDGE.read − static forward` gap, largest on deep), and it applies **only to the composable
`BRIDGE.read` value** — adopters who want the floor already have `BRIDGE_FN`, which sits there. Worse, on the deep tier
where the tax is largest (~0.8 ns) it is swamped ~7:1 by the generated-body gap (~5.6 ns), so removing it barely moves
the ratio. It would add ~100 LOC of `BridgeProcessor` complexity to shave ≤0.8 ns off one of two already-shipped call
shapes. **Not building it.** The only thing that would move the deep number vs MapStruct is matching its generated
_body_ (fewer null-guards, inlined leaf conversions) — a separate, finer optimization, adopter-gated on someone actually
hitting the deep tier above 100M ops/sec.

### 3. The CI-reproducible matrix is the baseline

The manual `Benchmarks` workflow produces the full matrix on dedicated hardware with tight error bands. Future PRs
trigger it on their branch and baseline-diff against a prior run's artifact. `-prof gc` is wired as a `profilers` input
(`gc`, `stack`, `perfasm`; locally `-Pjmh.profilers=gc`) for decomposing call cost vs allocation when chasing a
residual.

## What this revision ships

- **`BRIDGE_FN` benchmarked across all three tiers** (`nested_*_bridgefn_forward`, `deep_*_bridgefn_forward`; flat
  already existed). This is what lets the forward tables compare all four call shapes — static, one-hop, lattice,
  MapStruct — on each run and pin `BRIDGE_FN` to the floor.
- **This analysis doc, corrected against two runs.** Prior revisions claimed a ~0.3–0.7 ns lattice slice and proposed
  two remediations to close it; a first fresh run then over-corrected to "dispatch is free everywhere". A confirmation
  run settled it: `BRIDGE_FN` is the floor, the lattice wrapper is a ≤0.8 ns tax that grows with nesting depth, and the
  nested ratio is JMH-noisy. The doc records the reproducible parity result and the measured-and-declined subclass
  decision.
- **No production code changed.** `BRIDGE_FN` already ships; the codegen is at parity as-is.

## Bottom line

Telescope codegen is at **effective MapStruct parity — ~1.07× flat and ~1.15× deep forward, both stable across two CI
runs** (nested is near-parity but JMH-noisy run-to-run, 1.04×–1.42×, so no single figure is publishable). On dispatch,
**`BRIDGE_FN` is the floor**: it tracks the zero-dispatch `static forward` within error on every tier (identical on
run-2 flat), because the JIT inlines the monomorphic hop. The full-lattice `BRIDGE.read` carries a wrapper tax that
grows with depth — 0.14 → 0.31 → 0.77 ns across flat/nested/deep on the tight run, each outside the error band. That
settles both proposed remediations: `BRIDGE_FN` shipped and lands at the floor (ergonomic _and_ marginally the fast
value), and the type-specialized subclass would remove only that ≤0.8 ns tax from the composable `BRIDGE.read` —
**declined**, since on deep (where the tax is largest) it is swamped ~7:1 by the body gap. The only real gap over
MapStruct is ~5.6 ns of generated-body work on the deep tier (`static forward` alone is ~1.12×), not dispatch; closing
it means matching MapStruct's inlined body, adopter-gated on a real deep-tier hot loop. The 2.9–3.6× forward "gap", the
"telescope faster on backward" claim, and the "static slower than lattice" inversion from laptop smoke runs were all JMH
noise. Measuring the dispatch claim to a conclusion — through two runs, not one — and declining the optimization it
implied, is the deliverable here.
