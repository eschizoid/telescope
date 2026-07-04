# ADR-0013: Introspectable optics via `explain()` and `trace()`

**Status:** Accepted (decision set resolved in design review, 2026-07-04) · **Date:** 2026-07-04

## Context

A telescope optic already reasons about what it does — and throws the reasoning away.

At mapper construction, `Telescope.mapper(A, B, rows…)` routes through `DeepMap.computeAutoIso`, which for each field
pair calls `PairingRules.decidePair(srcType, tgtType, componentName)`, receives a sealed `PairDecision` (`Identity` /
`PrimitiveWrapper` / `RecursePair` / `OptionalToNullable` / `LiftContainer` / `Incompatible` / …), uses it to select
which `Iso` to build, and discards the decision. `PairingRules.matchFields` computes the `matched` / `unmatchedTargets`
/ `unmatchedSources` name sets and discards those too. The ADR-0012 verifier runs the _same_ `decidePair` walk over the
call site to raise diagnostics — and also discards the intermediate decisions once it has emitted its errors.

Navigation has a parallel gap. A telescope built by `.field(…)` / `.each(…)` / `.whenPresent(…)` composes its steps into
one fused `Traversal<S, A>` and keeps only `firstHopName` — the method name of the _first_ hop, one slot, retained
solely so `DeepMap` can route nested-telescope rows. The rest of the path structure is fused away.

So both worlds discard the structure they compute. There is no way to ask a mapper "what do you map, and why did you
skip what you skipped," and no way to ask a navigator "what path do you take." The optic is a black box between its
build and its result. The comparison target's introspection story is "read the generated `.java`" — real code, but not a
queryable structure you can assert on in a test, log from a service, or surface on a diagnostics endpoint, and it does
not state _why_ a target field was left unset.

## Decision

Add two introspection surfaces — a static `explain()` and a data-driven `trace(input)` — to the whole optic surface,
built on one unified node model derived from the structure the optic already computes.

**1. One unified node model.** A **sealed public `OpticNode` family in `:core`** — a public sealed ADT with nested
public record variants (unlike `Mapping`'s package-private impls: here the variants are user-facing, constructed and
asserted on directly, e.g. `new Mapped("firstName", "firstName", "givenName")`). Its variants cover both worlds:

- **Navigation hops** — `Focus(path)`, `Traverse(container)`, `Filter(desc)`, `Narrow(type)`, `Bridge(target)`.
- **Mapping rows** — `Mapped(path, from, to)`, `Transformed(field, fromType, toType)`, `Skipped(field, Reason)` where
  `Reason ∈ { DROPPED, MISSING_SOURCE, UNMAPPED_SOURCE }`.

`DROPPED` is an explicit `Mapping.drop(src)` row; `MISSING_SOURCE` is a target field with no source (an
`unmatchedTargets` entry — lenient paths only, see below); `UNMAPPED_SOURCE` is a source field with no consumer (an
`unmatchedSources` entry). One vocabulary, two shapes of optic.

