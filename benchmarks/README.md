# telescope benchmarks

The `benchmarks` module holds the JMH micro-benchmarks for telescope. They compare telescope's runtime path, its
generated code, hand-written Java, and MapStruct on the same fixtures. The module is not published.

The benchmarks live in the `jmh` source set, which depends on `:core` and `:spring-boot-starter`. The source set runs
the `:codegen` and MapStruct 1.6.3 annotation processors, so the benchmarks measure the navigators, bridges and Spring
beans those processors emit. The `check` task depends on `jmhClasses`, so every build compiles the benchmarks without
running them. A benchmark that no longer matches the code it measures fails the build instead of failing later in a
manual run.

## Running the benchmarks

Numbers you cite should come from the `Benchmarks` GitHub Actions workflow and not from a laptop. A developer machine
with other work in the background gives numbers that change from run to run, and two CI runs can land on runners of
different speed. Most benchmark classes therefore have a control row, such as a hand-written loop, an untouched path, or
MapStruct's own row. If the control moved between two runs, the runners differ in speed. In that case, divide each ratio
by the control's change, or run again. Allocation per call, from the `gc` profiler, doesn't depend on runner speed, so
it is the steadier evidence.

### Running in CI

The [`Benchmarks`](../.github/workflows/benchmarks.yaml) workflow runs only when you start it from the Actions tab. It
takes these inputs:

| Input                    | Default                        | Meaning                                                      |
| ------------------------ | ------------------------------ | ------------------------------------------------------------ |
| `benchmark_filter`       | `MapStructComparisonBenchmark` | JMH regular expression over class or method names            |
| `warmup_iterations`      | `3`                            | Warmup iterations                                            |
| `measurement_iterations` | `5`                            | Measured iterations                                          |
| `time_on_iteration`      | `3s`                           | Time per measured iteration                                  |
| `warmup_time`            | `3s`                           | Time per warmup iteration                                    |
| `forks`                  | `1`                            | Forked JVMs per benchmark                                    |
| `profilers`              | empty                          | JMH profilers, comma-separated, for example `gc` or `stack`  |
| `jvm_args`               | empty                          | Flags for every forked JVM, for example `-XX:+PrintInlining` |

The job runs on `ubuntu-latest` with JDK 25. It prints `results.txt` in the job summary and uploads
`benchmarks/build/results/jmh/` as an artifact named `jmh-results-<sha>-<run-id>`, which it keeps for 30 days. The sha
and run id in the name keep consecutive runs apart, so a later change can download a known run as its baseline.

### Running locally

Use a local run to check that a benchmark works, and use the workflow to measure it. The plain command runs every
benchmark:

```
./gradlew :benchmarks:jmh
```

The project properties below map to the JMH settings in `benchmarks/build.gradle.kts`:

| Property                | Default     | JMH setting                                 |
| ----------------------- | ----------- | ------------------------------------------- |
| `-Pjmh.includes`        | every class | benchmark filter                            |
| `-Pjmh.warmup`          | `3`         | warmup iterations                           |
| `-Pjmh.iterations`      | `5`         | measured iterations                         |
| `-Pjmh.fork`            | `1`         | forks                                       |
| `-Pjmh.warmupTime`      | JMH default | time per warmup iteration                   |
| `-Pjmh.timeOnIteration` | JMH default | time per measured iteration                 |
| `-Pjmh.profilers`       | none        | profilers, comma-separated                  |
| `-Pjmh.jvmArgsAppend`   | none        | flags for every forked JVM, space-separated |

Every run uses one thread and reports average time in nanoseconds per operation. Results go to
`benchmarks/build/results/jmh/results.txt`. A short smoke run of one class looks like this:

```
./gradlew :benchmarks:jmh -Pjmh.includes=MatchDispatchBenchmark \
  -Pjmh.warmup=1 -Pjmh.iterations=1 -Pjmh.warmupTime=1s -Pjmh.timeOnIteration=1s
```

## Benchmark classes

The classes build their paths and mappers once, outside the measured loop, so a row measures the cost of using them and
not of building them. The exceptions are `MapperConstructionBenchmark`, which measures building a mapper, and
`NavigatorFluencyBenchmark`, which measures navigating a generated navigator inside the loop.

