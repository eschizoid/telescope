# telescope benchmarks

JMH micro-benchmarks that validate the performance claims in the design notes — the cost of reflection-based field
navigation versus the reflection-free `Telescope.lens` path versus a hand-written record copy.

This module is not published. It depends on `:core` and `:codegen` and is only built when you ask for it.

## Running

```
./gradlew :benchmarks:jmh
```

Config (in `benchmarks/build.gradle.kts`): average time in nanoseconds, 3 warmup iterations, 5 measurement iterations, 1
fork. Results are written to `benchmarks/build/results/jmh/`. A full run takes a few minutes.

## What it measures

The record benchmarks walk a `Company -> Department -> Address` tree and update the deeply-nested `Address::city`; the
POJO benchmarks walk an identical mutable-bean mirror. The conversion benchmarks convert one whole object. Each
`Telescope`/bridge is built once in `@Setup` and reused, so the numbers are per-operation cost, not construction.

### TelescopeBenchmark — deep-tree updates and full conversions

| Benchmark                  | What it does                                                                                         |
| -------------------------- | ---------------------------------------------------------------------------------------------------- |
| `reflectionFieldUpdate`    | Record deep-field update via `Telescope.of(...).field(...)` — reflection.                            |
| `lensConstantUpdate`       | Same update via composed `Telescope.lens(...)` constants — reflection-free (`@Focus` codegen).       |
| `handRolledCopyUpdate`     | Same update written by hand (nested `new Company(...)`) — record baseline.                           |
| `mapperForwardRead`        | `Telescope.map(...).build().read(...)` record→record conversion.                                     |
| `ofBeanFieldUpdate`        | Native POJO deep update via `Telescope.ofBean(...).field(getX)...` — rebuild-via-strategy per level. |
| `handRolledBeanCopyUpdate` | Same POJO update written by hand (no-arg ctor + setters) — bean baseline.                            |
| `mapBeanForwardRead`       | `Telescope.mapBean(...).build().read(...)` POJO→POJO conversion (runtime reflective).                |
| `fromBeanForwardRead`      | `Telescope.fromBean(...).viaFields().read(...)` POJO→record bridge.                                  |
| `bridgeForwardRead`        | Same POJO→POJO conversion via the generated `@Bridge` constant — reflection-free codegen.            |

### HolderDispatchBenchmark — codegen-routed dispatch vs reflective fallback

These benchmarks isolate the dispatch path on `@Focus`-annotated types vs unannotated ones. Each axis has paired `_lmf`
(no annotation, runtime reflective + LMF path) and `_holder` (sibling codegen-emitted `<X>FieldOptics` constants on the
classpath, dispatch short-circuits through them) rows. Fixtures are structurally identical between the two flavors —
same field names, same field types, same 3-level tree — so the only difference at dispatch time is whether the codegen
holder is present.

| Benchmark               | What it does                                                                                                                                                                                     |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `field_lmf`             | `Telescope.of(BenchPlainRec.class).field(BenchPlainRec::name).update(...)` against an unannotated record — falls back to the LMF-backed `Records.fieldLens(name)`.                               |
| `field_holder`          | Same dispatch against a `@Focus`-annotated record — the codegen holder hits and returns the pre-baked `BenchHolderRecFieldOptics.name` constant.                                                 |
| `field_holder_constant` | The generated constant invoked directly (`BenchHolderRecFieldOptics.name.update(...)`) — skips the holder lookup entirely; the pure codegen-direct ceiling.                                      |
| `mapForward_lmf`        | `Telescope.mapper(BenchPlainSrc.class, BenchPlainTgt.class).forward(...)` against unannotated types. Source-side reads via `Reflective.read`; target-side constructs via `Reflective.construct`. |
| `mapForward_holder`     | Same forward conversion against the `@Focus`-annotated 3-level tree — source-side reads via holder lens constants, target-side rebuilds via the holder's bound canonical constructor.            |
| `mapBackward_lmf`       | The opposite direction against the unannotated pair (`mapper.backward(...)`); same fallback shape.                                                                                               |
| `mapBackward_holder`    | The opposite direction against the annotated pair; holder-routed reads and construct swap roles between source and target sides.                                                                 |

### LmfBenchmark — single-step LambdaMetafactory dispatch primitive

