# Changelog

All notable changes to telescope are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), versioning follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

This is the inaugural CHANGELOG. Earlier versions (0.1.0 – 0.3.0) were tagged before the file existed; the entries below
are reconstructed from the git history so downstream consumers picking up 1.0 have a single document for the upgrade
story.

## [Unreleased]

_Nothing yet — see [0.4.1] for the most recent release._

## [0.4.1] — 2026-06-08

The first post-1.0-readiness patch. Surfaces four real-world bugs that the new
[`examples-springboot`](examples-springboot/) demo project flushed out, plus the demo itself as a permanent feature
showcase. All changes are additive — no breaking changes vs 0.4.0.

### Added

- **Cross-paradigm `Optional` ↔ nullable bridge in deep mapping.** When one side of a `(sourceClass, targetClass)` pair
  declares an `Optional<X>` component and the other declares a plain (possibly `null`) `X`, `DeepMap.autoIso` now wires
  the bridge automatically via the new `Iso.liftOptionalToNullable(...)` lattice helper. Common JPA case: the API record
  uses `Optional<Address>` while the entity uses a nullable `AddressEmbeddable`. Previously the resolver threw
  "incompatible source/target shapes". Lattice-pure — the bridge is one new `Iso` factory routed through the existing
  `.then(...)` composition.
- **`Mapping.via()` auto-lifts an element-level `Mapper<E, F>` through container shapes.** When the source/target
  accessors return same-kind containers (`List<E>` ↔ `List<F>` / `Set` / `Optional` / `Map<K, V>` values) and the
  user-supplied `Mapper`'s source/target classes match the element types, the engine lifts the mapper through the
  matching container via `Iso.liftList` / `liftSet` / `liftOptional` / `liftMapValues`. One row,
  `via(Parent::children, Parent::getChildren, childMapper)`, instead of building a separate `Mapper<List<E>, List<F>>`
  or hoisting every child-level row up to the parent's slot.
- **`WriteHint.writeBeans(WriteStrategy)` default writer.** Single-row default applied to every bean target the
  recursion touches that lacks a more specific `writeBean(Class, WriteStrategy)` override. Collapses the common
  "pin SETTERS across every JPA entity" enumeration from N rows to one. Per-class hints still win.
- **`Mapper#sourceClass()` / `Mapper#targetClass()` accessors** — expose the keying classes so the deep-mapping engine
  can detect when a user-supplied `Mapper` is element-level vs accessor-level and auto-lift accordingly.
- **`Mapper#liftList()` / `liftSet()` / `liftOptional()` / `liftMapValues()`** — promote an element-level `Mapper<A, B>`
  to a container-level mapper without going through a `via(...)` row. Useful when the lifted mapper is the call-site
  root (e.g., a bulk `List<Order> → List<OrderEntity>` HTTP handler).
- **`examples-springboot/`** — Spring Boot 4.0.1 + Spring Framework 7 + Hibernate 7 + Jakarta EE 10 + Jackson + H2 demo
  project. Two controllers (`RuntimeOrderController`, `CodegenOrderController`) exercise every public Mapping / Mapper /
  Telescope API surface end-to-end through a real REST + JPA pipeline. Composite-build wired so iteration on the
  telescope library shows up immediately in the demo. Tests boot embedded Tomcat on a random port and drive HTTP
  through Spring 7's `RestClient`.

### Fixed

- **`BridgeProcessor` cross-package visibility.** Generated `<X>Path<R>` and `<X><Comp>Step<R>` constructors now emit
  `public` instead of package-private. Bridge hops (`<Source>Path.as<Target>()` calling `new <Target>Path<>(...)`) and
  any mid-chain navigator instantiation that crosses packages no longer fail to compile. Also enables a new pattern:
  wrap a hand-composed `Telescope<R, X>` into the typed `<X>Path<R>` navigator via the public ctor — useful for
  threading a bridged Telescope (e.g., one that crossed paradigms via `mapper.asTelescope()`) back into a typed path
  chain. See `CodegenOrderController.applyDiscount` in the demo for the worked example.

### Changed

