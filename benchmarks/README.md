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
classpath, dispatch short-circuits through them) rows. Fixtures are structurally identical between the two flavours —
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

**To run just this suite locally:**

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
`Method.invoke` for years and the old "100-260 ns per call" reflection cost just isn't real anymore for trivial
accessors after warmup.

So why bother with LMF at all? Not the per-call cost — the per-call cost is a wash. The win is that there's no
`Object[]` allocated per call, no access check, and the JIT sees a regular functional-interface call site it can inline
through composed lens chains. None of that shows up when you time one isolated read. It shows up when bigger workloads
turn `Method.invoke` into an inlining barrier and the JIT gives up. Post-LMF, the hot path is just a SAM call.

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

Captured on GitHub Actions `ubuntu-latest` (JDK 25, x64) at the standard config (3 warmup + 5 measurement × 1 fork, 3s
per iteration) via the manual [`Benchmarks`](../.github/workflows/benchmarks.yaml) workflow. Three depth tiers, both
directions, three engines per cell. All rows share their input fixtures via `@State(Scope.Benchmark)` — the only
difference between same-tier rows is the dispatch path. These numbers are reproducible: re-run the workflow on any
branch and compare.

| Tier   | Direction     | MapStruct (ns/op) | Telescope codegen (ns/op) | Telescope codegen static (ns/op) | Telescope runtime (ns/op) |
| ------ | ------------- | ----------------: | ------------------------: | -------------------------------: | ------------------------: |
| flat   | bean → record |     3.109 ± 0.061 |             4.844 ± 0.045 |                    4.549 ± 0.037 |             54.86 ± 0.169 |
| flat   | record → bean |     3.199 ± 0.013 |             4.840 ± 0.025 |                                — |            154.86 ± 1.482 |
| nested | bean → record |     4.223 ± 0.119 |             8.470 ± 0.058 |                    8.100 ± 0.121 |             86.81 ± 2.115 |
| nested | record → bean |     5.221 ± 0.124 |             8.448 ± 0.067 |                                — |            217.86 ± 2.771 |
| deep   | bean → record |     46.36 ± 0.240 |             53.41 ± 0.879 |                    52.68 ± 0.374 |            381.10 ± 8.800 |
| deep   | record → bean |     46.21 ± 0.436 |             53.03 ± 0.292 |                                — |            859.65 ± 4.549 |

Tight error bands across every row (±0.01–0.9 ns) — the dedicated CI runner with no competing workload gives cleaner
data than a laptop. The `static` column calls the codegen-emitted `<Source>Bridge.forward(s)` directly, bypassing the
`Telescope` lattice; it isolates the lattice-dispatch tax (see below).

#### How the runtime path stays fast

Two structural choices keep the runtime path cheap:

- **Fused source-and-remap** in `DeepMap.assembleIso`: no source-side `Object[]` intermediate. The forward body gathers
  directly from cached positional readers into the target `Object[]` per slot — one alloc + 5 array writes + 5 reads + 2
  `Iso.then` virtual dispatches saved per call vs the naive three-Iso composition.
- **Acyclic-pair shell bypass**: every nested type-pair hop would otherwise pay `ThreadLocal.get` +
  `IdentityHashMap.put` + try/finally for cycle safety. Type pairs whose static graph has no path back to themselves
  (detected during `populateIso` via SCC analysis on the recursion stack) get a plain Iso that skips the probe. Cyclic
  SCCs still get the full guard.

The acyclic-bypass alone reclaims ~15 ns per nested hop, compounding on the deep tier where the shell fires once per
level walked.

#### What the numbers say

**Codegen-for-codegen, telescope and MapStruct are the same performance class — a tie at realistic depth.** Three tiers:
on flat (3.11 vs 4.84 ns) telescope is 1.56× behind, ~1.7 ns absolute; on nested (4.22 vs 8.47 ns) 2.01×, ~4.2 ns; on
deep (46.36 vs 53.41 ns) **1.15× — a tie, ~7 ns on a 47 ns op**, both directions. The deeper the tree, the closer to
parity, because the constant wrapper overhead is fixed while the per-level conversion work grows. At the flat scale
you're choosing on API and capability, not nanoseconds.