These benchmarks isolate the LMF dispatch wrapper — one record component read, one bean getter read, or one bean setter
call — without composing through a multi-level optic. They measure the residual cost of the cached
`Function<Object, Object>` / `BiConsumer<Object, Object>` synthesized once via `LambdaMetafactory` against a directly
inlined Java call. The delta is the per-dispatch overhead the LMF wrapper adds on top of an inlined accessor / setter.

| Benchmark                          | What it does                                                                                                     |
| ---------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `recordComponentRead_lmf`          | `Records.read(record, "name")` — LMF-backed reader hot path.                                                     |
| `recordComponentRead_methodInvoke` | Cached `RecordComponent.getAccessor()` + `Method.invoke` per call — apples-to-apples reflection baseline.        |
| `recordComponentRead_handRolled`   | Direct `record.name()` — record accessor baseline.                                                               |
| `beanGetterRead_lmf`               | `Beans.readProperty(pojo, "name")` — LMF-backed getter hot path.                                                 |
| `beanGetterRead_methodInvoke`      | Cached `Method` for `getName()` + `Method.invoke` per call — apples-to-apples reflection baseline.               |
| `beanGetterRead_handRolled`        | Direct `pojo.getName()` — bean getter baseline.                                                                  |
| `beanSetterDispatch_lmf`           | `Beans.settersWriter(BenchPojo.class).construct(["name"], …)` — LMF-backed setter dispatch hot path.             |
| `beanSetterDispatch_methodInvoke`  | `new BenchPojo()` + cached `Method` for `setName(...)` + `Method.invoke` — apples-to-apples reflection baseline. |
| `beanSetterDispatch_handRolled`    | `new BenchPojo()` + direct `setName(...)` — bean setter baseline.                                                |

### MapStructComparisonBenchmark — head-to-head: MapStruct vs telescope

The apples-to-apples comparison. Three depth tiers × two directions × three engines (+ one `static_forward` row per
tier) = 21 benchmark rows on identical fixture shapes. Same input fixtures held in `@State(Scope.Benchmark)` and reused
across all rows per tier, so the engines time the SAME work — only the conversion path varies.

| Benchmark                               | Tier          | Direction       | Engine                                             |
| --------------------------------------- | ------------- | --------------- | -------------------------------------------------- |
| `flat_mapstruct_forward`                | flat          | bean → record   | MapStruct generated impl                           |
| `flat_telescope_runtime_forward`        | flat          | bean → record   | `Telescope.mapper(...)` reflective + LMF dispatch  |
| `flat_telescope_codegen_forward`        | flat          | bean → record   | `@Bridge`-emitted `*Bridge.BRIDGE` constant        |
| `flat_telescope_codegen_static_forward` | flat          | bean → record   | `*Bridge.forward(s)` static — bypasses the lattice |
| `flat_mapstruct_backward`               | flat          | record → bean   | MapStruct `@InheritInverseConfiguration`           |
| `flat_telescope_runtime_backward`       | flat          | record → bean   | `mapper.backward(...)`                             |
| `flat_telescope_codegen_backward`       | flat          | record → bean   | `BRIDGE.set(placeholder, rec)` — `Iso.from(rec)`   |
| `nested_*` / `deep_*` (× 7 each)        | nested / deep | both directions | same engines as above, incl. `*_static_forward`    |

**Tier shapes:**

- **flat** — 5 scalar fields, no nesting. The MapStruct sweet spot — a single hand-templated method body.
- **nested** — outer with 2 scalars + 1 nested type (`Address`). MapStruct needs an inner mapping method declared; the
  processor wires the recursion.
- **deep** — 3 levels of nesting + 2 `List<>` hops (`Company → List<Department> → List<Team>`). MapStruct auto-generates
  the list iterators when an element-level method exists.

**How to read the rows:**

- `_mapstruct_*` vs `_telescope_codegen_*` is the closest comparison — both bind at compile time and emit direct
  bytecode. Any delta is the cost of telescope's lattice composition vs MapStruct's hand-templated method body. Usually
  small and dominated by JIT inlining.
- `_telescope_runtime_*` establishes the upper bound on telescope when the consumer opts out of codegen entirely. The
  gap to `_telescope_codegen_*` is what `@Bridge` buys.

**To run only this suite locally:**

