# ADR-0008: `Telescope.fromMap(Class<T>, MapExtractStep...)` for untyped sources

**Status:** Accepted — shipped in #150 · **Date:** 2026-06-16 (accepted 2026-06-21, amended 2026-10-10)

## Context

A meaningful slice of real codebases interacts with untyped sources: `Map<String, Object>` returned by JDBC `ResultSet`
unwrappers, raw JSON nodes from a framework's request body parser, message bus payloads decoded as a flat map. Today,
telescope has no first-class shape for these — the closest workaround is `Telescope.all(Edit.over(...))` on a
pre-allocated target, which is imperative on the extraction side and loses every type guarantee telescope normally
provides.

MapStruct hits the same wall and punts: its `@Mapper` interface can declare a method whose source is
`Map<String, Object>`, but the user must write a `@AfterMapping` callback that does the typed extraction by hand.
There's no contractual middle ground.

The migration-feedback report from a 12-mapper MapStruct → telescope adopter flagged this as a Low-priority enhancement
because the workaround works — but every adopter who hits it loses the static-type story for the affected mapper. The
asymmetry is bad marketing more than it is bad code.

## Decision

Add a forward-only factory:

```java
ForwardMapper<Map<String, Object>, CaseListRequest> m = Telescope.fromMap(
  CaseListRequest.class,
  extract("bookingType", CaseListRequest::getBookingType, Extractors::firstStringOrValue),
  extract("caseId", CaseListRequest::getCaseId, Object::toString),
  extract("priority", CaseListRequest::getPriority, (v) -> Integer.parseInt(v.toString()))
);
```

`extract(...)` is a new static factory on a new sealed `MapExtractStep` interface (sibling of `MapStep`). Each row
carries:

- The map key (as a `String`).
- The target accessor (`Function<T, X>` method reference) — recovered via `SerializedLambda` so the field name + type
  flow into the rebuild step exactly like today's `Mapping.to(...)`.
- A `Function<Object, X>` value-converter that consumes the raw map value and produces the typed target value.

The factory returns a `ForwardMapper<Map<String, Object>, T>` — not a bidirectional `Mapper`, because the backward
direction (`T → Map<String, Object>`) loses information by design and adopters who genuinely need it should write it
explicitly. Lenient by default: missing map keys → JLS-default target value via `NullDefaults`; unmatched map keys →
silently ignored.

## Consequences

- **Untyped-source codebases keep telescope's compile-checked story for everything downstream.** The map is the boundary
  layer; once you're past `fromMap(...)`, you have a `ForwardMapper<Map<String, Object>, T>` you can `.then(...)`-chain,
  lift into a list with `liftList()`, and inject as a CDI/Spring bean exactly like any other ForwardMapper.
- **No new internal substrate.** The factory routes through `DeepMap` with a synthetic source-side reader: the
  per-component "read this field's value" Function returned by `Reflective` becomes
  `map -> converter.apply(map.get(key))`. The target-side construct path is unchanged. The lattice still holds.
- **Lenient-by-default is symmetric with `mapperForward(...)`.** Telescope's forward-only family (`mapperForward`,
  `asForwardMapper`, `fromMap`) all share the same JLS-default-on-miss + silent-ignore-on-extra semantics, matching
  MapStruct's default for every generated mapper.
- **`MapExtractStep` is sealed.** Today's permits: `Extract`. Future expansion (nested extracts, conditional gates,
  required-key validation) extends the sealed surface — same pattern as `MapStep` already follows.
- **Static-import friendly.** `extract(...)` is intended to be static-imported alongside `Mapping.to` / `Mapping.via`:
  the call site reads as a list-of-rows with no `Telescope.` qualifier noise on each line.
- **Performance is intentionally below the typed path.** Map lookups are O(1) HashMap probes, not JIT-inlined field
  reads; the converter `Function<Object, X>` is a virtual call, not a method-reference. Adopters hit this for legacy
  code; the documented expectation is "this is the cost of unstructured input, run it at request-boundary not in a hot
  inner loop."

## Amendment: a component no row names reads the key with its own name

**Date:** 2026-10-10

As first shipped, a component no `extract(...)` or `required(...)` row named was never read: it always took the default
for its type. The `@FromMap` binder of ADR-0010 reads every component from the key with its own name, so the two paths
gave different objects for the same map, and a mapper with no rows returned a target holding only defaults.

