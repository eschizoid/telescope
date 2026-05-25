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
| `mapBeanForwardRead`       | `Telescope.mapBean(...).build().read(...)` POJO→POJO conversion.                                     |
| `fromBeanForwardRead`      | `Telescope.fromBean(...).viaFields().read(...)` POJO→record bridge.                                  |

## Results

Numbers are machine-specific; reproduce with `./gradlew :benchmarks:jmh`. The ratios between rows matter more than the
absolute values. A local run (JDK 25, Apple Silicon, 1 fork, 3 warmup + 5 measurement iterations — directional, not
publication-grade) gave:

| Benchmark                  | ns/op | ±error |         vs hand-copy |
| -------------------------- | ----: | -----: | -------------------: |
| `handRolledBeanCopyUpdate` |  24.5 |   ±5.9 | 1.0x (bean baseline) |
| `handRolledCopyUpdate`     |  30.4 |   ±4.5 |      record baseline |
| `lensConstantUpdate`       |  51.5 |   ±4.5 |                 2.1x |
| `mapperForwardRead`        | 112.2 |  ±10.8 |        record→record |
| `fromBeanForwardRead`      | 123.0 |  ±35.3 |                 5.0x |
| `mapBeanForwardRead`       | 170.0 | ±256.7 |    6.9x (very noisy) |
| `reflectionFieldUpdate`    | 242.6 |   ±3.1 |                 9.9x |
| `ofBeanFieldUpdate`        | 441.8 |  ±34.0 |                18.0x |

Both deep-field benchmarks walk three levels — divide by three for per-level cost: record reflection ≈81 ns/level, the
`lens` path ≈17 ns/level, native `ofBean` ≈147 ns/level.

Takeaways:

- **Conversion is reasonable.** The POJO→record bridge (`fromBean`, ~123 ns) lands right next to the record→record
  mapper (~112 ns); `mapBean` (~170 ns, noisy) costs a little more because it builds a POJO via setters rather than a
  single record constructor. These are ordinary-feature territory.
- **Native POJO navigation (`ofBean`) is the expensive path — ~442 ns, ~18x a hand-written bean copy and ~1.8x record
  reflection.** It rebuilds the whole POJO at _every_ level and re-reads all getters per level to carry untouched
  properties over. Still sub-microsecond (~2M ops/sec single-threaded), so fine for ordinary use — but for a hot loop,
  bridge once to a record with `fromBean` and navigate the record (or use `@Focus` codegen) rather than rebuilding the
  bean at each level.
- The reflection-free `lens` path (~52 ns) — what `@Focus` codegen emits — is ~5x faster than record reflection and ~2x
  a hand-copy. That's the codegen payoff.
