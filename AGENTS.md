# telescope — architecture reference for contributors and coding agents

A map of how this library is built: where things live, which decisions are load-bearing, and what a change has to honour
to be mergeable. Written for anyone — human or coding agent — making a change here.

The user-facing tour lives in `README.md`. This file is for _implementers_. Most of what follows is enforced by review
rather than by tooling — though not all of it: spotless enforces formatting in CI, and `MapperVerifierProcessor`
verifies mapper pairings at compile time. Read the mantras before the module map.

---

## Project mantras (must-honour on every PR)

1. **No inline FQN names in code.** Always add an `import` and use the simple name. The repo was swept clean in a
   dedicated pass and stays that way by review; reintroducing them is a regression. Includes `java.util.LinkedHashMap`
   in method bodies, `javax.lang.model.SourceVersion` in processors, etc. — _add the import_.
2. **Zero reflection unless unavoidable.** Reach for the cached `LambdaMetafactory`-built `Function` / `BiConsumer` /
   `Supplier` substrate first (see `:internal/Beans.java`, `:internal/Records.java`). Only fall through to raw
   `Method.invoke` / `Constructor.newInstance` when the LMF path genuinely doesn't apply.
3. **Respect the optics lattice.** Any new feature that converts / composes / lifts / transforms between types must
   route through `Iso` / `Lens` / `Prism` / `Affine` / `Traversal` in `:internal/optics/`. NO hand-rolled
   `Function<Object, Object>` plumbing. See the "Lattice-first" decision below for the full contract.
4. **North star: dethrone MapStruct.** Every feature decision should make telescope a credible drop-in replacement for
   the MapStruct user. Codegen path needs to match MapStruct's runtime efficiency; runtime path needs to be competitive
   enough for non-hot loops.
5. **World-class ergonomics.** Public APIs should read like English at the call site. Static-imported row factories over
   varargs of typed rows. Sealed permits, not class hierarchies. Fluent navigators, not string-keyed paths.
6. **A performance claim needs a measurement, and the measurement needs a control.** Numbers come from the `Benchmarks`
   workflow, never a laptop: two CI runs can land on runners of different speed, and a row that should not have moved is
   the only way to detect it. Every benchmark carries one — a hand-written loop, an untouched path, a prebuilt value. If
   the control moved, normalise against it or rerun; do not publish the raw ratio. Allocation (`-Pjmh.profilers=gc`) is
   deterministic and survives hardware differences, so prefer it as the durable evidence.
7. **A test that cannot fail is not a test.** Before claiming a test guards a change, revert the change and watch it go
   red. The trap is an assertion that holds on both sides: a container rebuild has the same size and contents whether or
   not its table was sized correctly, and a filtered read whose match sits at the last element costs the same whether it
   stops there or walks the whole tree. Choose the input where the two behaviours actually diverge.

## Writing conventions

- **Comments state a property, not the incident that taught it.** A good comment names something true about the world
  ("a table built from an element count resizes once that count passes three quarters of the smallest power of two at
  least as large"). A bad one names an event in this repository's history ("the review caught this", "fixed in round 2",
  "this used to be slow"). The incident belongs in the PR description; the property belongs in the code.
- **Never name external organisations** in commits, PR bodies, or comments. Adopter feedback drives priority without
  being cited by name.
- **Markdown prose is hard-wrapped at 120 columns** — spotless runs prettier with `proseWrap: always` over `**/*.md`, so
  `spotlessApply` will reflow anything else and `spotlessCheck` will fail the build. PR and issue bodies are the
  opposite: one line per paragraph, because GitHub's comment renderer turns a single newline into a line break. Commit
  messages wrap at ~72 columns, since they are read in terminals.
- **No bug or issue numbers in source comments.** Describe the behaviour in its own terms; cross-references live in the
  PR and the CHANGELOG.

---

## What this library is

A Java 25 deep-copy DSL for records and POJOs: build a path through an immutable graph, then read / write / update /
traverse / convert / lift-through-an-effect using one type.

Two layers, hard line between them:

- **Public** — `io.github.eschizoid.telescope.*` — what users name. One DSL class (`Telescope<S, A>`), three codegen
  entry-point annotations plus per-field modifiers, two sealed effect types, one Indexed record.
- **Internal** — `io.github.eschizoid.telescope.internal.*` — the proven optic lattice, the Kind / Applicative
  HKT-emulation, the reflection helpers. Package-private at the file level and encapsulated by `module-info.java`. Users
  never see Lens / Prism / Affine / Iso / Traversal / Getter / Setter / Fold.

---

## Repository layout

```
core/                  public DSL (telescope-core)
internal/              lattice + HKT emulation + reflection helpers (qualified-exported to :core only)
codegen/               @Focus / @BeanFocus / @Bridge processors
lombok/                telescope-lombok — navigators for @Data/@Value/@Builder
quarkus/               telescope-quarkus — Quarkus 3 CDI extension + TelescopeMapperRegistry
spring-boot-starter/   telescope-spring-boot-starter — Spring Boot 4 auto-config + TelescopeMapperRegistry
benchmarks/            JMH benchmarks (reflective vs codegen vs hand-written)
examples/              runnable example slices for READMEs / blog posts
docs/adr/              architecture decision records
AGENTS.md              this file
```

All modules use Java package `io.github.eschizoid.telescope` (starters live under `.quarkus` / `.spring`); Maven group
is `io.github.eschizoid`. **Don't reintroduce the unowned `org.telescope` package** (issue #1 in the repo).

Published artifacts (group `io.github.eschizoid`): `telescope-core` (main entry), `telescope-internal` (substrate;
published for runtime bytecode, not for direct depend-on), `telescope-codegen`, `telescope-lombok`, `telescope-quarkus`,
`telescope-spring-boot-starter`. JReleaser config in root `build.gradle.kts`; release pipeline is
`.github/workflows/release.yaml` (`workflow_dispatch` with `releaseType` patch/minor/major).

---

