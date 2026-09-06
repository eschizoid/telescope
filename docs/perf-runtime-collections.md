# Runtime collection mapping measurements

This benchmark isolates a reused `Telescope.mapper(...)` runtime mapper. Mapper construction is excluded: each JMH state
creates one mapper in `@Setup`, then measures `forward` and `backward`. The fixture maps a container of `Value(int)` to
`ValueDto(int)` with runtime structural mapping.

The benchmark runs collection cardinalities 0, 1, 16, 256, and 4096 for `List`, `CopyOnWriteArrayList`, `Set`, and
`Map`. It reports average time and allocation per operation. The functional regressions are in
`RuntimeMappingRegressionTest`; they exercise the default fused path, the Java-loop path, and the legacy array leaf.

## Reproducing

```bash
./gradlew :benchmarks:jmh -Pjmh.includes=ContainerAllocationBenchmark \
  -Pjmh.fork=3 -Pjmh.warmup=3 -Pjmh.iterations=5 \
  -Pjmh.warmupTime=500ms -Pjmh.timeOnIteration=500ms \
  -Pjmh.profilers=gc
```

For a change that claims an improvement, save matching JMH JSON before and after, then run:

```bash
python3 scripts/compare-runtime-benchmarks.py before.json after.json --check
```

The comparator rejects differing JMH settings, material timing regressions with non-overlapping 99.9% confidence
intervals, allocation regressions, and less than 5% allocation improvement for container cases of size 16 or more.

## Measured result

Baseline and updated artifacts were measured on an Apple M5 Pro, macOS 27.0, OpenJDK 25.0.3, JMH 1.36,
`-Xms256m -Xmx256m`; every row used three forks, three warmups, five measurements, and 500 ms iterations. The table
shows forward mapping; backward has the same allocation shape.

| Container              |  Size | Before ns/op | After ns/op | Before B/op | After B/op |
| ---------------------- | ----: | -----------: | ----------: | ----------: | ---------: |
| `List`                 |    16 |        65.46 |       55.12 |         536 |        376 |
| `List`                 |   256 |       893.88 |      789.03 |       8,688 |      5,208 |
| `List`                 | 4,096 |    14,745.29 |   12,728.47 |     115,824 |     82,008 |
| `CopyOnWriteArrayList` |    16 |       150.41 |       61.39 |       1,144 |        496 |
| `CopyOnWriteArrayList` |   256 |     4,443.70 |      792.34 |     140,368 |      6,280 |
| `CopyOnWriteArrayList` | 4,096 | 1,170,473.53 |   12,655.12 |  33,701,969 |     98,440 |
| `Map`                  | 4,096 |    40,030.68 |   31,404.33 |     262,336 |    229,488 |
| `Set`                  | 4,096 |    37,893.88 |   30,352.16 |     295,136 |    262,288 |

Empty and singleton copy-on-write lists retain the previous allocation shape (56 B/op and 96 B/op) and statistically
indistinguishable timing. The large copy-on-write result comes from mapping into a temporary pre-sized collection and
performing one bulk copy into the final copy-on-write container, rather than copying its backing array after every
`add`.

Treat the exact nanoseconds as machine-specific. The allocation reduction and the quadratic copy-on-write behavior are
the durable result; rerun the command above on the target JVM and hardware before publishing a performance claim.