- **`Mapping#via(Accessor<A, ?>, Accessor<B, ?>, Mapper<?, ?>)`** — relaxed signature replacing the prior
  `via(Accessor<A, X>, Accessor<B, X>, Mapper<X, X>)` and the proposed per-shape variants (`viaList`, `viaSet`, etc.).
  The same row carries either an accessor-typed `Mapper<List<E>, List<F>>` or an element-typed `Mapper<E, F>`;
  `DeepMap` detects which based on the accessor's field type at row resolution and lifts as needed. No call-site changes
  for existing code (the API is wider, not narrower).
- **`Reflective.beansWithHints(Map, Function<Class<?>, Beans.BeanWriter<?>>)`** — signature widened to take a
  default-writer factory function in addition to the per-class hint map. Internal seam consumed only by `DeepMap`.

## [0.4.0] — 2026-06-07

### Added

- **Hybrid codegen ↔ runtime lookup substrate (ADR-0006), Phase A.** `FocusProcessor` and `BeanFocusProcessor` emit a
  sibling `<X>Telescope` metadata holder alongside the existing `<X>Path<R>` navigator. Each holder exposes one
  `public static final Telescope<X, FieldType>` constant per record component / bean property. Pure additive codegen
  output; no runtime consumer in Phase A. See
  [ADR-0006](docs/adr/0006-codegen-runtime-1-1-lookup-via-metadata-holder.md).
- **Phase B.** `ClassValue<Optional<HolderRef>>` short-circuit in front of `Records.fieldLens(...)` / `Beans.lens(...)`.
  When the holder is present on the classpath, the SerializedLambda-derived field name routes to the pre-baked constant
  directly; when absent, the LMF substrate path runs unchanged.
- **Phase C.** `Reflective#structuralIso(cls)` — the engine behind `Telescope.map(...)` / `Telescope.mapper(...)` —
  probes for a sibling `<X>Telescope` holder per side. When the holder covers every component, every per-component read
  during instance decomposition routes through the pre-baked `Lens` constants instead of `Records.read` /
  `Beans.readProperty`. Holder-absent types and partial holders fall through to the reflective path unchanged.
- **Phase D.** `<X>Telescope` holders gain a `public static <X> construct(Function<String, Object> values)` method that
  mirrors the same write strategy the `<X>Path<R>` lens setters chose (canonical constructor for records; builder chain
  or no-arg ctor + setters for beans). `MetadataHolderProbe.HolderRef` adds an optional
  `Function<Function<String, Object>, Object> constructor` field bound once via `LambdaMetafactory` at probe time.
  `Reflective#structuralIso(cls)`'s forward branch (`Map → instance`) routes through it when present, bypassing the
  reflective `Records.construct` / `Beans.BeanWriter` path. Legacy holders without a Phase D `construct` method degrade
  gracefully (the `constructor` field is `null` and the reflective fallback runs). Combined with Phase C, both
  directions of `structuralIso` are reflection-free for annotated type pairs; the only remaining runtime reflection is
  `SerializedLambda` decode on user accessor method references.
- **Phase E.** `<X>Telescope` holders gain a `public static Map<String, Telescope<?, ?>> constants()` method returning
  the name → lens map directly. `MetadataHolderProbe.probe(...)` calls this method as the only path; a holder that's
  missing the method (out-of-date codegen on the classpath) trips a precise `IllegalStateException` rather than silently
  falling back. Same posture for the `construct(Function)` method emitted in Phase D — holder presence is a full
  contract, not a probe of independent optional methods. Cuts the cold-path probe from `~3 + N` reflective ops (N =
  holder field count) to `3` ops regardless of N. The probe is already `ClassValue`-cached, so this is a
  one-shot-per-class improvement, not a hot-path change — but it consolidates the holder's runtime contract into two
  named methods (`constants()` + `construct(Function)`) instead of a contract that depends on field-shape conventions.
  Pre-1.0 stance: no legacy fallback for older codegen output; users re-run the processor.
