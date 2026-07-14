# Telescope vs MapStruct — Head-to-Head Performance Analysis

Goal: measure telescope's codegen path against MapStruct's compile-time-generated output, identify any real overhead,
and propose remediations where the gap is structural.

## Headline finding

**Telescope codegen is at effective MapStruct parity: 1.04× (nested) to 1.19× (deep) forward, with flat and nested
inside MapStruct's own error band.** The latest CI-reproducible run (GitHub Actions, 3 warmup + 5 measurement × 3s, 1
fork) lands flat at 1.07×, nested at 1.04×, deep at 1.19× forward — every tier a near-tie. The residual on the deep tier
is **generated-body work, not dispatch**: the zero-dispatch `static forward` floor is itself 1.18× on deep, so removing
the lattice wrapper cannot close it.

Two dispatch-shape claims from earlier revisions of this doc were **measured and refuted** (see the results table and
the "dispatch is free" section):

- The `BRIDGE_FN` one-interface-hop constant gives **no measurable win** over the full-lattice `BRIDGE.read` — the JIT
  already inlines the whole `BRIDGE.read → Iso → Fn.forward → static forward` chain. The "~0.3–0.7 ns lattice slice" a
  prior run reported has vanished on current hardware/JDK.
- The proposed type-specialized bridge subclass (remediation #2 below) targets a wrapper tax that is already ~zero:
  `static forward` (no wrapper at all) is within 0.3 ns of `BRIDGE.read` on every tier. **It is not worth building.**

Earlier smoke-confidence laptop runs reported a 2.9–3.6× forward gap, a "telescope-faster-on-backward" inversion, and a
"static-slower-than-lattice" inversion — all three were JMH noise artifacts that clean CI hardware dissolved. The
runtime path is a different conversation (~8–20× — convenience surface, not the hot-path lane).

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

## Results — CI-reproducible run (GitHub Actions `ubuntu-latest`, 3W + 5I × 3s @ 1 fork)

These are the canonical numbers, produced by the manual `Benchmarks` workflow on a dedicated runner. Error bands are
tight because there's no competing workload — far cleaner than a laptop. Re-run the workflow on any branch to reproduce.
This run adds the `BRIDGE_FN` one-interface-hop column across all three tiers (earlier revisions measured it on flat
only), which is what refutes the two dispatch-shape claims in the headline.

**Forward (bean → record), all four call shapes measured on the same run:**

| Tier   |     MapStruct | codegen `BRIDGE.read` (lattice) | codegen `BRIDGE_FN` (1 hop) | codegen `static forward` (0 hop) | codegen/MapStruct |
| ------ | ------------: | ------------------------------: | --------------------------: | -------------------------------: | ----------------: |
| flat   | 2.527 ± 0.159 |                   2.692 ± 0.099 |               2.629 ± 0.294 |                    2.544 ± 0.077 |             1.07× |
| nested | 4.506 ± 0.366 |                   4.699 ± 0.146 |               4.722 ± 0.331 |                    4.503 ± 0.144 |             1.04× |
| deep   | 40.29 ± 0.801 |                   47.72 ± 1.503 |               48.41 ± 4.023 |                    47.40 ± 3.153 |             1.19× |

**Backward (record → bean), MapStruct vs codegen `BRIDGE.set`:**

| Tier   |     MapStruct | codegen `BRIDGE.set` | codegen/MapStruct |
| ------ | ------------: | -------------------: | ----------------: |
| flat   | 2.708 ± 0.167 |        2.569 ± 0.093 |             0.95× |
| nested | 5.079 ± 0.468 |        5.023 ± 1.219 |             0.99× |
| deep   | 42.11 ± 1.575 |        49.54 ± 1.312 |             1.18× |

Read across the forward table row by row: `BRIDGE_FN` (one hop) and `BRIDGE.read` (full lattice) differ by ≤0.7 ns on
every tier — inside the error bands, and on flat `BRIDGE_FN` is even fractionally _faster_. And `static forward` (zero
dispatch) sits within 0.3 ns of `BRIDGE.read` everywhere. Dispatch is free; what's left on deep is the generated body.

Runtime path forward: flat 40.14, nested 64.37, deep 315.23 ns/op — ~16× / ~14× / ~8× of MapStruct. The backward
direction stays higher (flat 108.31, nested 148.63, deep 626.39) because building a bean (allocate + N setters) is
structurally heavier than a record's canonical-ctor invoke and its read side was already optimal. The runtime path goes
through a structural-Iso build per `Telescope.mapper(...)` call site and a reflective dispatch chain on every
invocation; it's not in the dethrone-MapStruct lane, it's the convenience surface for "I don't want to write codegen for
this one mapper".

## Dispatch is free — the lattice slice is JIT-inlined away

The `*_codegen_static_forward` (zero dispatch), `*_bridgefn_forward` (one interface hop), and `*_codegen_forward`
(`BRIDGE.read`, full lattice) benchmarks isolate the dispatch cost by call shape. On the latest CI run they are
**indistinguishable within error** on every tier:

| Tier   | `static forward` (0 hop) | `BRIDGE_FN` (1 hop) | `BRIDGE.read` (lattice) |  spread |
| ------ | -----------------------: | ------------------: | ----------------------: | ------: |
| flat   |                    2.544 |               2.629 |                   2.692 | 0.15 ns |
| nested |                    4.503 |               4.722 |                   4.699 | 0.22 ns |
| deep   |                    47.40 |               48.41 |                   47.72 | 1.01 ns |

The lattice path walks `Telescope.read → BridgeTelescope.read → BridgeFn.forward → Fn.forward → static forward → ctor`;
MapStruct walks `INSTANCE.toRec → ctor`. Those extra hops cost **nothing measurable** — the JIT inlines the entire
chain, because every hop is monomorphic (one concrete `Fn` per bridge, one `BridgeTelescope` shape). The spread across
all three call shapes is smaller than MapStruct's own run-to-run error band.

An earlier run reported a ~0.3–0.7 ns "lattice slice" and proposed closing it by emitting a directly-callable
`BRIDGE_FN` constant (see below). `BRIDGE_FN` shipped — and this measurement shows it makes no difference, because there
was no slice left to close once the JIT warmed up. The one useful thing `BRIDGE_FN` still provides is a _passable mapper
value_ (not a static method) for adopters who want to hand a bridge around without touching the lattice; it just isn't
faster.

The lesson stands: **smoke runs lie.** A 3-iteration × 2s laptop run with error bars exceeding the mean once produced a
2.9–3.6× "forward gap", a "telescope-faster-on-backward" claim, AND a "static-slower-than-lattice" inversion — all three
wrong. Dedicated CI hardware (no competing workload) is what you trust, and it says: dispatch is free.

## So is there a real gap?

**A small one, only on deep: 1.19× forward, and it is not dispatch.** Flat (1.07×) and nested (1.04×) are inside
MapStruct's own error band — call them a tie. The deep residual is ~7 ns, and the zero-dispatch `static forward` floor
is _also_ 1.18× — so the gap is the generated **body**, not the wrapper: six leaf conversions, two list allocations, and
per-field null-guards vs MapStruct's directly-inlined field sequence. Whether it matters to an adopter:

- At <10M ops/sec on a hot mapper: invisible against application work.
- At >100M ops/sec on the deep tier: a ~7 ns per-call structural cost — measurable on a flame graph, rarely dominant.
- On flat/nested (the common shape): no gap to speak of.

## Remediations — status

### 1. Codegen emits a typed `BridgeFn<S, T>` constant — **shipped, measured, no perf effect**

`public static final BridgeFn<S, T> BRIDGE_FN = new Fn();` ships per generated bridge (asserted in
`BridgeProcessorTest`). It gives adopters a passable one-hop mapper value instead of a static method. The benchmark
table above shows it measures **at parity with `BRIDGE.read`** — the JIT already inlined the lattice, so there was no
slice to recover. Kept for the ergonomic value (a `BridgeFn` you can pass around), not for speed.

### 2. Type-specialized bridge subclass whose `read(S)` is the inlined body — **measured and declined**

The idea was to emit a `Telescope`/bridge subclass that removes the `BridgeFn` field and the `invokeinterface` wrapper
so `read(S)` _is_ the generated body. The data kills the premise: `static forward` (no wrapper at all) is within 0.3 ns
of `BRIDGE.read` on every tier, so there is no wrapper tax to remove. It would add ~100 LOC of `BridgeProcessor`
complexity to chase a difference inside the noise band. **Not building it.** The only thing that would move the deep
number is matching MapStruct's generated _body_ (fewer null-guards, inlined leaf conversions) — a separate, finer
optimization, adopter-gated on someone actually hitting the deep tier at >100M ops/sec.

### 3. The CI-reproducible matrix is the baseline

The manual `Benchmarks` workflow produces the full matrix on dedicated hardware with tight error bands. Future PRs
trigger it on their branch and baseline-diff against a prior run's artifact. `-prof gc` is wired as a `profilers` input
(`gc`, `stack`, `perfasm`; locally `-Pjmh.profilers=gc`) for decomposing call cost vs allocation when chasing a
residual.

## What this revision ships

- **`BRIDGE_FN` benchmarked across all three tiers** (`nested_*_bridgefn_forward`, `deep_*_bridgefn_forward`; flat
  already existed). This is what lets the forward table compare all four call shapes — static, one-hop, lattice,
  MapStruct — on a single run and prove dispatch is free.
- **This analysis doc, corrected.** Prior revisions claimed a ~0.3–0.7 ns lattice slice and proposed two remediations to
  close it; the measured data refutes both. The doc now records the parity result, the JMH-artifact gotcha, and the
  measured-and-declined type-specialized-subclass decision so future-us doesn't re-open it.
- **No production code changed.** `BRIDGE_FN` already ships; the codegen is at parity as-is.

## Bottom line

Telescope codegen is at **effective MapStruct parity: 1.07× flat, 1.04× nested, 1.19× deep forward**, with flat and
nested inside MapStruct's own error band. **Dispatch is free** — `static forward` (zero hop), `BRIDGE_FN` (one hop), and
`BRIDGE.read` (full lattice) are indistinguishable within error on every tier, because the JIT inlines the monomorphic
chain. That kills both proposed dispatch remediations: `BRIDGE_FN` shipped and measures at parity with the lattice (kept
for ergonomics, not speed), and the type-specialized subclass targets a wrapper tax that is already ~zero —
**declined**. The only residual is ~7 ns of generated-body work on the deep tier (`static forward` is 1.18× too), not
dispatch; closing it would mean matching MapStruct's inlined body, adopter-gated on a real deep-tier hot loop. The
2.9–3.6× forward "gap", the "telescope faster on backward" claim, and the "static slower than lattice" inversion from
laptop smoke runs were all JMH noise that dedicated CI hardware dissolved. Measuring the dispatch claim to a conclusion,
and declining the optimization it implied, is the deliverable here.