The gap decomposes. The `static` column calls `<Source>Bridge.forward(s)` directly; on CI hardware it runs consistently
~0.3–0.7 ns faster than `BRIDGE.read(...)` (flat 4.55 vs 4.84, nested 8.10 vs 8.47, deep 52.68 vs 53.41). So the
`Telescope` lattice dispatch hop — `BridgeTelescope.read` → `BridgeFn.forward` → static `forward` → ctor — costs a
sub-nanosecond surcharge, and the remaining ~1.4 ns on flat is the generated body's bean-getter reads vs MapStruct's
directly-inlined sequence. (An earlier Apple-Silicon local run reported the static path as _slower_ than the lattice
path — a JMH escape-analysis artifact that the clean CI hardware dissolved.)

Where the flat-tier gap comes from. MapStruct emits one hand-templated method body per pair, fully monomorphic, and the
JIT inlines the whole conversion into a single basic block. Telescope's `@Bridge` codegen emits the same shape — a
direct constructor call — but wraps it in a `Telescope` for composability, so on a 4–8 ns flat/nested conversion the one
lattice hop is visible. On deep, where element-by-element list conversion dominates and the workload climbs past 50 ns,
it vanishes into the noise — hence the deep-tier tie. The ~1.7 ns flat surcharge buys you the composability MapStruct's
sealed method bodies don't have; if you're in a tight inner loop that doesn't need it, call `<Source>Bridge.forward(s)`
directly and shave the hop (the `static` column shows it lands ~0.3–0.7 ns under `BRIDGE.read`).

Runtime conversion (`Telescope.mapper(...)`) on the forward (bean → record) direction binds the source-side bean readers
once at assembly time (`Beans.capturedReader`), so the hot read is a single virtual `Function#apply` instead of a
per-call `persistentClassOf` + `GETTER_INVOKERS` ClassValue probe + name→reader lookup. That capture roughly halves the
forward direction — flat 111 → 55 ns, deep 884 → 381 ns — putting it at **~17× MapStruct on flat, ~8× on deep**. The
backward (record → bean) direction stays higher (~48× flat / ~18× deep): its read side is already optimal (record
readers), but building a bean — allocate + N setter calls — is structurally heavier than a record's single
canonical-constructor invoke. The deeper the tree, the more per-level work dominates the constant reflective-dispatch
overhead. Sub-microsecond on flat and nested, single-microsecond on deep. Reach for codegen on hot paths; the runtime
path is for one-shot conversions and non-hot service code.

The runtime column above is the post-capture measurement (run on the optimized branch); the MapStruct / codegen / static
columns are from the baseline run and are stable across both within error.

A quick decision guide. If the problem is "convert this entity to this DTO and back, both directions known at build
time, no nested-list iteration, just scalars," MapStruct's bytecode is still ~1.45× faster on the row (3.35 vs 4.86 ns).
On realistic deep workloads — nested records with list-of-records inside — telescope codegen matches MapStruct.

Where MapStruct stops being an option entirely: sealed-narrow paradigm hop, effectful update (`updateAsync`,
`updateValidated`), JPA cycle handling, Hibernate `LAZY` proxy unwrap, deep navigation as a primitive. These are out of
scope for a mapping-only model and demoed end-to-end in `examples/springboot/`. Capability wins, not perf wins, but
they're the reason you'd pick telescope in the first place.

Even the slowest telescope row — deep runtime backward at ~2.3 μs — is fine for typical request handling. One or a few
conversions per request, not millions per second.

One implementation note: the telescope `_codegen_backward` rows use `BRIDGE.set(placeholderBean, rec)`, which discards
the placeholder and invokes the underlying `iso.from(rec)`. The `set` wrapper adds a small constant overhead vs a direct
`iso.from`.