## Modules: `:core` and `:internal`

`:core` exposes the public DSL; `:internal` carries the lattice + reflection helpers `:core` is built on. `:internal`
qualified-exports its packages only to `:core` via JPMS, so lattice types never appear in `:core`'s public surface even
though both modules ship as artifacts (`import io.github.eschizoid.telescope.internal.optics.Lens` from a downstream
module won't compile).

### `:core` — public surface (`io.github.eschizoid.telescope`)

Six exported packages (`module-info.java`): the root, `annotations`, `conversion`, `effects`, `introspection`,
`mapping`. `runtime.instances` stays unexported (core-internal effect witnesses).

| File / package               | Role                                                                                                                                                                                                                                                                                    |
| ---------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Telescope.java`             | the DSL — every entry point + every terminal op                                                                                                                                                                                                                                         |
| `DeepMap.java`               | the runtime deep-mapping engine — caches + composes an `Iso<X, Y>` per type pair, walks components recursively                                                                                                                                                                          |
| `Merge.java`, `Sources.java` | multi-source `N → 1` merge (`Telescope.merge(Target.class, from(...), ...)`) with the class-keyed source bag                                                                                                                                                                            |
| `Indexed.java`               | `(int index, A value)` record returned by `toListIndexed` etc.                                                                                                                                                                                                                          |
| `Edit.java`                  | tiny public interface for `Telescope.all(over(...))` — static `over(path, fn)` factory, package-private `EditImpl`                                                                                                                                                                      |
| `effects/`                   | `Either`, `Validated` — the sealed sum types backing `updateEither` / `updateValidated`                                                                                                                                                                                                 |
| `mapping/`                   | `Mapping` (the sealed row interface for `Telescope.map(to(...), via(...), auto())`) + its row impls (`SameTypedTo`, `TypedTransformTo`, `Via`, `Compute`, `Constant`, `Conditional`, `Drop`, `Extract`, …) and `NullHint`/`WriteHint`                                                   |
| `conversion/`                | `Mapper` / `MapperBuilder` (forward/backward/patch), `Match` (sealed-root dispatch), `BridgeFn` / `BridgeProvider` / `BridgeRegistry` (codegen-bridge SPI, ADR-0011), `From`/`To`, `MappingTraces`                                                                                      |
| `introspection/`             | `explain()` / `trace()` support — `OpticNode`, `OpticReport`, `Trace`, `TraceLimits` (ADR-0013/0014)                                                                                                                                                                                    |
| `runtime/instances/`         | the `*K` effect witnesses (`CompletableFutureK`, `OptionalK`, `EitherK`, `ValidatedK`) — see Effectful update machinery                                                                                                                                                                 |
| `annotations/`               | `@Focus` (records → navigator), `@BeanFocus` (POJOs), `@Bridge`/`@Bridges` (type-pair conversion), `@FromMap` (untyped ingestion), plus the per-field codegen knobs (`@Compute`, `@Constant`, `@Default`, `@Rename`, `@Transform`, `@ViaMapper`, `@WriteStrategy`, `@UncheckedMapping`) |

`Telescope.java` is the single point where the DSL is exposed. It wraps a `Traversal<S, A>` from the internal lattice
**plus a `Function<S, S> chain`** (defaults to identity) that accumulates multi-edit work. The vocabulary below is the
common core rather than the full surface — `iso`, `bridge`, `asList`/`asSet`/`asMap`/`asOptional`, `after`, `before`,
`hop`, `explain`, `trace`, `asForwardMapper`, `mapperForward`, `fromMap` and `merge` also live there:

- **Navigation (typed, compile-checked):** `of`, `ofBean`, `from/to/using`, `field(Accessor)`, `each(Accessor)`,
  `eachValue(Accessor)`, `whenPresent(Accessor)`, `as`, `filter`, `then`.
- **Navigation (runtime-checked, loudly named):** `fieldByName(String)`, `fieldByName(String, Class<B>)`, `each()`
  no-arg. Documented escape hatches; see Compile-safety scoring below.
- **Read:** `read`, `find`, `toList`, `toListIndexed`, `count`, `exists`.
- **Write — single-shot:** `set`, `update(S, fn)`, `updateIndexed`, plus the four effectful `update*` variants
  (`updateAsync`, `updateOptional`, `updateEither`, `updateValidated`).
- **Write — multi-edit (recommended):** `Telescope.all(Edit<S>...)` folds N edits into one reusable normalizer.
  `Edit.over(Telescope<S, X>, Function<X, X>)` builds each row (static-import `over`). One `over(...)` per arg line —
  count visible at a glance, no chain blur. Internally folds into the same `chain: Function<S, S>` accumulator that
  `.with(...)` uses. Edits whose paths are provably order-free fuse into one structural pass through the `Fusion` engine
  (prefix-trie sharing plus sibling slot fusion); overlapping paths, runtime-checked navigation, and custom edits fall
  back to the sequential fold.
- **Write — multi-edit chain (alternative):** fluent shape pre-dating `Telescope.all(...)`, kept for inline paths.
  - `update(Telescope<S, X>, Function<X, X>)` — pre-built path; equivalent end-state to `over(...)` but accumulated
    inline.
  - `with(Function<A, A>)` — inline-path trailing edit on a chain already navigated.
  - Both feed the same `chain` accumulator; overload resolution disambiguates `update(Telescope first arg)` (chainable)
    vs `update(S first arg)` (single-shot terminal).
  - **Steer multi-path users to `Telescope.all(over(...))`** — back-to-back `.each(...).field(...).with(...)` segments
    visually blur into one chain even though they're separate edits.
- **Convert — declarative mapping (recommended):** `Telescope.map(A.class, B.class, MapStep...)`. Source and target
  classes are mandatory head arguments, never inferred. **Same-name backfill is the default, not a row** — passing no
  rows at all recurses the pair and lines every component up by name; rows are overrides layered on top. `MapStep` is
  the sealed varargs type (`permits Mapping, WriteHint, NullHint`), and the row factories are `to`, `via`, `drop`,
  `toOneWay`, `constant`, `compute`, `when`, `enumTo`, `toOrElse`, `zip`. Sibling `Telescope.mapper(...)` returns
  `Mapper<A, B>` for patch/nestability. The fluent alternative is `Telescope.mapperBuilder(A.class, B.class)` with
  `.add(...)` / `.inherit(...)` then `.build()` or `.buildTelescope()`. Symmetrical with `Telescope.all(Edit<S>...)`.
  - **Class inference internals:** `Mapping#sourceClass()` / `targetClass()` use `LambdaIntrospection.implClassOf`
    (`:internal`'s `SerializedLambda` helper — the one place for the decode).
- **Indexed chain:** `withIndex()` → `WithIndex<S, A>` for indexed traversal terminals.

### `:internal` — substrate (`io.github.eschizoid.telescope.internal`)

| File / package                                       | Role                                                                                                                                                                                                                          |
| ---------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Records.java`                                       | record reflection: cached LMF component readers, canonical-ctor `MethodHandle` rebuild                                                                                                                                        |
| `Beans.java`                                         | bean reflection: getter / setter / builder discovery, the `BeanWriter` rebuild strategies (`SettersWriter` / `BuilderWriter` / `FieldsWriter` / ctor)                                                                         |
| `Reflective.java`                                    | the uniform record-vs-bean dispatch `DeepMap` drives — one facade, per-side `of(Class)` resolution                                                                                                                            |
| `MhIso.java`                                         | `MethodHandle`-combinator assembly of the structural-conversion `Iso` leaf (the runtime-perf hot path: fused reads + rebuild in one composed handle)                                                                          |
| `MhAccessors.java`                                   | native-image accessor closures — one `supplier`/`function`/`biConsumer`/`biFunction` per SAM shape over an `asType`-adapted handle (ADR-0015)                                                                                 |
| `NativeImage.java`                                   | the one-time `IN_IMAGE` flag (JDK `imagecode` property) that gates LMF-vs-`MhAccessors` accessor construction                                                                                                                 |
| `LambdaIntrospection.java`                           | `SerializedLambda` decode — impl-method name + declaring class from a method reference; the single decode point                                                                                                               |
| `MetadataHolderProbe.java`, `BridgeHolderProbe.java` | ADR-0006 runtime probes for the codegen-emitted `<X>FieldOptics` / `<Source>Bridge` siblings — constant on hit, LMF on miss                                                                                                   |
| `NullDefaults.java`                                  | per-leaf-type default-value table backing `NullHint.NullStrategy#DEFAULT`                                                                                                                                                     |
| `pairing/`                                           | the shared pairing spec (ADR-0012): `PairingRules` + `PropertySystem` — one decision implementation consumed by both the runtime mapper and the compile-time verifier, with `PairingMessages` as the single diagnostic source |
| `optics/Iso.java`                                    | reversible `A ↔ B` (+ static `lift*` container helpers)                                                                                                                                                                       |
| `optics/Lens.java`                                   | exactly-one focus, reversible writes                                                                                                                                                                                          |
| `optics/Prism.java`                                  | at-most-one focus + reconstruct, sealed-type cases                                                                                                                                                                            |
| `optics/Affine.java`                                 | at-most-one read+write (Lens + Prism intersection)                                                                                                                                                                            |
| `optics/Traversal.java`                              | many-focus with `modifyF` for effectful traversal                                                                                                                                                                             |
| `optics/Getter.java`, `Setter.java`, `Fold.java`     | read-only / write-only / fold-only weakenings                                                                                                                                                                                 |
| `optics/Focus.java`                                  | static factories that build the optic instances                                                                                                                                                                               |
| `optics/collections/Traversals.java`                 | runtime dispatch for List / Set / Iterable / Map values / Optional                                                                                                                                                            |
| `optics/Kind.java`, `Applicative.java`               | HKT-emulation for effectful update (see next section)                                                                                                                                                                         |

Each optic except `Setter` and `Fold` exposes `.then(Other)` for composition (a write-only and a read-only weakening
have nothing to compose through). Composition picks the most-specific result type; the lattice's resolution rules are in
the composition table further down. The lattice is what makes the public DSL composable without users seeing optic
types.

`:internal`'s `module-info.java` qualified-exports every `internal.*` package to `io.github.eschizoid.telescope` only
(`pairing` also to the codegen module for the compile-time verifier) — JPMS enforces the boundary; downstream modules
cannot compile against lattice types.

---

## Effectful update machinery (`internal/optics/Kind.java` + `Applicative.java`)

The four public `update*` methods (`updateAsync` / `updateOptional` / `updateEither` / `updateValidated`) all delegate
to `Traversal#modifyF(Applicative<F>, S source, Function<A, Kind<F, A>> fn)`. `Kind<F, A>` emulates HKT;
`Applicative<F>` is the (`pure`, `map2`) dispatch table. Effects ship as singleton witnesses — the `*K` classes live in
`:core` (`io.github.eschizoid.telescope.runtime.instances`), not `:internal`, because each names the public `:core` sum
type it wraps (`Either` / `Validated`):

| Witness              | Wraps                                           |
| -------------------- | ----------------------------------------------- |
| `CompletableFutureK` | `CompletableFuture<A>` (sequential allOf)       |
| `OptionalK`          | `Optional<A>` (short-circuit on `empty`)        |
| `EitherK`            | `Either<L, A>` (short-circuit on `Left`)        |
| `ValidatedK`         | `Validated<E, A>` (accumulate `Invalid` errors) |

`Kind<F, A>` is **strictly internal** — users never type `Kind` or `Applicative`. Adding a new effect = one new `*K`
witness + one new `update*` method. `updateAsync` has an `Executor` overload.

---

## Composition lattice (the internal rules)

Composing two optics picks the most-specific result type. `Telescope` exposes this through `.then(...)` chains that look
like a single fluent type to users:

| Outer ↘ Inner | Lens   | Prism  | Iso    | Affine | Traversal |
| ------------- | ------ | ------ | ------ | ------ | --------- |
| **Lens**      | Lens   | Affine | Lens   | Affine | Traversal |
| **Prism**     | Affine | Prism  | Prism  | Affine | Traversal |
| **Iso**       | Lens   | Prism  | Iso    | Affine | Traversal |
| **Affine**    | Affine | Affine | Affine | Affine | Traversal |
| **Traversal** | Trav.  | Trav.  | Trav.  | Trav.  | Traversal |

Anything that flows through a `Traversal` (many-focus) stays a Traversal. `Telescope` accepts the widening silently;
`read()` throws when there's no value, `find()` returns Optional.

The diamond between `Iso → Lens, Prism` is resolved explicitly: `Iso.then(Lens) = Lens`, `Iso.then(Prism) = Prism`,
`Iso.then(Iso) = Iso`. Test `OpticLawsTest#isoThenLensIsLens` pins it.

---

## Compile-safety scoring

The public surface is partitioned into three buckets. Future changes should preserve this partitioning — moving an entry
from "compile-checked" to "runtime-checked" is a regression.

### ✓ Fully compile-checked (default)

- **Entry points**: `Telescope.of(Class)` / `ofBean(Class)`, `lens(getter, setter)`,
  `from(Class).to(Class).using(fwd, bwd)`, `map(A.class, B.class, rows...)`, `mapperBuilder(A.class, B.class)…build()`.
- **Navigation**: `.field`, `.each`, `.eachValue`, `.whenPresent`, `.as(Class)`, `.filter`, `.then`, `.withIndex` — all
  take `Accessor` method refs.
- **Typed-container navigation**: `.list` / `.setField` / `.mapField` / `.optional` return `ListTelescope` /
  `SetTelescope` / `MapTelescope` / `OptionalTelescope` whose typed terminal (`.each()` / `.values()` / `.present()`)
  descends without runtime container dispatch. `setField` / `mapField` were renamed from `.set` / `.map` (1.0) to avoid
  cognitive collision with the write terminal `set(S, A)` and the static `Telescope.map(Class, Class, ...)`
  deep-conversion factory.
- **Write — single-shot**: `.set`, `.update(S, fn)`, `.updateIndexed`, plus the four effectful `update*` variants.
- **Write — multi-edit**: `.with(Function<A, A>)`, `.apply(S)`. `javac` rejects type-mismatched fn at compile time.
- **Read**: `read` / `find` / `toList` / `toListIndexed` / `count` / `exists`.

### ⚠️ Runtime-checked (two documented escape hatches)

- `.fieldByName(String)` — late-bound field name (config-driven paths). String IS the contract. Wrong name → runtime
  `IllegalArgumentException`. Renamed from `.field(String)` so the call site signals the runtime nature.
- `.fieldByName(String, Class<B>)` — same as above with an inline `Class<B>` for `var`-friendly inference. The
  `Class<B>` is **not validated** against the actual field type at compile OR runtime; it's pure inference sugar, same
  pattern as `Telescope.of(Class<S>)`. Honest javadoc says so.

### Zero runtime-check points: codegen path

The `@Focus` / `@BeanFocus` / `@Bridge` annotation processors generate `<X>Telescope<R>` navigators where every step is
a typed method call against a generated method. No `SerializedLambda` decode, no string-keyed lens lookup, no runtime
container dispatch. This is the "if it compiles, it runs" path.

---

## Module: `:codegen`

Annotation processors that emit a fluent typed `<X>Telescope<R>` navigator at compile time, eliminating the runtime
reflection cost.

### Shape of the generated code

For each `@Focus` / `@BeanFocus` type, the processor emits a sibling `<X>Telescope<R>` final class wrapping a
`Telescope<R, X>` field, with:

- `static <X>Telescope<X> of()` factory + `Telescope<R, X> get()` extractor.
- One method per component returning the composed `Telescope<R, Field>` via `path.then(Telescope.lens(getter, setter))`,
  with a `.hop(...)` appending an `OpticNode` so `explain()` and `trace()` answer on generated navigators too.
- The full Telescope op-surface forwarded (`read`, `find`, `toList`, `set`, `update*`, `then`, …) so any hop can read /
  update without dropping back to `.get()`. Forwarders are emitted by
  `AbstractTelescopeProcessor#emitTelescopeForwarders`.

Container components (`List<E>` / `Set<E>` / `Map<K,V>` / `Optional<E>` / `Iterable<E>`) get a sibling `<X><Cap>Step<R>`
class with `.each()` / `.eachValue()` / `.whenPresent()`. If the element type is itself navigable the step returns the
sub-element's `<Element>Telescope<R>` navigator so navigation continues fluently.

(Distinct from the ADR-0006 sibling **`<X>FieldOptics`** metadata holder — the per-field
`public static final Telescope<X, ...>` constants the runtime hybrid-dispatch probe reads. The navigator is what you
call; the holder is what the runtime short-circuits through.)

### Bridge hops (cross-paradigm conversion)

When a type carries **both** `@Focus`/`@BeanFocus` **and** `@Bridge(Target.class)`, the navigator gains an
`as<Target>()` method that chains the generated `<Source>Bridge.BRIDGE` constant (`BridgeProcessor` emits a
`Telescope<Source, Target>` bridge from component-name bijection or builder/setter rebuild). Example:
`UserEntityTelescope.of().asUserDto().email().update(entity, String::toLowerCase)`.
`AbstractTelescopeProcessor#emitBridgeHop` writes the body. If the target is itself navigable, the hop returns
`<Target>Telescope<R>`; otherwise a terminal `Telescope<R, Target>`.

### Files

| File                                                      | Role                                                                                                                                                                                                                                                                                                                                                         |
| --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `AbstractTelescopeProcessor.java`                         | **public abstract** base. Holds the shared emit pipeline (`emitBeanNavigator`), the `javax.lang.model` probes (setter/builder discovery, traversal-shape detection, navigable-type lookup), and the forwarder block. Subclassed by `FocusProcessor`, `BeanFocusProcessor`, `BridgeProcessor`, `FromMapProcessor`, and out of tree by `LombokFocusProcessor`. |
| `FocusProcessor.java`                                     | scans `@Focus`, emits the record version of the Telescope navigator (canonical-ctor rebuild).                                                                                                                                                                                                                                                                |
| `BeanFocusProcessor.java`                                 | scans `@BeanFocus`, delegates to `emitBeanNavigator` (builder-or-setters rebuild).                                                                                                                                                                                                                                                                           |
| `BridgeProcessor.java`                                    | scans `@Bridge`, generates the bidirectional Iso class plus the BRIDGE static constant. Handles all type-pair combinations (record↔record, record↔POJO, POJO↔POJO).                                                                                                                                                                                          |
| `FromMapProcessor.java`                                   | scans `@FromMap`, emits the reflection-free `Map<String, Object>` → record binder (ADR-0010). Uses the sealed `Coercion` taxonomy for per-component conversion.                                                                                                                                                                                              |
| `MapperVerifierProcessor.java`                            | compile-time mapper pairing verification (ADR-0012) via the shared `pairing` spec. Extends plain `AbstractProcessor`, not the navigator base. Controlled by `-Atelescope.verify=error\|warn\|off`.                                                                                                                                                           |
| `Coercion.java`, `MirrorProps.java`                       | the per-component conversion taxonomy and the `javax.lang.model` side of the pairing `PropertySystem`.                                                                                                                                                                                                                                                       |
| `META-INF/services/javax.annotation.processing.Processor` | SPI registration for all five processors.                                                                                                                                                                                                                                                                                                                    |

### Conventions for any new processor

- Subclass `AbstractTelescopeProcessor` when emitting a bean-style navigator; reuse
  `emitBeanNavigator(pojo, triggerLabel, navigableAnnotations)` — pass the annotation-FQN set that marks a class as
  having its own Path.
- Annotation triggers are matched by **string FQN** via `Elements.getTypeElement(fqn)` — graceful no-op when the trigger
  isn't on the consumer's classpath. The Lombok module relies on this to avoid a compile-time Lombok dep on the
  processor jar.

---

## Module: `:lombok` (telescope-lombok)

Out-of-tree consumer of `AbstractTelescopeProcessor`. Detects `@lombok.Data`, `@lombok.Value`, `@lombok.Builder` by
string FQN and emits the same `<X>Telescope<R>` navigator shape as `:codegen`.

### The round-deferred-emission gotcha

**Don't process a Lombok-annotated class in round 1.** Lombok installs lazy AST visitors during processor init that
patch class declarations on traversal. Round 1's `Elements.getAllMembers()` on a `@Data` class sees the un-patched
member list (no synthesised getters / setters / builder) → "no readable properties" error.

Two shapes handle it, and which one a processor needs depends on whether its output has to be resolvable from
same-module main code. `LombokFocusProcessor` retries every round and emits as soon as the bean surface reads complete
(`emitBeanNavigatorIfReady`), using `processingOver()` only as a last-resort drain — deferring everything meant the
emitted navigator did not exist when same-module main code was resolved. `BridgeProcessor` and `FromMapProcessor` defer
only the targets carrying a Lombok trigger, to `processingOver()`. **Any future processor reading synthesised members of
Lombok-annotated types must do one or the other; reading them in round one gets an un-patched view.**

The in-memory `ProcessorHarness` (`:codegen` testFixtures) can't reproduce this — Lombok's javac hooks don't install in
the in-process `JavaCompiler.CompilationTask` flow. Lombok integration tests must use Gradle's standard
`compileTestJava` pipeline.

---

## Modules: `:quarkus` and `:spring-boot-starter` (integration starters)

Mirror-image integration modules exposing a `TelescopeMapperRegistry` indexed by `(srcClass, tgtClass)` through each
framework's native bean discovery. Three symmetric classes per module:

| Quarkus class             | Spring class                 | Role                                                                                                   |
| ------------------------- | ---------------------------- | ------------------------------------------------------------------------------------------------------ |
| `TelescopeMapperRegistry` | `TelescopeMapperRegistry`    | `(srcClass, tgtClass) → Mapper<?, ?>` index. `get(...)` + `find(...) → Optional<Mapper<?, ?>>`.        |
| `TelescopeProducer`       | `TelescopeAutoConfiguration` | Collects every `Mapper<?, ?>` bean visible to the container, builds the registry.                      |
| `TelescopeConfig`         | `TelescopeProperties`        | `telescope.registry.fail-fast` (default `true`); Spring also binds `telescope.default-write-strategy`. |

Duplicate `(srcClass, tgtClass)` pairs throw `IllegalStateException` at construction — qualify and inject directly if
two mappers genuinely share a pair.

**Quarkus specifics.** `@ApplicationScoped` registry; `TelescopeProducer` uses ArC's `@All List<Mapper<?, ?>>`
collector; `TelescopeConfig` is `@ConfigMapping(prefix = "telescope")`. Jar ships a pre-built `META-INF/jandex.idx` to
skip Quarkus's startup bytecode scan. Runtime-only — no separate `deployment` module / `@BuildStep` recorders.

**Spring specifics.** `TelescopeAutoConfiguration` is `@AutoConfiguration` registered via
`META-INF/spring/...AutoConfiguration.imports`. Injects a raw `Collection<Mapper>` and narrows it internally — Spring's
autowiring will not match `Collection<Mapper<?, ?>>` against parametric beans, which is why this side differs from
Quarkus's `@All List<Mapper<?, ?>>`. Exposes the registry as `@Bean @ConditionalOnMissingBean`, guarded by
`@ConditionalOnClass(Telescope.class)`. `TelescopeProperties` is `@ConfigurationProperties(prefix = "telescope")` with
`failFast` on a nested `Registry` type, so the resolved key is the same `telescope.registry.fail-fast`.

**Not here:** no `@EnableTelescope`; no build-time codegen (these starters wrap whichever `Mapper` shape the user brings
— reflective or codegen-generated). No `@QuarkusTest`/`@SpringBootTest` integration tests — pure-Java registries covered
by unit tests.

---

## Testing infrastructure

### `:core` tests (`core/src/test/java/io/github/eschizoid/telescope/`)

Plain JUnit 5, no special harness: the DSL surface, the indexed traversal, the effectful update variants, the
from/to/using factory, the read-terminal consistency matrix, and the bean navigation path. The optic laws live one layer
down in `internal/src/test/.../optics/OpticLawsTest.java`, alongside `FoldLaws` — the lattice is proven where it is
defined, and the DSL is proven where it is exposed.

### `:codegen` tests — in-memory compilation harness

`codegen/src/testFixtures/java/io/github/eschizoid/telescope/codegen/ProcessorHarness.java` — exposed via the
`java-test-fixtures` Gradle plugin so it's reusable from `:lombok`. Drives one or more annotation processors through
`ToolProvider.getSystemJavaCompiler()` over in-memory source strings (`-proc:only`, no `.class` files emitted), captures
generated `SOURCE`-kind outputs in a wrapping `CapturingFileManager`, returns a `Compilation` record with success /
diagnostics / generated map.

Tests: `FocusProcessorTest`, `BeanFocusProcessorTest`, `BridgeProcessorTest`. Each test compiles a tiny source string,
drives one processor, asserts on the generated output's text and on diagnostic content for rejection cases.

The harness has a multi-processor overload (`compile(List<Processor>, sources)`) for any future cross-processor
integration tests where order matters.

### `:lombok` tests — file-based integration via Gradle

The in-memory harness **does not work for Lombok** (see the round-deferred-emission note above). Lombok integration
tests live as real files in `lombok/src/test/java/io/github/eschizoid/telescope/codegen/lombok/`:

- `fixtures/DataUser.java`, `BuilderUser.java`, `ValueBuilderUser.java`, `DataTeam.java` — real Lombok-annotated POJOs
  that get compiled by Gradle's standard `compileTestJava` pipeline with both Lombok and `LombokFocusProcessor` on the
  `testAnnotationProcessor` config.
- `LombokFocusProcessorTest.java` — verifies the generated `<X>Telescope` classes by reflection (`Class.forName(...)`)
  and runs them end-to-end (`DataUserTelescope.of().email().update(...)` should lower-case the email through Lombok's
  synthesised setter).

The build wires `testAnnotationProcessor(libs.lombok)` + `testAnnotationProcessor(project(":core"))`

- `testAnnotationProcessor(project(":codegen"))` + `testAnnotationProcessor(files(tasks.named("jar")))`.

---

## Module: `:benchmarks`

JMH micro-benchmarks. Build wires `jmhAnnotationProcessor(project(":codegen"))` so `@Focus` / `@BeanFocus` / `@Bridge`
are processed on the JMH source set, which means generated navigators and bridges are exercised as real emitted code.

**Run them from CI, not locally.** The `Benchmarks` workflow (`.github/workflows/benchmarks.yaml`, `workflow_dispatch`)
takes a JMH filter, warmup and measurement iteration counts, the time per iteration for each, a fork count, and an
optional profiler. A developer machine running anything else in the background produces numbers that look like findings
and are not.

### Measuring a change

An A/B needs both sides to run the _same_ benchmark, so put the benchmark on both branches: cherry-pick it onto a
baseline branch cut from `main`, dispatch the workflow on each, and compare. Otherwise a "before" run has nothing to
measure.

Read the control row first. If a hand-written loop, an untouched path, or a prebuilt value moved between the two runs,
the runners differ in speed and every ratio in that pair is inflated by roughly that factor. Normalise or rerun.
Allocation (`-Pjmh.profilers=gc`) is deterministic and does not have this problem, which makes it the better gate.

### What each class covers

| Benchmark                                                        | Covers                                                                             |
| ---------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| `MapStructComparisonBenchmark`                                   | codegen and runtime against MapStruct at flat / nested / deep tiers                |
| `TelescopeBenchmark`                                             | record and bean field updates, mapper reads, hand-rolled baselines                 |
| `ReadFoldBenchmark`                                              | multi-focus read terminals, and the `read` / `find` head-grab against a loop floor |
| `ContainerAllocationBenchmark`                                   | mapper container conversion across cardinality                                     |
| `ContainerWriteBenchmark`                                        | `.each()` / `.eachValue()` rebuilds across cardinality                             |
| `LmfBenchmark`                                                   | the dispatch substrate: LMF vs reflection vs hand-rolled                           |
| `EffectfulTraversalBenchmark`                                    | `updateOptional` and `updateValidated` against a pure update                       |
| `MergeBenchmark`, `FromMapBenchmark`, `MultiEditBenchmark`       | the positional-bind and fusion engines                                             |
| `MapperConstructionBenchmark`                                    | what building a mapper costs, separate from using one                              |
| `FieldByNameBenchmark`, `HolderDispatchBenchmark`                | the string-keyed escape hatch, and the codegen-holder probe                        |
| `MatchDispatchBenchmark`                                         | sealed-root dispatch                                                               |
| `ContainerLoopSpikeBenchmark`, `MethodHandleChainSpikeBenchmark` | spikes kept for the record; not gates                                              |

The table lists what exists on `main`. A benchmark added by an open PR is not here until it lands.

Published figures live in `docs/perf-mapstruct-comparison.md` and `docs/perf-runtime-collections.md`. Treat absolute
nanoseconds as machine-specific; ratios within one run are the durable part.

### Coverage is the thing that decays

Every defect found in the repository's performance audit lived on a path no benchmark exercised — reads on a navigation
path, name resolution on a wide record, hash-container writes, in-place `into`. The benchmarked paths were fine, which
is the point: coverage decays silently while the numbers keep looking healthy. When adding a benchmark, prefer a
dimension nothing else varies (cardinality, arity, path depth) over another variant of a shape already covered.

---

## Why the two-layer architecture

Proven optic types (Haskell `lens` → Scala Monocle → Arrow Optics) inside; one DSL class outside. Users write
`Telescope.of(...).each(...).field(...)` without ever naming Affine / Lens / Prism. Effectful update, indexed
traversals, and codegen all landed by extending `internal/optics` and surfacing new methods on `Telescope` — no core
rewrites. Tested at both layers: `OpticLawsTest` in `:internal` proves the optic laws, the `:core` suites prove DSL
behaviour.

The history — function registry, then a full Monocle port, then a regression that threw the lattice away, then the
two-layer settlement — is written up in a series of blog posts linked from the README.

---

## Design decisions worth recording

### Lattice-first: use the internal optics, don't reinvent

Any new feature that converts / composes / lifts / transforms between types routes through the lattice (`Iso` / `Lens` /
`Prism` / `Affine` / `Traversal` in `internal/optics/`). No hand-rolled `Function<Object, Object>` plumbing. Composition
laws are pinned by `OpticLawsTest`; container lifting belongs next to `Iso`/`Traversal` in `internal/optics/`.

- **Bidirectional structural conversion** (deep mapping, bridge generation, record-pair recursion) → cache + compose
  `Iso<X, Y>` per type pair; assemble record-level `Iso<S, T>` by walking components. Reference: `DeepMap.java`.
- **Single-direction transformation packaged with a path** (the `Edit<S>` / `Telescope.all(over(...))` shape) → delegate
  to `Traversal#modify` via `Telescope.update(path, fn)`. Don't parallel the modify machinery.
- **Container-shape lifting** lives as static `Iso.lift*` helpers in `internal/optics/Iso.java`.
- **Effectful traversal** for new effects → add an `Applicative<F>` witness + a `update*` method calling
  `Traversal#modifyF`.

A `Function<Object, Object>` field that could have been an `Iso<X, Y>` is a smell. Object-typed plumbing is only
acceptable at the final `Records` / `Beans` / `Reflective` assembly floor where erasure is unavoidable (plus the
documented module-internal seams like `Mapper.PatchEntry`).

### Method references for field names

`.field(User::name)` works because `User::name` is a `Serializable` method reference, and
`SerializedLambda.getImplMethodName()` recovers `"name"` at runtime. The library then uses `Class.getRecordComponents()`
and the canonical constructor to rebuild.

Lambdas (`u -> u.name()`) are explicitly rejected — they synthesize methods like `lambda$xx$0` and we can't recover the
field name. The error message tells you. For dynamic field names there's `fieldByName(String)`.

### `each(getter)` with element-type inference

`each(Func<A, ? extends Iterable<E>> getter)` infers `E` from the method-reference's compile-time type
(`Function<Team, List<User>>` → `E = User`). No witness needed at the call site. Same trick for `eachValue` (Map) and
`whenPresent` (Optional).

### Records and beans, separate entry points

Records use canonical-ctor rebuild via `Records.java`. Beans use builder-or-no-arg-ctor-with-setters via `Beans.java`.
The runtime entry points are `Telescope.of(...)` and `Telescope.ofBean(...)` respectively. `BeanFocusProcessor` and
`LombokFocusProcessor` both generate the bean-style rebuild; `FocusProcessor` generates the record-style.

### Reflection for discovery, generated dispatch for the hot path

ADR-0003 originally chose plain reflection over MethodHandles; ADR-0005 refined it, and the refinement is what ships.
Reflection is retained for **discovery** — finding components, getters, setters, builders. Hot-path **dispatch** is a
`LambdaMetafactory`-built SAM on the JVM, and a `MethodHandle` closure (`MhAccessors`, `MhIso`) inside a native image
where LMF cannot define a class. Read 0003 as history and 0005 as the live decision; 0003's figures predate both.

### Runtime and codegen are separate strategies, not unified

Runtime path uses `SerializedLambda` to recover field names; codegen path emits direct method-references. They produce
equivalent `Telescope` values but reach them differently. ADR-0004 captures why we don't try to unify the rebuild path.
See `docs/adr/0004-runtime-and-codegen-strategy-separate.md`.

### Round-deferred Lombok emission

`LombokFocusProcessor` collects targets every round and only emits on `processingOver()` so Lombok's lazy AST patches
have all fired. Documented above; applies to any future Lombok-touching processor.

### Bridge constants emit in the source's package

`<Source>Bridge` is emitted in the source's Java package. The Telescope navigator's `as<Target>()` method references it
by simple name (no FQN qualifier) because both classes live in the same package. If a future change moves
`<Source>Bridge` to a different package, `AbstractTelescopeProcessor#emitBridgeHop` needs an FQN update.

### Native-image AOT: every runtime LMF site must carry the `IN_IMAGE` branch

Runtime `LambdaMetafactory.metafactory(...)` defines a class at run time, which GraalVM native-image forbids. Every
accessor-builder site in `:internal` therefore branches on `NativeImage.IN_IMAGE` to the matching `MhAccessors` closure
(`supplier` / `function` / `biConsumer` / `biFunction`) — **any new runtime LMF call site must do the same, or the
native-image workflow goes red.** The JVM keeps LMF (it's faster once JIT-warmed); the branch is one folded
`static final boolean`. Telescope's own build-time-init metadata ships as `native-image.properties` inside
`telescope-core`; app DTO/lambda metadata is the app's own reachability config. The `:examples:graphql` `NativeVerify`
native binary (nine capabilities) is the regression gate — on every substrate push to `main` and weekly. Full contract:
`docs/native-image.md`, decisions: ADR-0015. The one documented exception is the guarded Hibernate-proxy accessor pair
(fails safe to `null`; JVM/codegen-only under AOT).

---

## ADR index (`docs/adr/`)

1. **0001 — Internal optic lattice.** Why the lattice is package-private.
2. **0002 — No fuzzy auto-mapping.** `.auto()` is exact name+type; no Dozer-style heuristics.
3. **0003 — Reflection over MethodHandles.** Perf trade-off.
4. **0004 — Runtime and codegen are separate strategies.** Why we don't unify the rebuild path.
5. **0005 — LambdaMetafactory over MethodHandle.invoke.** Refines ADR-0003: keep reflective discovery; swap hot-path
   dispatch primitive to `LambdaMetafactory`-built `Function`/`BiConsumer`/`Supplier` so the JIT inlines through. Landed
   in phases: record readers first, then bean getters, bean setters, builder writers, and the rebuild path.
6. **0006 — Codegen ↔ runtime 1:1 lookup via sibling metadata holder.** Extends ADR-0004, refines ADR-0005:
   `FocusProcessor`/`BeanFocusProcessor` emit sibling `<X>FieldOptics` with `public static final Telescope<X, ...>`
   constants per field (distinct from the `<X>Telescope` navigator — the holder is the runtime-probe target, not the
   fluent surface). Runtime sites short-circuit via `ClassValue<Optional<HolderRef>>` probe — constant on hit, LMF on
   miss. Phased (A emit, B runtime probe, C deep-mapping use). For annotated types, only remaining runtime reflection is
   `SerializedLambda` decode.
7. **0007 — Cross-module `@Bridge` carrier.** (Accepted, shipped #149.) `@Bridge` on a third "carrier" class with
   explicit `source`/`target`. Closes the split-module MapStruct-parity gap.
8. **0008 — `Telescope.fromMap(...)` for untyped sources.** (Accepted, shipped #150.) Forward-only factory;
   `extract(key, accessor, converter)` rows; lenient default.
9. **0009 — `@Bridge(lenient = true)`.** (Accepted, shipped #148.) Codegen sibling of Enh 9's `mapperForward` lenient
   default. Opt-in flag; partial-Iso when on.
10. **0010 — `@FromMap` codegen.** (Accepted, shipped.) Reflection-free `Map<String, Object> → record` ingestion —
    codegen sibling of ADR-0008's runtime `fromMap`.
11. **0011 — Carrier-`@Bridge` runtime discovery via `ServiceLoader` SPI.** (Accepted.) `BridgeProvider` /
    `BridgeRegistry` in `:core/conversion` let the runtime `mapperForward` find carrier-form codegen bridges.
12. **0012 — Compile-time mapper verification via a shared pairing spec.** (Accepted.) `internal/pairing` —
    `PairingRules` + `PropertySystem`, one decision implementation for both the runtime mapper and the compile-time
    verifier, `PairingMessages` as the single diagnostic source.
13. **0013 — Introspectable optics via `explain()` / `trace()`.** (Accepted.) `:core/introspection` — `OpticNode`,
    `OpticReport`, `Trace`, `TraceLimits`.
14. **0014 — Auto-logging explain()/trace() via `System.Logger`.** (Accepted.) Flip a log level and mappers narrate; no
    logging dependency added.
15. **0015 — Native-image AOT support for the runtime path.** (Accepted, shipped #250; qualifies ADR-0005 under AOT.)
    Wall B (runtime `LambdaMetafactory` class definition, forbidden by AOT) fixed in the substrate:
    `NativeImage.IN_IMAGE` gates every accessor builder to `MhAccessors` `MethodHandle` closures inside an image, LMF
    stays the JVM hot path. Wall A (`SerializedLambda`) = app-level `serialization-config`. Core ships its own
    `native-image.properties`; a planned `telescope-graalvm` Feature module was dropped as unnecessary (amendment). The
    `:examples:graphql` `NativeVerify` binary (nine capabilities) is the CI regression gate.

When making a load-bearing design choice that future-you might want to re-litigate, add a numbered ADR rather than
burying the rationale in a code comment.

---

## Roadmap and open work

Shipped work is recorded in `git log`, the CHANGELOG, and the ADR index above; it is not duplicated here. Open work
lives in the issue tracker, labelled `performance` where it came out of the audit. Those issues each carry a mechanism,
a measurement, and a proposed verification, which makes them the easiest ones to pick up cold.

Whatever you pick up, the two things a reviewer will ask for are the ones the mantras name: a measurement with a
control, and a test that fails on the unfixed code.

## Recurring traps

Collected because each of these has cost a round of review or a red build more than once.

- **A hash container's `int` constructor takes a table capacity, not an element count.** `new HashSet<>(n)` filled with
  `n` elements resizes once `n` exceeds three quarters of the smallest power of two **at least** `n` — the top quarter
  of each band, so 13 through 16 resize while 9 through 12 do not. The non-monotonicity is the part that catches people:
  16 resizes and 17 does not. Use `HashSet.newHashSet(n)` and its siblings. `ArrayList(n)` _is_ an exact element
  capacity; `ConcurrentHashMap` and `IdentityHashMap` already size for the count internally. Do not "fix" those two.
- **Generated code must read each source property exactly once.** A conversion expression that names its read twice
  double-evaluates a defensive-copy getter, resolves a lazy proxy twice, and can dereference null when a volatile getter
  returns non-null then null. Hoist into a local; `BridgeProcessor.withPrelude` is the idiom.
- **`getAll` and `visitWhile` enumerate the same focuses, and `visitWhile` is the one that stops.** Anything that
  answers with one focus, or that folds eagerly, should ride `visitWhile`. `FoldLaws` pins the equivalence.
- **Lombok-touching processors must defer emission to `processingOver()`.** Lombok's AST patches may not have fired in
  round one, so member lookups return an un-patched view.
- **Every runtime `LambdaMetafactory` site needs the `NativeImage.IN_IMAGE` branch** to its `MhAccessors` equivalent, or
  the native-image workflow goes red.
- **Spotless for Java runs google-java-format first and prettier last.** Running only one of them leaves violations the
  other would have caught.
