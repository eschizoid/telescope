# ADR-0016: `telescope-jackson` — Jackson integration via a mapper-backed module and a streaming `JsonBinder`

**Status:** Accepted — grilled 2026-09-07 (two adversarial review passes; 28 findings folded in) · **Date:** 2026-09-07

## Context

Every real MapStruct deployment already runs a two-hop pipeline: JSON → wire DTO (Jackson databind) → domain type
(MapStruct). Telescope owns the second hop today (`Telescope.mapper`, `@Bridge`), but the seam between the hops is
hand-wired in every codebase: deserialize to the wire type, call the mapper, remember to do it in both directions.
Owning that seam cleanly is squarely on the north star (dethrone MapStruct), and MapStruct itself has no Jackson story.

Separately, ADR-0008/ADR-0010 established the untyped-boundary family: `fromMap` (runtime) and `@FromMap` (codegen) turn
`Map<String, Object>` into records/POJOs. JSON is the other ubiquitous untyped source, and routing it through an
intermediate `Map` pays a full tree allocation plus databind's reflection. `jackson-core`, the streaming half of
Jackson, is reflection-free.

A boundary principle keeps scope sane: **telescope starts where types start.** Jackson parses; telescope binds and maps.
No optics over `JsonNode` — a `JsonNode` has no fields to method-reference, so any such layer would be string-keyed
navigation, the exact thing telescope exists to replace, in a space already crowded (JsonPath, JsonPointer).

## Decision

Add a `telescope-jackson` module targeting **Jackson 3.x** (`tools.jackson`) with two halves and a codegen phase.

**Why 3.x from the start:** the repo's `telescope-spring-boot-starter` already targets Spring Boot 4, whose Jackson line
is 3.x. Building the integration on 2.x would lag telescope's own starter platform, and every mechanism class in this
design is renamed in 3.x — straddling both lines doubles the surface for an audience that is already on 3.x. A 2.x
backport is a later, adopter-gated decision. Class names below use their 3.x forms (`JacksonModule`,
`ValueDeserializer`/`ValueSerializer`, `tools.jackson.core.JsonParser`).

**Verified against the 3.x migration guide (pre-build):** coordinates are `tools.jackson.core:jackson-core` and
`tools.jackson.core:jackson-databind`; annotations deliberately keep the `com.fasterxml.jackson.annotation` package and
coordinates (the domain-annotation registration check targets those); `resolve()`/`createContextual()` are folded into
`ValueDeserializer`/`ValueSerializer` (the separate `Resolvable*`/`Contextual*` interfaces are gone — the delegating
pattern's lifecycle argument holds, through the merged methods); the whole exception hierarchy is unchecked
(`JacksonException` root), which the error-taxonomy section below relies on. Still to verify against 3.x javadocs in PR
1: the 3.x JPMS module names, the delegating serializer/deserializer class names, and the module type-id deduplication
behavior.

### 1. `TelescopeModule` — mappers as Jackson converters

A `JacksonModule` built by explicit registration: `mapperBuilder.addModule(TelescopeModule.of(mapper1, mapper2, …))`.
For each registered `Mapper<Wire, Domain>`:

- **Mechanism: Jackson's own delegating primitives, not hand-rolled (de)serializers.** Each direction is a Jackson
  `Converter<Wire, Domain>` / `Converter<Domain, Wire>` wrapped in the standard delegating deserializer/serializer
  (`StdDelegating*` in 2.x terms; 3.x equivalents). The delegating pair — unlike hand-rolled `ValueDeserializer`
  overrides — already forwards polymorphic type handling (`deserializeWithType`/`serializeWithType`, whose base-class
  defaults throw on `@JsonTypeInfo`), performs eager wire-handler resolution at the correct lifecycle point
  (resolve/contextualization, where a `DeserializationContext` actually exists), honors per-property contextual
  annotations on the wire side (`@JsonFormat`, views), and implements `isEmpty` for `@JsonInclude(NON_EMPTY)` by
  converting then asking (one documented extra `backward()` call).
- **Null contract:** Jackson never calls `deserialize()` for an explicit JSON `null` — mapper hooks never see JSON null;
  null handling is the wire type's Jackson configuration. Stated here so nobody "fixes" it later.
- **Serialization is always symmetric.** `writeValue(domain)` runs `backward(domain) → wire` and serializes the wire
  shape. Registration granularity is the control surface: register a mapper and both directions are mapped; want native
  domain serialization, don't register that mapper (or use a separate `ObjectMapper`). Write-side lookup walks the
  runtime class's supertype chain against the registry, so domain subclasses (including Hibernate proxies, which
  telescope accommodates elsewhere) still hit their registration.