- **JMH `HolderDispatchBenchmark`** — quantifies the actual perf delta from the hybrid dispatch path against the LMF
  substrate baseline. Headline (5 warmup + 10 measurement × 3 fork, JDK 25, Apple Silicon): `field_holder` at 25.3 ±0.3
  ns/op is **3.23× faster** than `field_lmf` at 81.8 ±1.7 ns/op with non-overlapping CIs, and the probe overhead is
  essentially zero (`field_holder_constant` lands within the same error band). Deep-mapping forward rows
  (`mapForward_holder` vs `mapForward_lmf`) post-Phase-D measure at **1.21× faster** (542.2 vs 655.9 ns/op,
  non-overlapping CIs); backward rows at **1.26× faster** (536.4 vs 677.3 ns/op). See `benchmarks/README.md` "Hybrid
  dispatch" section.
- **JMH `_methodInvoke` rows** in `LmfBenchmark` — apples-to-apples reflection baselines
  (`recordComponentRead_methodInvoke`, `beanGetterRead_methodInvoke`, `beanSetterDispatch_methodInvoke`) measured at
  5+10×3 fork. Result: LMF and `Method.invoke` are roughly comparable per single-step dispatch (sometimes
  `Method.invoke` is even a touch faster on bean accessors). The LMF substrate win is **structural** — per-call
  `Object[]` arg allocation elimination, removed access-check, JIT inlining through composed lens chains — **not**
  per-call on a trivial accessor. Honest framing added to `benchmarks/README.md`.

### Changed

- Internal: holder-aware dispatch sites prepared in `Records` / `Beans` ahead of Phase B wiring.
- **`Telescope.asList` / `.asSet` / `.asMap` / `.asOptional` no longer short-circuit on `instanceof`.** The four typed-
  container promotion methods previously had `if (path instanceof ListPath<?, ?> lp) return (ListPath<S, X>) lp;`
  branches that saved an allocation when the caller already held a typed subclass. The branches required
  `@SuppressWarnings({"unchecked", "exports", "CastConflictsWithInstanceof"})` because of the wildcard projection; in
  practice they almost never fired (`.list(getter)` returns `ListPath` directly; `asList(...)` callers are promoting
  fresh `Telescope<S, List<X>>` builds that need the allocation regardless). Dropped — each method now shrinks to a
  single `@SuppressWarnings("exports")`. Behaviour-equivalent; observable difference is only instance identity on the
  rare already-typed-subclass case.
- **Examples module reshaped from a single `Main.java` orchestrator to per-demo Gradle tasks.** Each demo class
  (`CodegenDemo`, `DeepMappingDemo`, etc.) carries its own `static void main()`; `examples/build.gradle.kts` registers
  one `JavaExec` task per demo plus an aggregator `runAllDemos`. `:examples:runAllDemos` replaces `:examples:run` as the
  smoke-test entry point; targeted re-runs via `:examples:run<Demo>` (e.g. `:examples:runCodegenDemo`). The
  `application` Gradle plugin is no longer needed and removed.

## [1.0.0] — Unreleased

The 1.0 line freezes the public DSL surface and completes the LambdaMetafactory substrate swap (ADR-0005). The
multi-edit and deep-mapping factories converge on `Telescope.all(Edit<S>...)` and
`Telescope.map(Class<A>, Class<B>, Mapping...)`; every fluent builder that briefly existed on the road to 1.0 is gone.
Two instance methods that collided with sibling APIs were renamed.

### Added

- **Unified deep-mapping factory** — `Telescope.map(Class<A>, Class<B>, Mapping<?, ?>...)` and the sibling
  `Telescope.mapper(Class<A>, Class<B>, Mapping<?, ?>...)` handle every kind of bidirectional mapping in one entry
  point: record↔record, record↔POJO, POJO↔POJO, with same-name auto-mapping by recursion, explicit field overrides,
  typed transforms, and nested per-pair `Mapper` reuse. Rows are built with the static factories on `Mapping`:
  `Mapping.to(srcAcc, tgtAcc)`, `Mapping.to(srcAcc, tgtAcc, fwd, bwd)`, `Mapping.via(srcAcc, tgtAcc, mapper)`. PR #6.
