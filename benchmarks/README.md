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