- **Fail-fast registration.** `TelescopeModule.of(...)` throws `IllegalStateException` at construction for every shape
  that would misbehave silently at runtime:
  - a lifted mapper (`liftList()` etc.) — its source/target erase to raw container classes and would key the registry on
    `List`/`Set`/`Map`, hijacking every such value in the `ObjectMapper` (field-level generics like `List<Domain>` need
    no lifting: Jackson's collection deserializer resolves the element handler through the same lookup);
  - `Wire == Domain` — the delegate lookup would re-enter the module: unbounded recursion;
  - a wire class equal to another registration's domain class — silent mapper chaining or cycles;
  - two registrations sharing a domain class — last-write-wins by varargs order otherwise; the error points at separate
    `ObjectMapper`s;
  - a domain type carrying its own Jackson (de)serialization annotations — Jackson's precedence differs by annotation
    kind (`@JsonDeserialize(using=…)` beats the module; `@JsonCreator` silently loses to it), so the conflict must be
    loud instead;
  - a non-bean domain type (enum, reference shape) — those resolve through per-shape lookup hooks the module does not
    override, so mapping would be silently skipped.
- **Module identity:** `TelescopeModule` overrides the module type-id to a per-instance value — Jackson dedupes module
  registrations by type id by default, and without the override a second `TelescopeModule` is dropped with zero
  feedback.
- **Recursion/nesting posture:** domain types map at every nesting depth — including a domain-typed field _inside_ a
  wire type (each wire property resolves through the same lookup). The one rejected shape is the self-recursive pair (a
  wire type containing its own domain type), caught by the wire-equals-domain checks above. This "maps everywhere"
  behavior is the feature, stated so it is not mistaken for a leak.
- **Documented limitations (not design changes):** a domain type used as a `Map` key needs a key (de)serializer the
  module does not provide; `@JsonUnwrapped` on a domain-typed property is unsupported (Jackson only unwraps bean
  shapes); `convertValue(obj, Domain.class)` routes through the module and therefore expects wire-shaped input.

The Quarkus/Spring starters can later auto-build the module from their `TelescopeMapperRegistry` beans; that is a
starter-side convenience, not part of this module's contract.

### 2. `JsonBinder<T>` — streaming ingestion

`TelescopeJson.fromJson(Target.class, rows…)` — **hosted in `telescope-jackson`, not on `Telescope`**: a factory whose
surface names `JsonParser` cannot live in `:core` without dragging Jackson into the core API, which the Consequences
section forbids. It returns a **`JsonBinder<T>`** — reusable and thread-safe, with `read(String)`, `read(InputStream)`,
`read(byte[])`, `readList(String)`, `readList(InputStream)` (root JSON arrays → `List<T>` — the "endpoint returns a
list" shape is v1 surface, not an afterthought), and `parse(JsonParser)` entry points over a shared internal
`JsonFactory`. A dedicated type rather than `ForwardMapper<JsonParser, T>` because a `JsonParser` is stateful and
single-use — pretending it is a mappable source value invites lifecycle bugs and broken composition.

`parse(JsonParser)` pins the positioning contract explicitly: if `currentToken()` is null (fresh parser) it advances
once; it then requires `START_OBJECT` (or `START_ARRAY` via the list forms). This makes the binder composable inside
custom deserializers, where Jackson hands over a parser already positioned on the first value token.

**Binding model — name-driven with extract overrides.** Every target component auto-binds to its same-named JSON field
through the coercion taxonomy; `extract(jsonName, accessor, converter)` rows override individual fields (rename, custom
conversion). This is deliberately the `@FromMap` **codegen** model, not the runtime `fromMap` row-driven model — the
consistency that matters is with the `@FromJson` codegen twin (same feature, two tiers, identical semantics), and a JSON
binder that required one row per field would be unusable on real payloads. Runtime `fromMap` stays row-driven for `Map`
sources; the divergence is recorded here as intentional, not drift. (Design note: consistency with the existing family
was weighed and preferred in the abstract; the third model won because the two existing models already disagree with
each other, so "consistent" had no single referent.)

The engine is a **recursive-descent token loop** over `jackson-core`'s `JsonParser` — no intermediate `JsonNode` or
`Map`. One name→slot table per binder frame (so `id` at the root and `id` inside a nested object cannot collide);
same-level duplicate JSON keys take last-wins (matching databind's tree behavior); unknown fields cost one
`skipChildren()`. Slot values fill a positional args array; construction is one cached canonical-constructor invocation
(records) or writer construct (beans).

Semantics, precisely:

- **Lenient:** a missing JSON field and an explicit JSON `null` both produce the same result — the `NullDefaults` value
  for auto-bound components, `converter.apply(null)` for extract-row components (the two-tier behavior runtime `fromMap`
  already has). Unknown JSON fields are skipped. Extract rows naming a nonexistent target component fail loudly at build
  time.
- **Lossless number coercion, hand-enforced:** jackson-core's typed getters do **not** provide the strictness —
  `getIntValue()` on a fractional token silently truncates. The binder gates on the token kind: `VALUE_NUMBER_INT` binds
  to integral slots, any number token binds to `double`/`BigDecimal`, a fractional token into an integral slot throws
  `IllegalArgumentException` naming the field and token. Integral overflow surfaces as jackson-core's
  `InputCoercionException`; the loop catches it and rethrows as the same documented `IllegalArgumentException` (field,
  token text, location) — it is a coercion failure, not malformed JSON. Cross-kind coercions (string→number) are
  rejected; explicit `extract` converters exist for that.
- **v1 taxonomy:** scalars, nested records (recursive sub-binder), `List<E>`/`Set<E>` from arrays, `Map<String, V>` from
  objects, `Optional<E>` (JSON null/missing → `Optional.empty()`), and the `StringFactory` types (UUID, Instant, …) —
  the `@FromMap` coercion taxonomy, reused verbatim by the codegen twin.
- **Errors:** build-time validation throws `IllegalArgumentException`; malformed JSON propagates jackson-core's parse
  exception (it carries location); coercion failures throw the documented `IllegalArgumentException`.
- **Introspection:** `explain()` returns the build-time binding plan (per-component: bound name, coercion, override row,
  or default) — fully build-time-derivable because the model is decided per component at build. Runtime-only events
  (unknown-field skips) belong to `trace()` (ADR-0014), not `explain()`.

### 3. Codegen phase (second PR): `@FromJson` + generated module

- **`@FromJson`** (annotation and processor both hosted in `telescope-jackson`, the `telescope-lombok` pattern:
  string-FQN trigger, graceful no-op when absent) emits a sibling `<X>FromJson` with a generated parser loop —
  reflection-free, `jackson-core`-only. **This is the sole AOT-clean tier**, mirroring ADR-0010.
- **Generated `TelescopeGeneratedModule`** aggregating the annotated pairs, registered via the ServiceLoader mechanism,
  with the discovery claim scoped honestly:
  - classpath apps using Jackson's module auto-discovery: works as-is;
  - **Spring Boot does not call module auto-discovery** — Boot registers `JacksonModule` _beans_; the existing
    `telescope-spring-boot-starter` exposes the generated module as a `@Bean` (one conditional in
    `TelescopeAutoConfiguration`);
  - **JPMS apps:** `ServiceLoader` ignores `META-INF/services` on the module path; the user adds
    `provides … with <generated module>` to their `module-info.java` — a processor cannot inject it; documented as user
    responsibility.

### Shipping order

Two PRs: **PR 1** = module skeleton + `TelescopeModule` + streaming `JsonBinder` runtime + this ADR. **PR 2** =
`@FromJson` codegen + generated-module wiring. The runtime PR pins the taxonomy and semantics the codegen twin reuses.

## Consequences

- `telescope-core` stays dependency-free and Jackson-free — including its API surface: no `fromJson` on `Telescope`.
- **JPMS:** `:internal` adds qualified exports of the substrate packages the binder engine needs (`Records`, `Beans`,
  `NullDefaults`, `LambdaIntrospection`, `pairing`) to `io.github.eschizoid.telescope.jackson` — the exact precedent of
  `pairing`'s export to the codegen module. `telescope-jackson` declares `requires static` databind and
  `requires transitive` jackson-core, so `fromJson`-only deployments can omit databind from the module path entirely
  (`TelescopeModule` users have databind present by definition).
- **Native-image, honestly:** the runtime `JsonBinder` inherits Wall A (ADR-0015) exactly like runtime `fromMap` — its
  accessor method references need `SerializedLambda`, which dies under native-image without app-level
  serialization-config. Databind-unreachability is true but irrelevant to whether the binder runs. The AOT headline
  belongs to `@FromJson` codegen alone.
- **Lattice carve-out (mantra 3):** the token loop is boundary machinery at the same floor as the `Records`/`Beans`
  assembly seams — a streaming parser has no lawful optic decomposition (no `getAll` without consuming the source, no
  reversible write). `JsonBinder` is therefore an engine like `FromMap`/`Merge`, not a lattice value; anything it
  produces or composes _after_ construction (nested-record sub-binders reuse the cached structural machinery) stays on
  the existing substrate. This paragraph is the documented deviation the mantra requires.
- The forward-only family (`mapperForward`, `fromMap`, `fromJson`) keeps lenient-by-default; a strictness toggle, if
  ever requested, lands across the family, not per-engine.
- `Mapper` values become directly usable as Jackson infrastructure — the first integration where telescope's
  bidirectionality does work MapStruct users hand-wire.

## Alternatives considered

- **Optics over `JsonNode`** — rejected: string-keyed navigation abandons the typed-method-reference differentiator in a
  crowded space.
- **`JsonNode`-tree `fromJson` v1** — rejected for streaming from day one: the tree variant allocates the intermediate
  the streaming engine exists to avoid.
- **Hand-rolled (de)serializers for the module** — rejected during the grill: the delegating-converter primitives
  already solve polymorphism, contextualization, null routing, and resolution timing that a hand-rolled pair breaks.
- **Row-driven `fromJson` (runtime-`fromMap` consistency)** — rejected: one row per field is unusable on real JSON, and
  the codegen twin would diverge anyway; the two existing family models already disagree, so there was no single
  "consistent" choice to preserve.
- **`@JsonDeserialize(converter=…)` stamped on domain types** — rejected: pollutes domain types with Jackson coupling.
- **Jackson 2.x first** — rejected: the repo's Spring Boot 4 starter is already on the 3.x line; a 2.x backport is
  adopter-gated.
- **Engine in `:core` behind a Jackson-free token SPI** — rejected: an abstraction with exactly one implementation; the
  qualified-export precedent is cleaner.
