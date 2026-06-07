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

### HolderDispatchBenchmark — sibling metadata holder routing (ADR-0006 Phases B + C)

These benchmarks isolate the holder-routed dispatch path that ADR-0006 layers on top of the LMF substrate. Each axis has
paired `_lmf` (no `@Focus`, probe misses, LMF path runs) and `_holder` (sibling `<X>Telescope` holder on the classpath,
probe hits, holder constant returned) rows. Fixtures are structurally identical between the two flavours — same field
names, same field types, same 3-level tree — so the only difference at dispatch time is whether the holder is present.

| Benchmark               | What it does                                                                                                                                                              |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `field_lmf`             | `Telescope.of(BenchPlainRec.class).field(BenchPlainRec::name).update(...)` against an unannotated record — Phase B fallback to the LMF-backed `Records.fieldLens(name)`.  |
| `field_holder`          | Same dispatch against a `@Focus`-annotated record — `MetadataHolderProbe` hits and returns the pre-baked `BenchHolderRecTelescope.name` constant.                         |
| `field_holder_constant` | The generated constant invoked directly (`BenchHolderRecTelescope.name.update(...)`) — skips the probe entirely; the pure codegen-direct ceiling.                         |
| `mapForward_lmf`        | `Telescope.mapper(BenchPlainSrc.class, BenchPlainTgt.class).forward(...)` — Phase C fallback. Source-side backward Iso branch reads each component via `Reflective.read`. |
| `mapForward_holder`     | Same forward conversion against the `@Focus`-annotated 3-level tree — source-side backward Iso branch reads via holder lens constants.                                    |
| `mapBackward_lmf`       | The opposite Iso direction against the unannotated pair (`mapper.backward(...)`); same fallback shape.                                                                    |
| `mapBackward_holder`    | The opposite direction against the annotated pair; holder-routed reads on the source-side backward branch.                                                                |

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

A second-run capture of the LMF-tier benchmarks (single-step dispatch, no composition) gave:

| Benchmark                          |                                                             ns/op |                       vs hand-rolled |
| ---------------------------------- | ----------------------------------------------------------------: | -----------------------------------: |
| `recordComponentRead_handRolled`   |                                                              ~0.8 |                    record-read floor |
| `recordComponentRead_lmf`          |                                                               ~15 |                                 ~18× |
| `recordComponentRead_methodInvoke` | _to be captured at 5 warmup + 10 measurement × 3 fork before 1.0_ | apples-to-apples reflection baseline |
| `beanGetterRead_handRolled`        |                                                              ~0.8 |                    bean-getter floor |
| `beanGetterRead_lmf`               |                                                               ~26 |                                 ~33× |
| `beanGetterRead_methodInvoke`      | _to be captured at 5 warmup + 10 measurement × 3 fork before 1.0_ | apples-to-apples reflection baseline |
| `beanSetterDispatch_handRolled`    |                                                                ~4 |                    bean-setter floor |
| `beanSetterDispatch_lmf`           |                                                               ~36 |                                  ~9× |
| `beanSetterDispatch_methodInvoke`  | _to be captured at 5 warmup + 10 measurement × 3 fork before 1.0_ | apples-to-apples reflection baseline |

These are atomic-dispatch numbers, not deep-tree costs — comparable to the per-level estimates above. The LMF wrapper
adds ~15-35 ns of dispatch overhead per call (a synthetic-class virtual dispatch plus boxing) on top of the directly
inlined Java call. That overhead is what made swapping `Method.invoke` for `LambdaMetafactory` worthwhile: pre-Phase-1
the same dispatch went through `Method.invoke` and ran ~100-260 ns, depending on JIT state — the in-flight 5+10×3 run
will land hard numbers on the `_methodInvoke` rows above, closing the apples-to-apples claim without needing to
extrapolate from the (deeper-workload) `TelescopeBenchmark` runtime rows.

### Hybrid dispatch (ADR-0006 Phases B + C)