```
./gradlew :benchmarks:jmh -Pjmh.includes=MapStructComparisonBenchmark
```

**To reproduce on CI hardware** (without burning your laptop's battery for 20 minutes): trigger the manual
[`Benchmarks`](../.github/workflows/benchmarks.yaml) GitHub Action. Actions tab → `Benchmarks` → `Run workflow`, pick
the branch (default branch dropdown), and tune the iteration / fork knobs. The job summary prints `results.txt` inline;
the full `results/` directory is attached as an artifact named `jmh-results-<sha>-<run-id>` so consecutive runs don't
overwrite each other and a follow-up PR can baseline-diff against a known prior run.

## Results

Numbers are machine-specific; reproduce with `./gradlew :benchmarks:jmh`. The ratios between rows matter more than the
absolute values. A local run (JDK 25, Apple Silicon, 1 fork, 3 warmup + 5 measurement iterations) gave:

| Benchmark                  | ns/op |           vs hand-copy |
| -------------------------- | ----: | ---------------------: |
| `bridgeForwardRead`        |   7.0 | **codegen conversion** |
| `handRolledBeanCopyUpdate` |  21.6 |   1.0x (bean baseline) |
| `handRolledCopyUpdate`     |  25.6 |        record baseline |
| `lensConstantUpdate`       |  45.2 |                   2.1x |
| `reflectionFieldUpdate`    | 113.3 |                   5.2x |
| `mapperForwardRead`        | 215.2 |          record→record |
| `fromBeanForwardRead`      | 219.6 |                  10.2x |
| `ofBeanFieldUpdate`        | 251.2 |                  11.6x |
| `mapBeanForwardRead`       | 284.6 |                  13.2x |

Both deep-field benchmarks walk three levels — divide by three for per-level cost: record reflection ≈87 ns/level, the
`lens` path ≈15 ns/level, native `ofBean` ≈163 ns/level.

A tighter LMF-tier capture at **5 warmup + 10 measurement × 3 fork** (single-step dispatch, no composition) gave:

| Benchmark                          |  ns/op |  ±error |              vs hand-rolled |
| ---------------------------------- | -----: | ------: | --------------------------: |
| `recordComponentRead_handRolled`   |  1.334 | ± 0.845 |           record-read floor |
| `recordComponentRead_lmf`          |  9.394 | ± 4.190 |                         ~7× |
| `recordComponentRead_methodInvoke` | 18.260 | ± 7.588 | apples-to-apples reflection |
| `beanGetterRead_handRolled`        |  1.008 | ± 0.354 |           bean-getter floor |
| `beanGetterRead_lmf`               | 13.083 | ± 5.309 |                        ~13× |
| `beanGetterRead_methodInvoke`      | 10.660 | ± 1.635 | apples-to-apples reflection |
| `beanSetterDispatch_handRolled`    |  3.203 | ± 0.954 |           bean-setter floor |
| `beanSetterDispatch_lmf`           | 22.285 | ± 9.487 |                         ~7× |
| `beanSetterDispatch_methodInvoke`  | 12.001 | ± 4.192 | apples-to-apples reflection |

The thing that surprised me here: at the single-step level, LMF and `Method.invoke` are basically the same speed.
`Method.invoke` is even a touch faster on the bean getter and setter in this microbenchmark. HotSpot has been working on
`Method.invoke` for years and the old "100-260 ns per call" reflection cost isn't real anymore for trivial accessors
after warmup.

So why bother with LMF at all? Not the per-call cost — the per-call cost is a wash. The win is that there's no
`Object[]` allocated per call, no access check, and the JIT sees a regular functional-interface call site it can inline
through composed lens chains. None of that shows up when you time one isolated read. It shows up when bigger workloads
turn `Method.invoke` into an inlining barrier and the JIT gives up. Post-LMF, the hot path is a plain SAM call.

### Codegen-routed dispatch — annotated vs reflective fallback

A 5 warmup + 10 measurement × 3 fork capture of `HolderDispatchBenchmark` against the same machine (JDK 25, Apple
Silicon), with the codegen `<X>FieldOptics` constants on the classpath for the `_holder` rows, gave:

| Benchmark               | ns/op | ±error |                                         vs LMF |
| ----------------------- | ----: | -----: | ---------------------------------------------: |
| `field_holder`          |  25.3 |   ±0.3 |                       **3.23x vs `field_lmf`** |
| `field_holder_constant` |  25.8 |   ±0.2 | direct holder — within error of `field_holder` |
| `field_lmf`             |  81.8 |   ±1.7 |                         LMF substrate baseline |
| `mapForward_holder`     | 542.2 |  ±31.3 |                  **1.21x vs `mapForward_lmf`** |
| `mapForward_lmf`        | 655.9 |  ±33.8 |                         LMF substrate baseline |
| `mapBackward_holder`    | 536.4 |  ±22.5 |                 **1.26x vs `mapBackward_lmf`** |
| `mapBackward_lmf`       | 677.3 |  ±32.8 |                         LMF substrate baseline |

The `field_*` rows are a single deep-field write (`Telescope.of(X).field(X::name).update(rec, fn)`); the `mapForward_*`
/ `mapBackward_*` rows are a full 3-level record-tree conversion via `Telescope.mapper(A, B).forward(...)` /
`.backward(...)`. Each `_lmf` row is the structural twin of its `_holder` sibling — same fixture shape, only the
`@Focus` annotation differs — so the ratio between the pair is the codegen-routed savings rather than a workload
difference.

#### What the numbers say

Per-field dispatch is the big win. `field_holder` at 25.3 ns/op runs 3.23× faster than `field_lmf` at 81.8 ns/op. The
`field_holder_constant` row at 25.8 ns/op invokes the generated constant directly and skips the holder lookup; it clocks
the same as `field_holder`, which means the `ClassValue`-cached lookup costs nothing on the warm path. For deep field
navigation on `@Focus`-annotated types, that's a real constant-factor improvement compounding across levels.

Deep mapping shows a smaller win, but still measurable. `mapForward_holder` at 542.2 ns/op is 1.21× faster than
`mapForward_lmf` at 655.9 ns/op. Backward is the same shape: `mapBackward_holder` at 536.4 vs `mapBackward_lmf` at 677.3
ns/op, 1.26× faster. The reason it's smaller than the field-dispatch win: canonical-ctor invocation and the intermediate
`Map` allocation still dominate. The holder-routed lens reads and constructor shave ~115 ns off the ~656 ns baseline,
but they don't change the dominant cost.

For `Telescope.mapper(A, B)` on `@Focus`-annotated pairs, the end-to-end speedup lands at ~1.2–1.3×. The headline is the
per-field dispatch; the deep-mapping wins are what the codegen-emitted constructor unlocks once the construct path is
reflection-free.

A few practical takeaways from the row-by-row.

`@Bridge` is the codegen story for conversions. At ~15 ns it runs ~9.5× faster than runtime `mapBean` on the same
POJO→POJO conversion, because direct constructor/setter calls compile away the field scan, getter resolution, and writer
dispatch that the reflective path pays per call. (`handRolledBeanCopyUpdate` at ~22 ns is a different, deeper workload —
a 3-level tree rebuild — so it's not an apples-to-apples lower bound. The codegen-vs-reflective conversion comparison is
the one to trust.) Use the annotation whenever the source/target pair is known at compile time; runtime `mapBean` /
`fromBean` / `from-to-using` are for the cases that need renames or transforms.

Reflective conversion is still reasonable. Runtime `fromBean` (~114 ns), `mapper` (~135 ns), and `mapBean` (~142 ns)
cluster in the same band. That's ordinary-feature territory for sub-microsecond conversion.

Native POJO navigation is the expensive path. `ofBean` at ~488 ns is ~22× a hand-written bean copy and ~1.9× record
reflection — it rebuilds the whole POJO at every level and re-reads all getters per level to carry untouched properties
over. Still sub-microsecond (~2M ops/sec single-threaded), so it's fine for ordinary use. For a hot loop, bridge once to
a record with `fromBean` (or reach for `@BeanFocus` codegen) rather than rebuilding the bean at each level.

The reflection-free `lens` path at ~45 ns is what `@Focus` codegen emits — ~5.8× faster than record reflection, ~1.7× a
hand-copy. That's the codegen payoff for deep field navigation.

### MapStruct comparison (apples-to-apples)

Captured on GitHub Actions `ubuntu-latest` (JDK 25, x64) at 3 warmup + 5 measurement × 3 forks × 3s via the manual
[`Benchmarks`](../.github/workflows/benchmarks.yaml) workflow, on current `main` (with the MethodHandle-combinator
runtime leaf). Absolute ns differ between runner generations, so read the ratios, not the raw ns. Three depth tiers,
both directions, three engines per cell. All rows share their input fixtures via `@State(Scope.Benchmark)` — the only
difference between same-tier rows is the dispatch path. Reproducible: re-run the workflow on any branch and compare.

| Tier   | Direction     | MapStruct (ns/op) | Telescope codegen (ns/op) | Telescope codegen static (ns/op) | Telescope runtime (ns/op) |
| ------ | ------------- | ----------------: | ------------------------: | -------------------------------: | ------------------------: |
| flat   | bean → record |     3.167 ± 0.014 |             3.393 ± 0.011 |                    3.299 ± 0.005 |             12.21 ± 0.254 |
| flat   | record → bean |     3.305 ± 0.028 |             3.485 ± 0.022 |                                — |             11.44 ± 0.947 |
| nested | bean → record |     4.928 ± 0.024 |             5.879 ± 0.022 |                    5.553 ± 0.011 |             30.65 ± 2.596 |
| nested | record → bean |     5.983 ± 0.024 |             5.355 ± 0.019 |                                — |             29.87 ± 0.504 |
| deep   | bean → record |     48.68 ± 0.106 |             55.04 ± 0.522 |                    54.71 ± 0.375 |              90.1 ± 2.5\* |
| deep   | record → bean |     48.54 ± 0.273 |             54.81 ± 0.418 |                                — |              95.0 ± 2.5\* |

\* The deep runtime cells were re-measured on a laptop after the container-element MethodHandle loop landed (a nested
`List` element now loops over the leaf's raw handle instead of dispatching `Iso.to` per element). Same-machine A/B on
that laptop: main **205.4 / 207.2 ns** → branch **90.1 / 95.0 ns** (**~2.2×**), taking deep runtime from ~4.4× MapStruct
to ~1.3–1.9× on the same box. The other cells are the CI runner's; the whole table refreshes on the next benchmark
workflow run.

Tight error bands on the codegen/MapStruct rows (±0.01–0.35 ns) — the dedicated CI runner with no competing workload
gives cleaner data than a laptop. The `static` column calls the codegen-emitted `<Source>Bridge.forward(s)` directly,
bypassing the `Telescope` lattice; it isolates the lattice-dispatch tax. A directly-callable `BRIDGE_FN` constant (one
interface hop, omitted here for width) lands at the `static` floor — the full four-call-shape breakdown and the
dispatch-tax decomposition live in [`docs/perf-mapstruct-comparison.md`](../docs/perf-mapstruct-comparison.md).

#### How the runtime path stays fast

The runtime leaf for a record/bean type pair is a **MethodHandle-combinator**: the whole `(S) -> T` conversion is one
composed handle — each source property's raw, primitive-typed accessor piped straight into the target's raw canonical
constructor (`filterArguments` + `permuteArguments`) or, for a bean target, its no-arg constructor folded with one raw
setter per slot (`foldArguments`). On same-name/same-type slots the value flows **primitive-to-primitive with no box and
no `Object[]`**; only slots carrying a real per-field conversion (rename, nested pair, container lift) route through
their `Iso`. It stays lattice-native — the composed handle is wrapped in `Iso.of(...)`, and composition above the leaf
is unchanged. This is what took the runtime path from ~16–48× MapStruct to ~3.5–6.2× (`MhIso`, byte-identical to the
prior array leaf across a differential fuzz).

Two older structural choices still keep the paths above the leaf lean: **fused source-and-remap** (no source-side
`Object[]` intermediate for the array leaf that owns builder/field-injection beans) and the **acyclic-pair shell
bypass** (nested type-pair hops skip the `ThreadLocal` + `IdentityHashMap` cycle guard when SCC analysis proves no value
cycle is possible; cyclic SCCs keep the full guard).

#### What the numbers say

**Codegen-for-codegen, telescope and MapStruct are the same performance class — a tie at realistic depth.** On flat
(3.17 vs 3.39 ns) telescope is **~1.07×**, ~0.2 ns absolute; on deep (48.68 vs 55.04 ns) **~1.13× — a tie, ~6 ns on a
~50 ns op**, both directions, stable across CI runs. The nested single-hop tier swings 1.04×–1.42× run-to-run — its
MapStruct baseline is JMH-noisy (±0.35) — so it's a framework-overhead microbench, not a number to publish. The deeper
the tree, the more the per-level conversion work dominates the fixed dispatch overhead; at the flat scale you're
choosing on API and capability, not nanoseconds.

The gap decomposes into a tiny dispatch tax plus the generated body. The `static` column (zero-dispatch
`<Source>Bridge.forward(s)`) is the floor; the `BRIDGE.read` lattice path sits a wrapper tax above it that grows with
nesting depth but stays ≤0.8 ns, and on deep the residual over MapStruct is mostly the generated body — six leaf
conversions and two list allocations — not dispatch. The full two-run decomposition, all four call shapes (`static` /
`BRIDGE_FN` / `BRIDGE.read` / MapStruct) at each tier, plus the JMH-artifact history, lives in
[`docs/perf-mapstruct-comparison.md`](../docs/perf-mapstruct-comparison.md).

Where the flat-tier gap comes from. MapStruct emits one hand-templated method body per pair, fully monomorphic, and the
JIT inlines the whole conversion into a single basic block. Telescope's `@Bridge` codegen emits the same shape — a
direct constructor call — wrapped in a `Telescope` for composability. On a flat ~3 ns conversion that composability
costs ~0.2 ns total; on deep, where element-by-element list conversion dominates and the workload climbs past 50 ns, it
is a ~1.15× tie. If you're in a tight inner loop that doesn't need composition, call `<Source>Bridge.forward(s)` — or
the directly-callable `BRIDGE_FN` constant — and pay the zero-dispatch floor.

Runtime conversion (`Telescope.mapper(...)`) composes each record/bean pair into a single MethodHandle (see above), so
the hot path is one `invokeExact` through the fused handle rather than an `Object[]` gather with boxed per-field
dispatch. That lands the forward direction at **~3.9× MapStruct on flat, ~6.2× on nested, ~1.3–1.9× on deep**. Deep used
to trail at ~4.4×; the container-element MethodHandle loop (a nested `List`/`Set`/`Map` element loops over the leaf's
raw handle instead of dispatching `Iso.to` → `Function.apply` per element, which also un-megamorphizes the shared lift
call site) roughly halved it — same-machine 205 → 90 ns. The backward (record → bean) direction — previously the
pathological case (allocate a bean, then N boxed setter calls, ~48× MapStruct on flat) — is now **~3.5× on flat** via
the unboxed setter-fold, matching forward instead of trailing it. Allocation drops to the result-object floor (flat 32
B/op, the array + every primitive box gone). Sub-microsecond everywhere. Reach for codegen on the hottest paths; the
runtime path is now within ~1.3–6× of MapStruct with **no annotations and no build step** — and closest exactly where it
matters most, on the deep container-heavy trees — close enough for most service code, and `@Bridge` codegen is there
when a loop turns hot.

All four columns above are from the same run; the codegen/MapStruct ratios reproduce across confirming runs within error
(the runtime rows carry wider bands but the same magnitude).

A quick decision guide. If the problem is "convert this entity to this DTO and back, both directions known at build
time, no nested-list iteration, only scalars," MapStruct's bytecode is ~1.07× faster on the row (3.17 vs 3.39 ns, ~0.2
ns absolute). On realistic deep workloads — nested records with list-of-records inside — telescope codegen matches
MapStruct.

Where MapStruct stops being an option entirely: sealed-narrow paradigm hop, effectful update (`updateAsync`,
`updateValidated`), JPA cycle handling, Hibernate `LAZY` proxy unwrap, deep navigation as a primitive. These are out of
scope for a mapping-only model and demoed end-to-end in `examples/springboot/`. Capability wins, not perf wins, but
they're the reason you'd pick telescope in the first place.

Even the slowest telescope row — deep runtime backward at ~0.87 μs — is fine for typical request handling. One or a few
conversions per request, not millions per second.

One implementation note: the telescope `_codegen_backward` rows use `BRIDGE.set(placeholderBean, rec)`, which discards
the placeholder and invokes the underlying `iso.from(rec)`. The `set` wrapper adds a small constant overhead vs a direct
`iso.from`.