**2. `explain()` → the static structure.** On every optic-carrying type. Returns the `OpticNode` trail — for a mapper,
the field rows; for a navigator, the hop path. Derived from the **same decision stream / composition the optic itself
uses**, never a parallel re-walk: if `explain()` reasoned independently it could drift and lie; deriving it from the one
structure makes drift impossible (mantra #3, lattice-first). `explain()` is thus a _third lens_ on the same engine,
alongside `DeepMap`'s "decision → `Iso`" and the ADR-0012 verifier's "decision → diagnostic."

**3. `trace(input)` → the structure with values.** The same node vocabulary, executed against a concrete input and
enriched: each node gains its actual `valueIn → valueOut`. `trace` is `explain` with a value column filled in — one
model, two levels of detail. At many-focus nodes (`each`/`eachValue`/`whenPresent`) `trace` **expands into a tree**:
each fan-out node spawns one child sub-trace per element, so you see exactly which element produced which downstream
value. A pure mapping (all 1→1) never fans out, so its trace stays the linear `field → value` shape. Fan-out reuses the
existing `Traversal#getAll` semantics — the foci at each node are what `getAll` returns there. `forward()` / `read()`
stay fused and fast; `trace()` is the separate instrumented walk, off the hot path.

**4. Universal surface.** `explain()` / `trace()` live on the `Mapper` / `ForwardMapper` family (covering `mapper`,
`mapperForward`, `fromMap`) **and** on the general `Telescope<S, A>` (covering `map` and all navigation). On a navigator
they describe the path; on a mapper the field rows; on a bare `Telescope.of(…)` identity, an empty report — never a
throw.

**5. Forward-only.** Both surfaces show the forward view (A→B / S→A). `ForwardMapper` and `fromMap` have no backward, so
forward-only keeps the family uniform, and it matches the "bidirectional is overrated" positioning. `explainBackward()`
/ `traceBackward()` are a deferred later addition for `Mapper` if a real need appears.

**6. Retention: decision stream now, executors lazily.** Each optic retains a compact immutable `List<OpticNode>` trail
— a byproduct of the resolution/composition it already runs, generalizing the existing single-slot `firstHopName` into
the full list (with `firstHopName` becoming the trail's head). `explain()` reads it directly. `trace()` rebuilds the
small per-field/per-hop executors from the retained accessors only when called. One modest immutable list per optic;
nothing extra on the hot path; no re-validation.

**7. `trace()` is capped by default, with an uncapped override.** `trace(input)` materializes one node per focus, so it
caps breadth-per-fan-out and depth with `… (+K more)` truncation markers (a small `TraceLimits` type).
`trace(input, TraceLimits.none())` lifts the caps for the full tree. Safe by default; complete when you ask.

**8. Codegen parity, in the same release.** The `@Focus` / `@BeanFocus` / `@Bridge` processors (and the lombok module)
emit a static `OpticNode`-trail constant into their generated navigators/bridges, so codegen-built optics support
`explain()` / `trace()` with the same public surface as runtime-built ones. The verifier already holds this exact data
at compile time; this stops discarding it there too. Shipping codegen without `explain()` would leave a public-API gap
where the generated path silently lacks a method the runtime path has.

## What it looks like

**Mapping — `explain()` (static structure, no input):**

```java
final Mapper<UserDto, User> mapper = Telescope.mapper(UserDto.class, User.class,
    Mapping.to(UserDto::firstName, User::givenName),
    Mapping.to(UserDto::birthDate, User::birthDate, LocalDate::parse, LocalDate::toString),
    Mapping.drop(UserDto::id));

mapper.explain();
// Mapped:
//   ✓ firstName    → givenName
//   ✓ address.city → city          (nested, dotted path)
// Transformations:
//   • birthDate(String) → LocalDate
// Skipped:
//   • id  (dropped)
```

The report is data first — the text above is `toString()`. You assert on the structure:

```java
// completeness test: a strict mapper skips nothing by construction
assertThat(mapper.explain().skipped()).isEmpty();

assertThat(mapper.explain().mapped())
    .contains(new Mapped("firstName", "firstName", "givenName"));
```

**Mapping — `trace(input)` (same rows, value column filled in):**

```java
mapper.trace(new UserDto("Ada", "2020-01-02", /* id */ 7L));
//   ✓ firstName  "Ada"          → givenName "Ada"
//   • birthDate  "2020-01-02"   → LocalDate[2020-01-02]
//   • id         7              → (dropped)
```

**Lenient / `fromMap` — the gaps show, with reasons:**

```java
Telescope.fromMap(CustomerContact.class, /* rows … */).explain();
// Mapped:
//   ✓ name → name
// Skipped:
//   • region  (missing source)      // no key in the map
//   • legacyId (unmapped source)    // key present, no target
```

**Navigation — `explain()` describes the path:**

```java
Telescope.of(Company.class).each(Company::departments).field(Department::name).explain();
// Traverse: departments (List<Department>)
// Focus:    name
```

**Navigation — `trace(input)` fans out into a tree:**

```java
Telescope.of(Company.class)
    .each(Company::departments).each(Department::teams).field(Team::name)
    .trace(company);
// each departments
//  ├ Sales
//  │  └ each teams
//  │     ├ A → name "A"
//  │     └ B → name "B"
//  └ Eng
//     └ each teams
//        └ C → name "C"
```

**Capped by default, uncapped on request:**

```java
path.trace(companyWith10kDepartments);
//   each departments → [Sales, Eng … (+9998 more)]     // default caps
path.trace(companyWith10kDepartments, TraceLimits.none());
//   … full tree, no truncation
```

## Delivery stages (one PR, ordered commits)

This is a large feature delivered as a **single PR** with the stages below as ordered commits — not a v1/later split and
not four separate PRs. The build order lets each commit stand on its own and be reviewed in sequence within the one PR.

1. **Node model + mapping `explain()`.** The public `OpticNode` sealed family, the report type, and the decision-stream
   retention on `Mapper` / `ForwardMapper`. Runtime mapping introspection (`mapper` / `mapperForward` / `fromMap`).
2. **Navigation `explain()`.** Generalize `firstHopName` → `List<OpticNode>`; instrument every combinator (`field`,
   `each`, `eachValue`, `whenPresent`, `filter`, `as`, `then`) to append its node. `explain()` on `Telescope<S, A>`.
3. **`trace(input)`.** The instrumented execution walk, tree fan-out via `getAll`, `TraceLimits` caps + `none()`
   override — for both mapping and navigation.
4. **Codegen emission.** `Focus` / `BeanFocus` / `Bridge` processors + lombok emit the static trail constant.

## Consequences

- **New public API surface** — the `OpticNode` sealed family, `TraceLimits`, and `explain()` / `trace()` methods.
  Additive; no existing signature changes. Held to the same ergonomics bar as `Mapping` / `Edit`.
- **This is a subsystem, not a harvest.** The original insight ("the data is already computed, just stop discarding it")
  holds for _mapping_ decisions but not for navigation (only `firstHopName` is kept) or codegen (nothing emitted) — so
  most of the scope is net-new instrumentation across every combinator and all processors. Recorded honestly: this is a
  flagship-scale feature delivered in stages, not a one-PR win.
- **A per-build tax on every optic.** Recording a node per combinator step allocates at _build_ time (not on
  `read`/`update`/`forward`), paid once per path construction, by users who may never call `explain()`. The trail must
  be a lightweight immutable structure (cons-list / copy-append), not repeated `ArrayList` copies; the per-step cost
  must be benchmarked to stay negligible against navigation build cost.
- **Zero hot-path cost** — `explain()` is a field read; `trace()` is an explicit off-hot-path debug call; neither
  touches `forward()` / `read()`.
- **A queryable introspection story the comparison target lacks** — assert-in-tests ("this mapper maps exactly these
  fields, skips exactly these"; "strict mapper → `explain().skipped()` is empty" as a completeness test),
  log-from-service, surface-on-diagnostics — and the _reason_ for each skip, which generated imperative code does not
  state.
- **Reinforces the ADR-0012 safety narrative** — the verifier says "complete or it won't compile"; `explain()` says "and
  here is exactly what complete means," from the same engine, for a developer-introspection audience.
- **Natural home for the `fromMap` debugging story** — pairs with the deferred `fromMap` verification follow-up.

## Semantic notes settled in review

- **Universal report, semantics reflect what the optic did.** A strict `mapper(...)` cannot have `MISSING_SOURCE` rows —
  an unmatched target is a _hard construction error_ (the strict bijection guard throws; `DeepMap` ~L113/555/601), so
  its `Skipped` set is provably empty, which is itself a useful assertion. `MISSING_SOURCE` therefore only appears on
  the lenient paths (`mapperForward`, nested pairs, `fromMap`), and on a strict top-level mapper the moment it carries a
  `constant` / `compute` row or a nested pair (which flip resolution lenient; `DeepMap` ~L535/596). `explain()`
  describes whatever the mapper actually did, uniformly, without special-casing.
- **`explain()` static vs `trace()` expanded.** Without input, fan-out points are single `Traverse` nodes;
  `trace(input)` is the only surface that expands them into per-element subtrees.
- **Non-mapping / non-navigation optics** return an empty report, never throw.

## Alternatives considered

- **`toString()` on `Mapper` / `Telescope`.** Too coarse and string-only — not iterable, filterable, or assertable, no
  reason codes. The rendered text is a _view_ of a structured report, not the interface.
- **Log the decisions during construction.** Ephemeral side-channel; not assertable in a test or queryable from a
  service. The value is a returnable structure.
- **Compile-time report artifact only** (emit a `mapping-report.txt`, no runtime API). Misses runtime-built and
  `fromMap` mappers and the test-assertion use case, which is where the queryable structure earns most.
- **Mapper-only, forward-only, runtime-only (the conservative cut).** The tightest feature — surface the discarded
  `DeepMap` decisions on `Mapper` and stop. Rejected in review in favor of the universal optic-introspection surface:
  the reach across navigation and codegen makes it a headline differentiator rather than a mapper footnote, at the cost
  of being a genuine subsystem (recorded in Consequences).
- **Both-directions report.** Considered and rejected — meaningless for `ForwardMapper` / `fromMap` (no backward), would
  double the report model for the one type with a reverse, and cuts against the "demote bidirectional" positioning.
  Deferred as an opt-in `explainBackward()` on `Mapper` if demanded.
- **Do nothing — "read the generated code."** Concedes the introspection gap on a feature where telescope already
  computes the answer and merely discards it.
