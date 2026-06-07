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

### LmfBenchmark — single-step LambdaMetafactory dispatch primitive (ADR-0005)

These benchmarks isolate the LMF dispatch wrapper — one record component read, one bean getter read, or one bean setter
call — without composing through a multi-level optic. They measure the residual cost of the cached
`Function<Object, Object>` / `BiConsumer<Object, Object>` synthesized once via `LambdaMetafactory` against a directly
inlined Java call. The delta is the per-dispatch overhead the LMF wrapper adds on top of an inlined accessor / setter.

| Benchmark                          | What it does                                                                                                      |
| ---------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| `recordComponentRead_lmf`          | `Records.read(record, "name")` — Phase 1 LMF reader hot path.                                                     |
| `recordComponentRead_methodInvoke` | Cached `RecordComponent.getAccessor()` + `Method.invoke` per call — the pre-LMF baseline Phase 1 replaced.        |
| `recordComponentRead_handRolled`   | Direct `record.name()` — record accessor baseline.                                                                |
| `beanGetterRead_lmf`               | `Beans.readProperty(pojo, "name")` — Phase 2 LMF getter hot path.                                                 |
| `beanGetterRead_methodInvoke`      | Cached `Method` for `getName()` + `Method.invoke` per call — the pre-LMF baseline Phase 2 replaced.               |
| `beanGetterRead_handRolled`        | Direct `pojo.getName()` — bean getter baseline.                                                                   |
| `beanSetterDispatch_lmf`           | `Beans.settersWriter(BenchPojo.class).construct(["name"], …)` — Phase 3 LMF setter dispatch hot path.             |
| `beanSetterDispatch_methodInvoke`  | `new BenchPojo()` + cached `Method` for `setName(...)` + `Method.invoke` — the pre-LMF baseline Phase 3 replaced. |
| `beanSetterDispatch_handRolled`    | `new BenchPojo()` + direct `setName(...)` — bean setter baseline.                                                 |

## Results

Numbers are machine-specific; reproduce with `./gradlew :benchmarks:jmh`. The ratios between rows matter more than the
absolute values. A local run (JDK 25, Apple Silicon, 1 fork, 3 warmup + 5 measurement iterations — directional, not
publication-grade) gave:

| Benchmark                  | ns/op | ±error |           vs hand-copy |
| -------------------------- | ----: | -----: | ---------------------: |
| `bridgeForwardRead`        |  14.9 |   ±0.2 | **codegen conversion** |
| `handRolledBeanCopyUpdate` |  22.2 |   ±0.6 |   1.0x (bean baseline) |
| `handRolledCopyUpdate`     |  26.4 |   ±1.9 |        record baseline |
| `lensConstantUpdate`       |  45.2 |   ±3.4 |                   1.7x |
| `fromBeanForwardRead`      | 114.0 |   ±1.7 |                   5.1x |
| `mapperForwardRead`        | 135.4 |  ±90.1 |  record→record (noisy) |
| `mapBeanForwardRead`       | 142.5 |   ±3.7 |                   6.4x |
| `reflectionFieldUpdate`    | 261.6 |  ±15.9 |                  11.8x |
| `ofBeanFieldUpdate`        | 488.1 | ±139.7 |                  22.0x |

Both deep-field benchmarks walk three levels — divide by three for per-level cost: record reflection ≈87 ns/level, the
`lens` path ≈15 ns/level, native `ofBean` ≈163 ns/level.

A tighter LMF-tier capture at **5 warmup + 10 measurement × 3 fork** (single-step dispatch, no composition) gave:

| Benchmark                          |    ns/op |   ±error |             vs hand-rolled |
| ---------------------------------- | -------: | -------: | -------------------------: |
| `recordComponentRead_handRolled`   |    1.334 |  ± 0.845 |          record-read floor |
| `recordComponentRead_lmf`          |    9.394 |  ± 4.190 |                       ~7×  |
| `recordComponentRead_methodInvoke` |   18.260 |  ± 7.588 | apples-to-apples reflection |
| `beanGetterRead_handRolled`        |    1.008 |  ± 0.354 |          bean-getter floor |
| `beanGetterRead_lmf`               |   13.083 |  ± 5.309 |                      ~13×  |
| `beanGetterRead_methodInvoke`      |   10.660 |  ± 1.635 | apples-to-apples reflection |
| `beanSetterDispatch_handRolled`    |    3.203 |  ± 0.954 |          bean-setter floor |
| `beanSetterDispatch_lmf`           |   22.285 |  ± 9.487 |                       ~7×  |
| `beanSetterDispatch_methodInvoke`  |   12.001 |  ± 4.192 | apples-to-apples reflection |

**Honest read of the numbers.** At the single-step dispatch primitive level, **LMF and `Method.invoke` are roughly
comparable** — and on the bean getter and setter, `Method.invoke` is actually a touch faster in this micro-benchmark
(though the error bars overlap). Modern HotSpot has been aggressively optimizing `Method.invoke` for years; the
historical "100-260 ns per call" reflection cost no longer holds at this scale for trivial accessors after warmup.

The case for the LMF substrate ([ADR-0005](../docs/adr/0005-lambdametafactory-over-method-handle-invoke.md)) was
**structural, not per-call**: removing the per-call `Object[]` argument allocation, eliminating the access-check, and
giving the JIT a normal functional-interface call site it can inline through composed lens chains. Those wins don't
show up in a JMH benchmark that times a single isolated read — they show up at the boundary of bigger workloads where
`Method.invoke` becomes an inlining barrier. The `TelescopeBenchmark` deep-tree numbers above are too noisy on this
machine to make the inlining claim quantitatively here, but the architectural argument still stands: post-LMF, the hot
path is a normal SAM call, not an opaque reflection invocation.

Caveats on the numbers: error bars are wide (±20–70% of the mean) because the operations are 1–22 ns and JMH's
`Blackhole` overhead is in the same order of magnitude. JIT-Blackhole interaction is noted in the JMH output itself.
Direction is reliable; absolute values aren't tight.

Takeaways:

- **`@Bridge` is the codegen win for conversions** — at ~15 ns it's ~9.5x faster than runtime `mapBean` for the same
  POJO→POJO conversion. Direct constructor/setter calls compile away the field scan, getter resolution, and writer
  dispatch that the reflective path has to do per call. (`handRolledBeanCopyUpdate` at ~22 ns is a different, deeper
  workload — a 3-level tree rebuild — so it isn't an apples-to-apples lower bound; the codegen vs reflective conversion
  comparison is the trustworthy one.) Use the annotation whenever the source/target pair is known at compile time; fall
  back to runtime `mapBean` / `fromBean` / `from-to-using` only for cases needing renames or transforms.
- **Conversion is reasonable even reflectively.** Runtime `fromBean` (~114 ns), `mapper` (~135 ns), and `mapBean` (~142
  ns) cluster in the same band — ordinary-feature territory for sub-microsecond conversions.
- **Native POJO navigation (`ofBean`) is the expensive path — ~488 ns, ~22x a hand-written bean copy and ~1.9x record
  reflection.** It rebuilds the whole POJO at _every_ level and re-reads all getters per level to carry untouched
  properties over. Still sub-microsecond (~2M ops/sec single-threaded), so fine for ordinary use — but for a hot loop,
  bridge once to a record with `fromBean` (or use `@BeanFocus` codegen) rather than rebuilding the bean at each level.
- The reflection-free `lens` path (~45 ns) — what `@Focus` codegen emits — is ~5.8x faster than record reflection and
  ~1.7x a hand-copy. That's the codegen payoff for deep field navigation.