- **`writeBean(Class, WriteStrategy)` hint rows** — passed to `Telescope.map` / `Telescope.mapper` to force a specific
  bean construction strategy (`BUILDER` / `SETTERS` / `FIELDS` / `CONSTRUCTOR`), overriding `Beans.autoWriter` for the
  keyed class. Hints are validated eagerly at resolve time (duplicate, record-class, incompatible-strategy, and
  unused-hint cases all throw at `Telescope.map(...)` call time). PR #7.
- **`Beans.autoWriter` 4th rung** — when no builder, no no-arg constructor, and no setters exist, fall back to a single
  public all-args constructor when there's exactly one matching arity AND the class was compiled with `-parameters`.
  Refuses positional fallback to avoid silent-data-shuffle. PR #7.
- **Typed container subclasses on `Telescope<S, A>`** — sealed permits `ListPath<S, X>`, `SetPath<S, X>`,
  `MapPath<S, K, V>`, `OptionalPath<S, X>`. Instance navigation now lands on the typed subclass:
  `.list(Accessor<A, List<X>>)`, `.setField(Accessor<A, Set<X>>)`, `.mapField(Accessor<A, Map<K, V>>)`,
  `.optional(Accessor<A, Optional<X>>)`. Each subclass exposes a compile-checked typed terminal (`.each()`, `.values()`,
  `.present()`) that descends via pure lattice composition — no runtime container dispatch. Static factories
  `Telescope.asList` / `.asSet` / `.asMap` / `.asOptional` re-wrap pre-built paths. PR #8.
- **Declarative multi-edit factory** — `Telescope.all(Edit<S>...)` with `Edit.over(Telescope<S, X>, Function<X, X>)`
  rows. Each `over(...)` lives on its own argument line; the count is visible at a glance; the edits fold into the
  existing `chain: Function<S, S>` accumulator that powers `.with(...)`. Recommended over the back-to-back fluent
  multi-path chain. PR #4.
- **Declarative multi-row mapping factory** — `Telescope.map(Mapping<A, B>...)` symmetrical with `Telescope.all(...)`,
  with `Mapping.to(...)` / `Mapping.via(...)` / `Mapping.auto()` rows. PR #4. (Folded into the unified
  `Telescope.map(Class, Class, ...)` factory in PR #6 — see Removed.)
- **LambdaMetafactory substrate, Phases 1-5** — per-call dispatch through cached `Function` / `BiConsumer` / `Supplier`
  SAMs synthesized once via `LambdaMetafactory`, replacing `Method.invoke` / `Constructor.newInstance` / `Field.set` in
  the hot path. Substrate change with no API change; the JIT now inlines through reflective dispatch. ADR-0005 documents
  the trade-off. PRs #9 (record-component readers), #12 (bean getters), #11 (bean setters), #13 (builder writers), #14
  (rebuild path — ctor + fields + `Records`).
- **`Telescope.fieldByName(String)` and `Telescope.fieldByName(String, Class<B>)`** — runtime-checked field-name escape
  hatches with loud names. The two-arg form is `var`-friendly inference sugar (the `Class<B>` is not validated against
  the actual field type; same pattern as `Telescope.of(Class<S>)`).
- **JPMS `opens` directive guidance for runtime navigation** — README now documents the `opens` directive non-modular
  consumers need so `MethodHandles.privateLookupIn` succeeds on the LMF substrate. PR #18.

### Changed

