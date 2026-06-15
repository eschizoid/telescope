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

The apples-to-apples comparison. Three depth tiers × two directions × three engines = 18 benchmark rows on identical
fixture shapes. Same input fixtures held in `@State(Scope.Benchmark)` and reused across all six rows per tier, so the
three engines time the SAME work — only the conversion path varies.

| Benchmark                         | Tier   | Direction       | Engine                                            |
| --------------------------------- | ------ | --------------- | ------------------------------------------------- |
| `flat_mapstruct_forward`          | flat   | bean → record   | MapStruct generated impl                          |
| `flat_telescope_runtime_forward`  | flat   | bean → record   | `Telescope.mapper(...)` reflective + LMF dispatch |
| `flat_telescope_codegen_forward`  | flat   | bean → record   | `@Bridge`-emitted `*Bridge.BRIDGE` constant       |
| `flat_mapstruct_backward`         | flat   | record → bean   | MapStruct `@InheritInverseConfiguration`          |
| `flat_telescope_runtime_backward` | flat   | record → bean   | `mapper.backward(...)`                            |
| `flat_telescope_codegen_backward` | flat   | record → bean   | `BRIDGE.set(placeholder, rec)` — `Iso.from(rec)`  |
| `nested_*` (× 6)                  | nested | both directions | same three engines as above                       |
| `deep_*` (× 6)                    | deep   | both directions | same three engines as above                       |

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

**To run just this suite:**

```
./gradlew :benchmarks:jmh -Pjmh.includes=MapStructComparisonBenchmark
```

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

Captured on the same machine (JDK 25, Apple Silicon) at the standard config (3 warmup + 5 measurement × 1 fork, 10s per
iteration). Three depth tiers, both directions, three engines per cell. All 18 rows share their input fixtures via
`@State(Scope.Benchmark)` — the only difference between same-tier rows is the dispatch path.

| Tier   | Direction     | MapStruct (ns/op) | Telescope codegen (ns/op) | Telescope runtime (ns/op) |
| ------ | ------------- | ----------------: | ------------------------: | ------------------------: |
| flat   | bean → record |             3.478 |                     5.502 |                    143.38 |
| flat   | record → bean |             3.469 |                   7.771 ¹ |                    213.26 |
| nested | bean → record |             5.186 |                    10.481 |                    241.21 |
| nested | record → bean |             5.325 |                    10.762 |                    332.80 |
| deep   | bean → record |            47.432 |                    57.648 |                    926.83 |
| deep   | record → bean |          60.677 ¹ |                    55.236 |                   1274.30 |

¹ Wide error band on this row (`flat record→bean codegen ±12.4`, `deep record→bean MapStruct ±34.4`). Skim past these
two cells; the other 16 are tight (±0.1–5 ns) and stable. Full ± table in PR #118.

All 18 rows captured together on a freshly-rebooted machine, no other workloads running. The runtime column reflects the
Object[] structural intermediate change shipped in PR #118 — runtime ns dropped 52-58% across every row vs the
previously published numbers.

#### What the numbers say

Three tiers, codegen path. On flat (3.48 vs 5.50 ns), telescope codegen runs 1.58× behind MapStruct — absolute gap ~2.0
ns. On nested (5.19 vs 10.48 ns), 2.02× behind, ~5.3 ns absolute. On deep (47.43 vs 57.65 ns), 1.22× behind on forward.
The deep backward row shows telescope codegen ahead on the means (55.24 vs 60.68 ns), but MapStruct's wide ±34.4 ns
error band on that row means the honest read is "both engines in the same band" — not a clean win for either.

Why MapStruct wins on the small tiers. It emits one hand-templated method body per pair, fully monomorphic, and the JIT
inlines the whole conversion into a single basic block. Telescope's `@Bridge` codegen emits the same shape — a direct
constructor call — but wraps it in a `Telescope` for composability. The wrapper's `read` / `set` terminals are
specialised on a `BridgeTelescope` subclass that holds the `BridgeFn` directly and dispatches in one virtual hop, but
that hop still costs ~2 ns of constant overhead. On flat and nested the actual work is only 3–10 ns, so the overhead
shows up. On deep, where element-by-element list conversion dominates and the workload climbs past 50 ns, that overhead
vanishes.

Runtime deep conversion (`Telescope.mapper(...)`) runs ~20–46× slower than MapStruct's generated bytecode after the PR
#118 Object[] intermediate change — 41× on flat, 46× on nested, 20× on deep. The lens chain walks the record/bean spine
at every level (cached LMF readers, not raw reflection), and per-call allocation is now 64–384 B/op (down from 776–1296
B/op). Sub-microsecond on flat and nested, single-microsecond on deep. Reach for codegen on hot paths; the runtime path
is for one-shot conversions and non-hot service code.

A quick decision guide. If the problem is "convert this entity to this DTO and back, both directions known at build
time, no nested-list iteration, just scalars," MapStruct's bytecode is still ~1.2× faster on the row (4.40 vs 5.44 ns).
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