### Conversion

| Class                          | What it covers                                                                                                                                                                                                                                     |
| ------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `MapStructComparisonBenchmark` | MapStruct against telescope's generated bridge and runtime mapper on flat, nested, deep, Set and Map shapes. See [MapStruct comparison](#mapstruct-comparison).                                                                                    |
| `TelescopeBenchmark`           | A three-level record and bean update by runtime path, by hand-built `Telescope.lens` constants, and by hand. Also runtime conversions record to record, bean to bean, bean to record, bean to a builder or constructor bean, and a `@Bridge` read. |
| `ContainerAllocationBenchmark` | A reused runtime mapper over a container of 0 to 4096 elements, forward and backward, for List, copy-on-write list, Map, Set, sorted Set, and a sorted Set converted to a plain one.                                                               |
| `SameTypedContainerBenchmark`  | A same-typed List, Set or Map component copied by runtime mapper and by generated bridge, against a hand-written share and a hand-written copy.                                                                                                    |
| `RawContainerBenchmark`        | Generated conversion of raw container subtypes across 1 to 4096 elements. Run it with `-Pjmh.profilers=gc`.                                                                                                                                        |
| `CycleGuardBenchmark`          | A self-referencing type converted as a chain and as a ring, by generated bridge and runtime mapper, against a hand-written recursive copy.                                                                                                         |
| `PatchBenchmark`               | `patch` with an all-null partial and with a one-field partial, generated and runtime, against a hand-written copy.                                                                                                                                 |
| `IntoBenchmark`                | `Mapper.into` against `forward` on the same pair, at five and twenty properties.                                                                                                                                                                   |
| `MapperConstructionBenchmark`  | The cost of building a flat and a deep runtime mapper.                                                                                                                                                                                             |
| `MergeBenchmark`               | `Telescope.merge` with explicit rows and with `auto()` rows, against a hand-written merge.                                                                                                                                                         |
| `FromMapBenchmark`             | `Telescope.fromMap` into a record and a bean, and the `@FromMap` generated binder, against hand-written positional binds.                                                                                                                          |
| `HolderDispatchBenchmark`      | Field lookup and runtime mapping on `@Focus` types, which use the generated `<X>FieldOptics` constants, against the same shapes without the annotation.                                                                                            |
| `SpringBlueprintBenchmark`     | A generated Spring `@TelescopeMapper` and `@TelescopeTransformer` bean against the same generated bridge and a cached path called directly.                                                                                                        |

### Navigation and update