- **BREAKING — `Telescope#map(Accessor<A, Map<K, V>>)` renamed to `Telescope#mapField(...)`** (PR #24). Avoids collision
  with the static deep-conversion factory `Telescope.map(Class<A>, Class<B>, Mapping...)`. No `@Deprecated` alias; the
  rename is mechanical. Migration: search-and-replace `.map(<getter>)` → `.mapField(<getter>)` everywhere `<getter>`
  returns a `Map`. `.list(getter)` and `.optional(getter)` did **not** rename — they had no collision.
- **BREAKING — `Telescope#set(Accessor<A, Set<X>>)` renamed to `Telescope#setField(...)`** (PR #24). Avoids cognitive
  collision with the write terminal `Telescope#set(S, A)`. Different arg types, but enough shared verb load that the
  rename pays off at the call site. No `@Deprecated` alias. Migration: search-and-replace `.set(<getter>)` →
  `.setField(<getter>)` everywhere `<getter>` returns a `Set`.
- **BREAKING — codegen `<X>Path<R>` navigator output replaced the prior static-constants shape.** `@Focus` and
  `@BeanFocus` no longer emit a `<X>Focus` class with `public static final Telescope<R, T>` constants. They now emit a
  fluent navigator with one method per record component or bean property, the full Telescope op-surface forwarded on
  every Path / Step class, and bridge hops via `as<TargetSimpleName>()` when `@Bridge` is present. PR #18 also corrected
  the lingering `@Focus` javadoc that documented the old shape.
- **Internal-leak cleanup for v1.0 API stability** (PR #24):
  - `Mapping#sourceClass()` / `targetClass()` / `sourceField()` / `targetField()` moved off the public sealed `Mapping`
    interface to a **package-private sibling** `MappingInternals` (same sealed permit list). `DeepMap` casts where it
    needs the keying info. Out-of-tree consumers that reached into these methods on `Mapping` no longer compile.
  - `conversion/To` no-arg constructor tightened to **package-private**.
  - `conversion/From` no-arg constructor stays public but is documented as a module-internal seam.
  - `Telescope#wrap(Traversal)` and `Telescope#optic()` stay public but are explicitly marked module-internal seams
    (codegen-emitted navigators in downstream modules call `wrap()`; `Traversal` lives in the unexported
    `internal.optics` package so external code still can't supply an argument).
- **`Reflective` collapsed to a record with function-typed fields** (PR #24). The public interface plus two
  anonymous-class singletons (`RECORDS`, `BEANS`) and the `beansWithHints` variant collapse to a single record whose
  five fields are `Function` / `BiFunction` references. Internal-package only; no consumer impact.
- **Package layout flattened** (PR #6). Repackaged from one top-level package (24 files) into five focused packages (≤10
  files each):
  - `io.github.eschizoid.telescope` — DSL surface (`Telescope`, `Edit`, `Either`, `Validated`, `Indexed`).
  - `io.github.eschizoid.telescope.conversion` — `From`, `To`, `Mapper`.
  - `io.github.eschizoid.telescope.mapping` — `Mapping` sealed interface + `SameTypedTo` / `TypedTransformTo` / `Via` +
    `DeepMap`.
  - `io.github.eschizoid.telescope.annotations` — unchanged.
  - `io.github.eschizoid.telescope.internal` — `Records`, `Beans`, `Reflective`, `LambdaIntrospection`, optic lattice.
- **`Iso.liftMapValues` preserves source keys.** `DeepMap` rejects `Map<K1, X>` ↔ `Map<K2, Y>` when key types differ;
  silent re-keying would discard data on the round trip. PR #6.
- **`LambdaIntrospection` rejects non-method-reference lambdas eagerly.** A bare `u -> u.name()` lambda where an
  `Accessor` method reference is expected now throws a precise `IllegalArgumentException` instead of returning a
  synthetic `lambda$xx$0` field name. PR #6.
- **Deep mapping is null-safe.** `Iso.liftList` / `liftSet` / `liftOptional` / `liftMapValues` treat `null` containers
  as `null` on both sides. `DeepMap.assembleIso` returns `null` at a node when the source/target value is `null` instead
  of attempting reflection on a `null` parent. `Mapper.patch(base, null)` returns `base` unchanged. PR #6.
- **`@Bridge` generalized from `@BeanBridge`.** A single `@Bridge(Target.class)` annotation handles all type-pair
  combinations (record↔record, record↔POJO, POJO↔POJO). `BridgeProcessor` emits
  `<Source>Bridge.BRIDGE : Iso<Source, Target>` and the matching `as<TargetSimpleName>()` hop on `<Source>Path<R>`.
- **Generated `<X>Path<R>` / `<X><Comp>Step<R>` forwards the full Telescope op-surface** (read / find / toList /
  toListIndexed / count / exists / set / update / updateIndexed / updateAsync / updateOptional / updateEither /
  updateValidated / then). Any hop can terminate without an intermediate `.get()`.
- **Generated source emits short names + a navigator block** rather than fully-qualified names everywhere; cleaner
  output, less import noise.
- **Module-internal API marking pattern adopted.** `@SuppressWarnings("exports")` on `Telescope.wrap(Traversal)`,
  `Telescope.optic()`, `Mapper`'s constructor, and `DeepMap.resolve(...)` flags the load-bearing seams that stay public
  for codegen reach but should never be called by application code. PR #6.

### Removed (BREAKING)

The fluent map-builder chains from earlier 0.x releases are gone. The unified
`Telescope.map(Class<A>, Class<B>, Mapping<?, ?>...)` factory subsumes all of them. PR #6.

- `Telescope.map(Class<A>)` fluent chain (`.map(A.class).to(B.class).field(...).build()`) — use
  `Telescope.map(A.class, B.class, Mapping.to(srcAcc, tgtAcc), Mapping.via(srcAcc, tgtAcc, mapper), ...)`.
- `Telescope.map(Mapping<A, B>...)` flat varargs (existed briefly in PR #4) — use the explicit class-pair form
  `Telescope.map(A.class, B.class, Mapping... rows)`. The flat-varargs version had to infer source/target classes from
  the first row's accessors; the class-pair form makes the type pair explicit, removes the runtime-checked corner where
  every row was bare `auto()`, and is symmetrical with `Telescope.all(Edit<S>...)`.
- `Telescope.mapper(Mapping<A, B>...)` flat varargs (PR #4 era) — use `Telescope.mapper(A.class, B.class, Mapping...)`.
- `Telescope.fromBean(Class<P>)` fluent chain — use `Telescope.ofBean(P.class).field(...)` for navigation, or
  `Telescope.from(P.class).to(R.class).using(forward, backward)` for hand-written conversion.
- `Telescope.mapBean(Class<A>)` fluent chain — use `Telescope.map(A.class, B.class, ...)`. The unified factory handles
  bean→bean, bean→record, record→bean, and record→record indistinguishably.
- `MapBuilder` / `MapBuilder.Link` / `FieldMapping` / `MapTo` classes — the internal fluent-chain machinery for the
  removed `.map(Class<A>)` chain. Subsumed by the `Mapping` sealed interface + `DeepMap` recursion.
- `BeanFrom` / `BeanTo` / `MapBeanFrom` / `MapBeanTo` classes — internal machinery for the removed `.fromBean(...)` /
  `.mapBean(...)` chains. Same replacement.
- `Mapping.auto()` / `Mapping.auto(Class, Class)` static factories + `AutoInfer` / `AutoExplicit` record impls.
  Same-name recursion IS the auto-mapping; there's no row to write for it. PR #6.
- `BeanTo.viaFields()` / `viaConstructor()` / `viaBuilder()` — the bean-rebuild strategy chooser on the fluent chain.
  `Beans.autoWriter` picks the strategy by class probe (builder, then no-arg + setters, then fields, then single
  all-args ctor with `-parameters`). Override with `writeBean(Class, WriteStrategy)` hint rows.
- `BeanTo.rename(...)` / `via(...)` / `viaEach(...)` — the field-rename/nested-mapper hooks on the fluent chain. Use
  `Mapping.to(srcAcc, tgtAcc)` and `Mapping.via(srcAcc, tgtAcc, mapper)` rows on the unified factory.
- `Telescope.each()` no-arg — the runtime-dispatched escape hatch over `List` / `Set` / `Map` / `Optional` / array on
  any current focus shape. Removed when the typed container subclasses (`ListPath` / `SetPath` / `MapPath` /
  `OptionalPath`) landed. Use the typed terminal on the subclass instead (`.each()` / `.values()` / `.present()`). PR
  #8.
- **Array containers (`X[]`).** `Traversals.arrayStream` / `arrayUpdate` and the only `java.lang.reflect.Array.get` /
  `set` / `newInstance` calls in the codebase. Arrays no longer participate in traversal. Migration: wrap as `List<X>`
  or `Set<X>`. PR #8.
- `TraversalShape.containerKind()` accessor — redundant after the typed-container refactor; the kind is encoded in the
  `Telescope<S, A>` subtype.

### Fixed

- LMF inherited-accessor lookup now uses the declaring class, not the inheritor class. Hot fix in the Phase 2-3 rollout:
  bean getters defined on a superclass were failing the `LambdaMetafactory.metafactory` link when the lookup pointed at
  the subclass.

## [0.3.0] — 2026-06-04

### Changed

- **BREAKING — package namespace unified to `io.github.eschizoid.telescope`.** Earlier `org.telescope` packages were
  unowned namespace squatting (issue #1). The Java package and the Maven group ID both now start with
  `io.github.eschizoid` deliberately; Sonatype verifies via the GitHub account. Downstream consumers update their
  imports.

## [0.2.0] — 2026-06-04

### Added

- **Public no-arg constructors on `FocusProcessor`, `BeanFocusProcessor`, `BridgeProcessor`.** Required by
  `javax.annotation.processing.Processor` SPI loading in some toolchains (Gradle annotation-processor classpath,
  third-party kapt-style runners). Annotation-processor SPI registration in `META-INF/services` still drives the
  discovery; the explicit ctor closes the reflective-instantiation path.
- **JaCoCo coverage + Codecov upload** wired across `:core`, `:codegen`, `:lombok` modules.

### Changed

- Package migration to `com.github.eschizoid.telescope` (later superseded in 0.3.0 — see above).

## [0.1.0] — 2026-05-30

The inaugural release. Everything originally scoped for 0.2 / 0.3 / 0.4 shipped together.

### Added

- **`Telescope<S, A>` DSL.** One public type wraps a `Traversal<S, A>` from the internal optic lattice. Navigation
  (`field`, `each`, `eachValue`, `whenPresent`, `as`, `filter`, `then`), read (`read`, `find`, `toList`,
  `toListIndexed`, `count`, `exists`), write single-shot (`set`, `update`, `updateIndexed`), write multi-edit
  (`update(Telescope, fn)`, `with(fn)`, `apply(S)`), entry points (`Telescope.of(Class)`, `Telescope.ofBean(Class)`,
  `Telescope.from(...).to(...).using(...)`, `Telescope.lens(getter, setter)`).
- **Internal optic lattice.** `Iso`, `Lens`, `Prism`, `Affine`, `Traversal`, `Getter`, `Setter`, `Fold` with
  `.then(Other)` composition that picks the most-specific result type. Sealed inside
  `io.github.eschizoid.telescope.internal.optics`, never exported by `module-info.java`. See
  [ADR-0001](docs/adr/0001-internal-optic-lattice.md) for why the lattice stays internal.
- **Effectful update family.** `updateAsync(S, Function<A, CompletableFuture<A>>)`,
  `updateOptional(S, Function<A, Optional<A>>)`, `updateEither(S, Function<A, Either<L, A>>)`,
  `updateValidated(S, Function<A, Validated<E, A>>)`. All delegate to `Traversal#modifyF` with a per-effect
  `Applicative<F>` witness (`CompletableFutureK`, `OptionalK`, `EitherK`, `ValidatedK`). `Kind<F, A>` / `Applicative<F>`
  are strictly internal — users never type them. `updateAsync` ships with an `Executor` overload.
- **Indexed traversals.** Terminal `updateIndexed` / `toListIndexed`, plus chainable `.withIndex()` returning
  `WithIndex<S, A>` for the ergonomic form. Index is a flat 0-based position in `getAll` order.
- **`from/to/using` Iso factory.** `Telescope.from(A.class).to(B.class).using(forward, backward)` packages a
  hand-written bidirectional conversion as a `Telescope<A, B>`.
- **In-house `Either<L, R>` and `Validated<E, A>` sealed types.** No Vavr / Arrow dependency.
- **`@Focus` annotation processor** (`:codegen`) emits a fluent `<X>Path<R>` navigator per `@Focus`-annotated record.
  Each record component gets one method on the path; container components get a `<X><Comp>Step<R>` sibling class with
  `.each()` / `.eachValue()` / `.whenPresent()` typed terminals. Sub-components that are themselves `@Focus` /
  `@BeanFocus` annotated return their own `Path<R>` so navigation continues fluently. Generated code is reflection-free
  in the hot path.
- **`@BeanFocus` annotation processor** emits the same navigator shape for POJOs (builder-or-setter rebuild via
  `Beans.autoWriter`).
- **`@Bridge(Target.class)` annotation processor** generates a `<Source>Bridge.BRIDGE : Iso<Source, Target>` constant
  plus an `as<Target>()` hop on the source's `<X>Path<R>`. Handles record↔record, record↔POJO, POJO↔POJO. (Generalized
  from the earlier `@BeanBridge` mid-0.1.0 cycle.)
- **Compile-time, reflection-free collection traversal in codegen.** Container components emit typed Step classes that
  descend via lattice composition through `Traversals.eachList` / `eachSet` / `eachMapValue` / `eachOptional`. No
  runtime container dispatch for codegen-emitted steps. Extended to `Map` values and `Optional` mid-cycle.
- **`telescope-lombok` module** — out-of-tree consumer of `AbstractTelescopeProcessor`. Detects `@lombok.Data`,
  `@lombok.Value`, `@lombok.Builder` by string FQN and emits the same `<X>Path<R>` navigator. Round-deferred emission
  collects targets every round and only emits on `processingOver()` so Lombok's lazy AST patches have all fired (the
  in-memory `ProcessorHarness` can't reproduce the scenario; integration tests use Gradle's `compileTestJava` pipeline).
- **`AbstractTelescopeProcessor` base class** with the shared emit pipeline (`emitBeanNavigator`), `javax.lang.model`
  probes (setter/builder discovery, traversal-shape detection, navigable-type lookup), and the Telescope op-surface
  forwarder block. Subclassed by `BeanFocusProcessor` and (out of tree) by `LombokFocusProcessor`. Annotation triggers
  matched by string FQN via `processingEnv.getElementUtils().getTypeElement(fqn)` so the processor is a graceful no-op
  when the trigger annotation isn't on the consumer's classpath.
- **CI release pipeline.** `workflow_dispatch` trigger with a `releaseType` choice input (patch / minor / major)
  consumed by axion-release; JReleaser stages `io.github.eschizoid:telescope`, `:telescope-codegen`, and
  `:telescope-lombok` to Maven Central.

### Design decisions captured

The 0.1.0 release ships with four architecture decision records documenting load-bearing choices:

- [ADR-0001 — Internal optic lattice.](docs/adr/0001-internal-optic-lattice.md) Why the proven `Iso` / `Lens` / `Prism`
  / `Affine` / `Traversal` types stay package-private and surface only through `Telescope<S, A>`. Users never type
  Affine or learn when a Lens composes with a Prism.
- [ADR-0002 — No fuzzy auto-mapping.](docs/adr/0002-no-fuzzy-auto-mapping.md) `.auto()` matches fields by exact name and
  exact type. No Dozer-style fuzzy heuristics; no MapStruct-style code generation for the runtime path. We're not a
  general-purpose mapper.
- [ADR-0003 — Reflection over MethodHandles.](docs/adr/0003-reflection-over-method-handles.md) Plain
  `RecordComponent.getAccessor()` + `Lookup.unreflect` over MethodHandles indirection. ~100 ns / op vs ~5 ns for a
  hand-written record copy. Codegen exists for hot paths. (Superseded by ADR-0005 for hot-path dispatch — the
  metadata-probe path stays reflective.)
- [ADR-0004 — Runtime and codegen are separate strategies.](docs/adr/0004-runtime-and-codegen-strategy-separate.md)
  Runtime path uses `SerializedLambda` to recover field names; codegen path emits direct method references. They produce
  equivalent `Telescope` values but reach them differently. The rebuild path is not unified.

[Unreleased]: https://github.com/eschizoid/telescope/compare/v0.4.1...HEAD
[0.4.1]: https://github.com/eschizoid/telescope/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/eschizoid/telescope/compare/v0.3.0...v0.4.0
[1.0.0]: https://github.com/eschizoid/telescope/compare/v0.3.0...v1.0.0
[0.3.0]: https://github.com/eschizoid/telescope/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/eschizoid/telescope/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/eschizoid/telescope/releases/tag/v0.1.0
