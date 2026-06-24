# ADR-0010: `@FromMap` codegen for reflection-free, native-image-clean `Map<String, Object>` ingestion

**Status:** Accepted — shipping in the `@FromMap` codegen PR · **Date:** 2026-06-24

## Context

ADR-0008 added the runtime `Telescope.fromMap(Class<T>, MapExtractStep...)` factory for untyped `Map<String, Object>`
sources. It works on the JVM, but it recovers each field name from its `User::name` method reference via
`SerializedLambda` (reflective `writeReplace` + `setAccessible`) — and that is the one machinery GraalVM native image
cannot run: native-image does not synthesize `writeReplace` on non-serializable lambdas, so the runtime `fromMap` path
**builds but dies at runtime** with `NoSuchMethodException: …$$Lambda/…writeReplace()`, even after native-image
auto-registers ~1,000 reflection entries.

This is exactly where the untyped-source use case bites hardest. An adopter building a GraalVM-native GraphQL service
hits it directly: every graphql-java argument arrives as a `String`, primitive, enum name, or nested `Map`, and the
usual `Jackson.convertValue` conversion is reflection-heavy and a chore to register for native image — the burden a
library shouldn't dump on its users. The runtime `fromMap` doesn't help here because it is itself reflection-bound.

ADR-0004 already established that telescope keeps a runtime tier and a codegen tier as **separate strategies**, and
ADR-0006 established the codegen↔runtime contract (a generated sibling class surfacing a public constant). The Map→POJO
case had a runtime tier (ADR-0008) but no codegen tier. This ADR adds it.

## Decision

Add a `@FromMap` annotation (records and JavaBeans) processed by `FromMapProcessor`, emitting a sibling `<X>FromMap`
class with a `public static X fromMap(Map<String, Object>)` method and a
`public static final ForwardMapper<Map<String, Object>, X> FROM_MAP` constant. The method rebuilds the target directly —
record canonical constructor, or bean builder / no-arg-ctor + setters — with each map value coerced inline. No
`SerializedLambda`, no `LambdaMetafactory`, no reflection: the generated code is native-image clean by construction.

Load-bearing design points:

- **Per-field coercion is a sealed `Coercion` model** (`Cast`/`Parse`/`BoolParse`/`CharParse`/`EnumOf`/`Nested`/
  `Listed`/`Setted`/`MapValues`/`OptionalOf`/`StringFactory`/`Unsupported`). Each emits a Java expression; container
  strategies compose recursively over their element coercion, so arbitrarily nested shapes
  (`Map<String, List<@FromMap>>`, `Optional<List<X>>`) work. Generated container collectors and empty fallbacks carry
  explicit type witnesses (`.<Map<K,V>>collect`, `List.<E>of()`) so they type-check at any nesting depth.

- **The lattice surface is `FROM_MAP`, a `Getter`-backed `ForwardMapper`; the generated body is direct.** Map→POJO is
  forward-only (no faithful inverse), so the correct lattice member is `Getter` (the read-only weakening), which
  `ForwardMapper` wraps. The generated converter lives in the consumer's package, which JPMS does not let see
  `internal.optics.*` (qualified-exported to `:core` only) — so the direct rebuild body is _forced_, not a shortcut, and
  the public `ForwardMapper` constant is the maximal reachable lattice surface. This follows how `@Bridge` surfaces a
  public lattice constant over a direct rebuild body (ADR-0006); `@Bridge` emits a bidirectional `Telescope<A, B>`
  because a bridge has a faithful inverse, whereas Map→POJO does not, so `@FromMap` emits the forward-only
  `ForwardMapper`.

- **Uncoercible fields are compile errors, not runtime failures.** A nested object whose type isn't `@FromMap`, a
  collection subtype (`ArrayList<X>`), a type variable, or an unrecognized JDK type carry no defensible cast, so the
  processor reports a guiding error and skips the converter — upholding "if it compiles, it runs."

- **Known String-carried JDK value types are supported** (`Instant`/`LocalDate`/`UUID`/`BigDecimal`/`URI`/… via their
  String factory), since an untyped map carries these as Strings. Unrecognized JDK types are rejected with a pointer to
  the runtime `fromMap` + a custom `extract` converter.

- **Lombok `@Data`/`@Value`/`@Builder` targets are round-deferred** to `processingOver()`, so the synthesized accessors
  are visible — the same pattern the other telescope processors use for Lombok.

- **Coercion is lenient, matching the runtime `fromMap`** (ADR-0008): absent key → JLS default; wrong-shaped container
  value → empty collection; only `"true"` is truthy; a non-numeric numeric value throws.

The `@Extract`-style per-field converter override is deferred until an adopter needs it — the convention + standard
coercions cover the motivating cases.

## Consequences

- **A GraalVM-native service can do Map→POJO with zero reflection config.** Consumed via the `FROM_MAP` constant (or a
  direct `XFromMap.fromMap(...)` call), the generated converter native-images `--no-fallback` with no hand-written
  `reflect-config.json`. The native A/B is the rationale in one line: the runtime `fromMap` dies on `writeReplace`; the
  generated converter just works.

- **The two-strategy split (ADR-0004) is complete for the Map→POJO case.** JVM code keeps the ergonomic runtime
  `fromMap`; native (or hot-loop) code annotates with `@FromMap` and gets the reflection-free sibling. Both return the
  same `ForwardMapper`-shaped value.

- **`@FromMap` is a secondary capability, not a new identity.** Map→POJO ingestion is an untyped-source concern adjacent
  to telescope's typed-optics core; it earns its place by closing a native-image gap MapStruct also leaves open, not by
  redefining what telescope is.