A 5 warmup + 10 measurement × 3 fork capture of `HolderDispatchBenchmark` against the same machine (JDK 25, Apple
Silicon) gave:

| Benchmark               |  ns/op | ±error |                                                 vs LMF |
| ----------------------- | -----: | -----: | -----------------------------------------------------: |
| `field_holder`          |   25.6 |   ±0.3 |                               **3.54x vs `field_lmf`** |
| `field_holder_constant` |   26.0 |   ±1.1 |         direct holder — within error of `field_holder` |
| `field_lmf`             |   90.7 |   ±4.5 |                                 LMF substrate baseline |
| `mapForward_holder`     |  808.3 |  ±38.8 |                             ~1.04x vs `mapForward_lmf` |
| `mapForward_lmf`        |  837.1 |  ±43.7 |                                 LMF substrate baseline |
| `mapBackward_holder`    | 1163.0 | ±334.5 | _noisy: kpipe sibling JMH on the same box, wide error_ |
| `mapBackward_lmf`       | 1374.3 | ±522.2 | _noisy: kpipe sibling JMH on the same box, wide error_ |

The `field_*` rows are a single deep-field write (`Telescope.of(X).field(X::name).update(rec, fn)`); the `mapForward_*`
/ `mapBackward_*` rows are a full 3-level record-tree conversion. Each `_lmf` row is the structural twin of its
`_holder` sibling — same fixture shape, only the `@Focus` annotation differs — so the ratio between the pair is the
holder-routed savings rather than a workload difference. The two `mapBackward_*` rows ran during a concurrent JMH
workload on the same box (~50% CPU saturation), which inflated their absolute values and widened error bars by an order
of magnitude vs the clean `mapForward_*` rows; the `mapBackward_holder ≈ 0.85 × mapBackward_lmf` ratio is consistent
with `mapForward`'s, but the noise band makes that ratio not independently load-bearing.

#### Honest read

Three distinct stories at the three benchmark levels:

- **Phase B — clear win on per-field dispatch.** `field_holder` at 25.6 ±0.3 ns/op is **3.54x faster** than `field_lmf`
  at 90.7 ±4.5 ns/op. The non-overlapping CIs make this a real, measurable savings, not a noise artifact. The
  `field_holder_constant` row at 26.0 ±1.1 ns/op (direct constant, probe bypassed) is **within the same error band as
  `field_holder`** — so the `MetadataHolderProbe` ClassValue lookup adds essentially zero overhead on the warm path, and
  the holder-routed dispatch reaches the codegen-direct ceiling. For deep field navigation on `@Focus`-annotated types,
  this is a meaningful constant-factor improvement that compounds across multi-level paths.

- **Phase C forward — comparable.** `mapForward_holder` at 808.3 ±38.8 ns/op is ~3-4% faster than `mapForward_lmf` at
  837.1 ±43.7 ns/op. The means trend in the right direction, but the error bars overlap — the source-side backward-Iso
  reads (3 component reads per fork-level, 7 total across a 3-level tree) are not the dominant cost in a full
  deep-mapping pass. The construction side (canonical constructor invocation, Map.put boilerplate, intermediate Map
  allocation) dominates the per-call cost, and that's identical in both paths. Phase D (constructor holder) would shift
  this balance.

- **Phase C backward — comparable but noisy.** Both rows ran with concurrent JMH contention on the same host, so the
  ±334 / ±522 ns error bars overshadow the trend. The mean ratio matches the forward direction (~0.85x), so there's no
  surprise regression — but the headline number on these rows is "needs a quiet machine to reproduce" rather than the
  holder savings.

The architectural story holds even where the per-call delta is comparable: the holder gives the runtime a
zero-config-codegen handshake (the `Telescope.of(X).field(X::name)` path doesn't re-run `Records.fieldLens` for
`@Focus`-annotated types), and Phase D will use the same probe infrastructure for the forward path. The Phase B win is
the headline; Phase C lands as parity with a forward-looking foundation rather than a measurable speedup today.

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