`fromMap` now follows the rule `Telescope.map` follows: same-name binding is the default and rows are overrides.

- A component no row names reads the map key with its own name and converts the value exactly as the generated binder
  does. Which kind of conversion a declared type gets is decided once, by `MapValueTypes.classify` in the shared pairing
  spec, over a type model each path implements. The processor renders the kind as source and the runtime as a function.
- A component whose type has a generated binder is built by that binder, found through its `FromMapProvider`, which
  gains a `binder()` method. Building it through the binder keeps what only the source-retained annotation declares,
  such as required keys.
- A target with a generated binder honours its own `@FromMap(required = ...)` for every component no row names, read
  through the provider's new `required()` method, so a map the binder refuses is refused with the same message.
- A row decides the component it names: its key and its converter replace the by-name default, and an `extract(...)` row
  lifts a declared requirement.
- A key absent from the map, or holding `null`, still leaves the documented default. `required(...)` is unchanged.
- A type with no conversion is still refused while the mapper is built, with the same message. A component whose type's
  provider does not override `binder()`, and a target whose provider does not override `required()`, are refused while
  the mapper is built too, since neither can be built as the generated binder builds it.
- A bean property no row names and the writer cannot set is not read. `explain()` reports it as a `MISSING_SOURCE` skip
  and every other component as an `Extracted` row under the key it reads.

This changes behaviour: a component that used to stay at its default now takes the map's value under its name, a value
under that name that cannot be converted now throws where it used to be ignored, a map missing a required key of the
target or of a nested type with a binder is now refused, and a mapper over a type whose provider lacks `binder()` or
`required()` is refused at construction.

## Alternatives considered

- **Inverse direction `Telescope.toMap(...)`.** Rejected for v1.x. The `T → Map<String, Object>` direction is what
  `MapperBuilder.into(Map.class, target)` already does loosely via reflection-walked components; making it first-class
  requires picking a key-shape policy (verbatim field names? camelCase → snake_case? configurable?) that's a separate
  ADR-worthy decision and out of scope here.
- **Polymorphic source — `JsonNode`, `org.json.JSONObject`, `bson.Document`, etc.** Rejected for v1.x. Each library has
  its own per-type read shape, and pulling them into telescope as compile dependencies is the wrong direction. Adopters
  needing those wrap them: `JsonNode → Map<String, Object>` first (their framework already does this), then
  `Telescope.fromMap(...)`.
- **Typed `Symbol<X>` marker keys (`extract(KEYS.bookingType, ...)` with a `Symbol<X>` table declared once).** Rejected.
  The actual shape on the wire is string-keyed — JDBC `ResultSet#getColumns`, framework request-body parsers, and
  message-bus payload decoders all produce `String → Object` maps. Forcing adopters to declare a typed `Symbol<X>` table
  just to call the factory adds boilerplate without removing the underlying string-lookup step (the framework still
  emits string keys). The runtime converter `Function<Object, X>` is the right type-recovery point.
- **Reuse `Mapping.to(...)` with a `Map.Entry`-like source accessor.** Rejected. The source side has no compile-time
  type, so `SerializedLambda`-based field-name recovery doesn't work, and the row would have to invent a different shape
  for its source identifier. A separate `MapExtractStep` interface keeps the typed-row infrastructure (`Mapping`)
  unburdened by the untyped corner case.
- **Annotation-driven — `@MapSource` on a target field with a `key` attribute.** Rejected for the runtime path. Could
  surface in codegen as a sibling enhancement, but the runtime factory needs to land first; codegen always trails
  runtime by at least one release cycle (per [ADR-0004](0004-runtime-and-codegen-strategy-separate.md)).
- **Do nothing — workaround works.** Rejected for the same reason as Enh 1 (cross-module `@Bridge`): the workaround
  works mechanically but throws away the compile-checked story for any mapper that touches an untyped boundary, and
  every adopter hitting this experiences the same loss in isolation.

## See also

- [ADR-0007](0007-cross-module-bridge-carrier.md) — sibling v1.1+ enhancement, cross-module `@Bridge` carrier
- [ADR-0009](0009-bridge-lenient-mode.md) — sibling v1.1+ enhancement, codegen `@Bridge(lenient = true)`
- [ADR-0004](0004-runtime-and-codegen-strategy-separate.md) — runtime-vs-codegen separation that pushes `fromMap` into
  the runtime path first