| Class                         | What it covers                                                                                                                                      |
| ----------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ContainerWriteBenchmark`     | `.each(...)` updates over a Set and `.eachValue(...)` updates over a Map, across sizes on both sides of each power-of-two band, against hand loops. |
| `MultiEditBenchmark`          | `Telescope.all(over(...))` with one to four edits, through hand-written and generated navigator paths, against hand-fused single rebuilds.          |
| `ReadFoldBenchmark`           | The `toList`, `count`, `exists`, `read` and `find` terminals over 100 elements, against hand loops.                                                 |
| `EffectfulTraversalBenchmark` | `updateOptional` and `updateValidated` over 16 to 1024 elements, against a pure `update`. Per-element cost should stay flat as size grows.          |
| `NavigatorFluencyBenchmark`   | Navigating a generated navigator per call, at one and three hops, against the same path built once.                                                 |
| `FieldByNameBenchmark`        | `fieldByName(...)` reads and writes against the typed `.field(...)` path.                                                                           |
| `ObservationBenchmark`        | `observe` on a path, with a synchronous observer and with a queued one, against the same path unobserved.                                           |
| `MatchDispatchBenchmark`      | `Match` dispatch over a sealed hierarchy against a pattern-matching `switch`.                                                                       |

### Dispatch internals

| Class                             | What it covers                                                                                                                                           |
| --------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `LmfBenchmark`                    | One record component read, bean getter read or bean setter call through the `LambdaMetafactory` accessors, against `Method.invoke` and a direct call.    |
| `MethodHandleChainSpikeBenchmark` | The runtime mapper's composed `MethodHandle` conversion against the same handle built by hand and a direct constructor call. Kept as a regression guard. |
| `ContainerLoopSpikeBenchmark`     | The runtime container loop shapes over a three-element list, against a hand-written loop. Kept as a regression guard.                                    |

## MapStruct comparison

`MapStructComparisonBenchmark` runs MapStruct and telescope on the same fixture instances, so the conversion path is the
only difference between rows of one tier. It has five shapes:

- flat, a bean of five scalar fields converted to a record
- nested, two scalars and one nested `Address`
- deep, three levels with two `List` hops, `Company` to `List<Department>` to `List<Team>`
- Set and Map, a component holding 100 elements, which is past the size where a hash table built from an element count
  has to resize

Forward converts the bean to the record, and backward converts the record to the bean. Telescope is measured through
four call shapes:

| Row suffix                  | Call shape                                          | Tiers                                   |
| --------------------------- | --------------------------------------------------- | --------------------------------------- |
| `_telescope_codegen_*`      | `<Source>Bridge.BRIDGE.read`, and `BRIDGE.set` back | every tier                              |
| `_codegen_bridgefn_forward` | `<Source>Bridge.BRIDGE_FN.forward`                  | flat, nested, deep                      |
| `_codegen_static_forward`   | static `<Source>Bridge.forward`                     | flat, nested, deep                      |
| `_telescope_runtime_*`      | `Telescope.mapper(...)`, `forward` and `backward`   | every tier, forward only on Set and Map |

The `_mapstruct_*` rows call the MapStruct mapper `INSTANCE`, and they are the control for the run.

The current figures come from Run 9, GitHub Actions run 38008688244 on `main`, with 4 forks of 8 measured iterations and
the `gc` profiler. Each figure is telescope's time divided by MapStruct's time on that run, so a figure below 1 means
telescope was faster. Where the error bands of the two rows overlap, the table gives a range.

| Tier   | forward, `BRIDGE_FN` | forward, `BRIDGE.read` | backward, `BRIDGE.set` | forward, runtime mapper | bytes per call, generated and MapStruct |
| ------ | -------------------- | ---------------------- | ---------------------- | ----------------------- | --------------------------------------- |
| flat   | 1.00 to 1.01         | 1.08                   | 1.05                   | 3.36                    | 32                                      |
| nested | 1.02                 | 1.06                   | 0.87                   | 2.69                    | 48                                      |
| deep   | 0.99                 | 0.99 to 1.00           | 0.96                   | 1.28                    | 376                                     |
| Map    | not measured         | 0.99 to 1.11           | 1.09                   | 1.15                    | 7,528                                   |
| Set    | not measured         | 1.05                   | 0.97                   | 1.11                    | 7,576 forward, 7,544 backward           |

Telescope's generated rows split across forks on deep backward, on both Map rows, and on Set forward. The per-fork
figures, the runtime backward ratios, the earlier runs and the methodology are in
[`docs/perf-mapstruct-comparison.md`](../docs/perf-mapstruct-comparison.md#run-9-2026-10-09-after-the-generated-mapper-converts-constructor-arguments-first).
The `ContainerAllocationBenchmark` measurements are in
[`docs/perf-runtime-collections.md`](../docs/perf-runtime-collections.md).

## Measuring a change

An A/B comparison needs both sides to run the same benchmark. Follow these steps:

1. Put the benchmark on both branches. If the benchmark is new, cherry-pick it onto a baseline branch cut from `main`.
2. Start the `Benchmarks` workflow on each branch with the same filter and settings. Use 3 or more forks when you plan
   to quote the result.
3. Compare the control rows of the two runs first. If a control moved, the runners differ in speed. Divide every ratio
   by the control's change, or run again.
4. Compare allocation as well as time. Add `gc` to `profilers` for that.

For `ContainerAllocationBenchmark`, `scripts/compare-runtime-benchmarks.py` compares two saved JMH JSON files, and with
`--check` it fails on a timing or allocation regression. The command is in
[`docs/perf-runtime-collections.md`](../docs/perf-runtime-collections.md#reproducing).
