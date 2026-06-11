# ADR-0006: Codegen ↔ runtime 1:1 lookup via a sibling metadata holder

**Status:** Accepted (extends [ADR-0004](0004-runtime-and-codegen-strategy-separate.md), refines
[ADR-0005](0005-lambdametafactory-over-method-handle-invoke.md)) · **Date:** 2026-06-07

## Context

After Phases 1-5 of the LambdaMetafactory substrate swap landed
([ADR-0005](0005-lambdametafactory-over-method-handle-invoke.md)), the runtime path through `Telescope.of(Class)` /
`Telescope.ofBean(Class)` / `Telescope.map(Class, Class, ...)` no longer pays `Method#invoke` per call — cached
`Function` / `BiConsumer` / `Supplier` SAMs sit at codegen-comparable per-call cost (~15-35 ns/op vs codegen's ~15
ns/op).

What remains in the runtime path is **metadata-probing reflection**: per-class scans for component names, generic types,
and getter/setter resolution. Those probes:

- Run once per class (cached behind `ClassValue`), so they don't dominate hot-path cost.
- Are the **last reflection** in the runtime story. Eliminating them closes the "reflection in the runtime" question
  end-to-end for users who opt into codegen.
- Force the runtime entry points to import `java.lang.reflect.*` and `javax.lang.model.*` paths that complicate the
  architecture diagram.

[ADR-0004](0004-runtime-and-codegen-strategy-separate.md) deliberately kept runtime and codegen as **separate
strategies**. Users who annotate switch their call sites to `UserPath.focus().name()` — different API entry. Users who
don't, stay on `Telescope.of(User.class).field(User::name)` with the reflective metadata probe.

The drawback of that split: a user who **wants** codegen ergonomics (no reflection) **and** the runtime entry point
(ergonomic, zero-config) has no path. They pick one. The runtime path can never drop its metadata reflection because
non-annotated users have no other way for the runtime to discover structure.

`@Bridge` already provides the architectural precedent for the resolution: it emits a
`public static final Telescope<Source, Target> BRIDGE` at compile time, and the generated navigator references it
directly by name. Zero runtime reflection for the whole-record conversion; structurally typed at compile time. The shape
generalises.

## Decision

`FocusProcessor` and `BeanFocusProcessor` (and `LombokFocusProcessor` via the round-deferred emission pattern) **also**
emit a sibling metadata holder class — `<X>Telescope` — alongside the existing `<X>Path<R>`. The holder exposes one
`public static final Telescope<X, FieldType>` constant per record component / bean property.

At runtime, the dispatch sites that consume metadata (`Telescope.of(Class).field(Accessor)`,
`Telescope.fieldByName(String)`, `Telescope.map(Class, Class, ...)`) consult a `ClassValue<Optional<HolderRef>>` cache
**before** falling through to today's `Reflective.of(Class)` path. When the holder is present, the
SerializedLambda-derived field name routes to the pre-baked constant directly. When absent, today's LMF substrate path
runs unchanged.

The split from [ADR-0004](0004-runtime-and-codegen-strategy-separate.md) stays — runtime and codegen remain distinct
entry points with different ergonomics. What changes is the **interior** of the runtime path: for annotated types, it
becomes a constant lookup rather than a reflective probe + LMF dispatch.

Phased rollout (each phase is a discrete PR, no public API change):

- **Phase A — emit `<X>Telescope`.** `FocusProcessor` and `BeanFocusProcessor` emit the sibling holder. Lombok
  piggybacks on the existing `processingOver()` deferral. Pure additive: no runtime consumer yet.
- **Phase B — runtime probe.** `ClassValue<Optional<HolderRef>>` short-circuit added to the dispatch sites in
  `Records.fieldLens(...)` / `Beans.lens(...)`. Holder-present types navigate via constants; holder-absent types fall
  through to today's LMF path unchanged.
- **Phase C — deep-mapping uses constants.** `Reflective#structuralIso(cls)` probes for a sibling `<X>Telescope` and,
  when present, pre-resolves a per-component `Lens` table. The backward branch (instance → name-keyed `Map`) reads via
  those lenses instead of routing through `Records.read` / `Beans.readProperty`. Holder-absent types and partial holders
  fall through to the reflective `read` path unchanged. The forward branch (`construct`) is untouched — the holder
  doesn't expose a constructor primitive, so canonical-ctor / `BeanWriter` dispatch remains. Net effect: for type pairs
  where both sides are annotated, every per-component value read during deep-mapping decomposition uses a pre-baked
  lens.
- **Phase D — holder construct(...) closes the forward branch.** The `<X>Telescope` holder gains a
  `public static <X> construct(Function<String, Object> values)` method that mirrors the same write strategy the
  `<X>Path<R>` lenses use (canonical constructor for records; builder chain or no-arg ctor + setters for beans). The
  `MetadataHolderProbe.HolderRef` adds an optional `Function<Function<String, Object>, Object> constructor` field bound
  once via `LambdaMetafactory` at probe time. `Reflective#structuralIso(cls)`'s forward branch routes through it when
  present, bypassing the reflective `Records.construct` / `Beans.BeanWriter` path; older holders that predate Phase D
  surface a `null` constructor and the engine falls back to today's reflective path unchanged. Net effect: for type
  pairs where both sides are annotated, both the forward (construct) and the backward (read) branches of `structuralIso`
  are reflection-free in the hot path; the only remaining runtime reflection is `SerializedLambda` decode on the user's
  accessor method references.
- **Phase E — holder constants() eliminates the probe's field scan.** The `<X>Telescope` holder gains a
  `public static Map<String, Telescope<?, ?>> constants()` method that returns the name → lens map directly.
  `MetadataHolderProbe.probe(...)` calls this method as the only path; a holder that's missing the method (out-of-date
  codegen on the classpath) trips a precise `IllegalStateException` rather than silently falling back. Net effect on the
  cold-path probe: from `~3 + N` reflective operations per probe (N = number of holder fields) to `3` operations
  regardless of N. The probe is `ClassValue`-cached, so this is a one-shot-per-class improvement, not a hot-path change
  — but it consolidates the holder's runtime contract into two named methods (`constants()` + `construct(Function)`)
  instead of a contract that depends on field-shape conventions. Pre-1.0 stance: no legacy fallback for older codegen
  output; users re-run the processor.

## Decisions encoded in the design

The scout report surfaced ten open design questions; each is resolved here so the implementation has no remaining
ambiguity:

1. **Holder location.** Top-level `public final class <X>Telescope` in the user's package, alongside `<X>Path` and
   `<X>Bridge`. Same shape as `@Bridge` output.
   `Class.forName(cls.getName() + "Telescope", false, cls.getClassLoader())` is the runtime probe.
2. **What the holder exposes.** Typed `public static final Telescope<X, FieldType>` constants per field. **No** `Type[]`
   or `Class<?>[]` metadata arrays — container detection happens at codegen time when the constant is emitted,
   sidestepping generic-erasure questions. The constant _is_ the typed lens.
3. **Holder naming.** Suffix convention `<X>Telescope` (e.g. `UserTelescope`). Matches `<X>Bridge` / `<X>Path`. No `$`
   prefix.
4. **`@Bridge` interaction.** `BridgeProcessor` stays as-is. The whole-record `BRIDGE` constant is already
   zero-reflection; composing it from per-field holder constants is a circuitous path with no perf gain.
5. **`Reflective` interface.** Unchanged. Phase B adds a probe **before** the `Reflective.of(cls)` dispatch, two-tier;
   no interface change.
6. **Cache type.** `ClassValue<Optional<HolderRef>>`. Matches the existing pattern (`Records.CACHE`, `Beans.GETTERS`,
   `Beans.GETTER_INVOKERS`, `Beans.AUTO_WRITER_CACHE`). `Optional` carries "checked but absent."
7. **Generics with bounds.** Codegen errors with a clear diagnostic when a component's type can't be emitted as a typed
   constant (wildcard-bound generics, self-referential bounds). Conservative posture matching the rest of the codegen
   story.
8. **Pre-baked `Mapper` patch tables.** Out of scope for Phases A-C. Future work; would either require a new
   `@MapTo(Target.class)` annotation or extending `@Bridge` semantics.
9. **Lookup miss.** Throws `IllegalStateException` with a precise diagnostic ("Component 'name' not found in `User`'s
   metadata holder. Re-run the @Focus processor."). Silent fallback would mask stale codegen or accessor mismatches.
10. **Lombok integration.** Holder emission uses the same `processingOver()` deferral the existing `<X>Path` emission
    already uses — Lombok AST patches arrive lazily; early emission would see un-patched members.

## Consequences

- **Zero call-site change.** Users who `@Focus`-annotate their records continue to write
  `Telescope.of(User.class).field(User::name).update(...)`. The dispatch transparently uses the constant under the hood.
- **For annotated types, the only remaining runtime reflection is `SerializedLambda` decode.** Method references carry
  no other type info; the JDK requires this probe regardless of substrate.
- **For non-annotated types, zero behavior change.** Phase B's short-circuit is opt-in via the holder's presence on the
  classpath.
- **Codegen output grows.** One additional `<X>Telescope.class` per annotated type, alongside `<X>Path.class` and any
  `<X><Comp>Step.class` files. Bounded by the surface of types the user explicitly annotates.
- **The `Telescope.map(A.class, B.class, ...)` path benefits most.** Today's reflective per-component name scan +
  per-component LMF bind per type pair becomes a per-pair `Iso<A, B>` composition from constants. This is where the
  hybrid's quantitative win is largest; the per-call navigation (`.field(...)`) is already at LMF parity with codegen
  post-ADR-0005.
- **The "is reflection still in the runtime?" question closes for codegen-opt-in users.** That was the qualitative bar
  the user set when reviewing the codegen output side-by-side with the LMF runtime path post-Phases 1-5.
- **JPMS opens requirement softens for annotated types.** The constant lookup doesn't go through
  `MethodHandles.privateLookupIn`; the holder is a public class in the user's package, accessible via plain
  `Class.forName(...)`. (The LMF substrate's `opens` requirement remains for non-annotated types.)
- **Acceptance gate before 1.0:** annotated runtime path should match codegen-path JMH numbers within noise (~5-15 ns/op
  for reads). See [`benchmarks/README.md`](../../benchmarks/README.md) for the methodology.

## Alternatives considered

- **Drop the runtime path, codegen-only.** Rejected by the user during the v1.0 readiness pass. The runtime entry point
  is the zero-build-config story — removing it would break `.fieldByName(String)` and force every consumer to add the
  annotation processor. [ADR-0004](0004-runtime-and-codegen-strategy-separate.md) records the original rejection; the
  user re-confirmed it here.
- **Status quo (Phases 1-5 land, no hybrid).** Rejected. The LMF substrate closed the per-call cost gap but left the
  metadata reflection in `Reflective.java` / `Records.componentNames` / `Beans.propertyNames` — visible enough that the
  user flagged it during the v1.0 readiness pass. Leaving it would mean shipping 1.0 with the "reflection still in the
  runtime" caveat and pushing the resolution to v1.1; user explicitly pulled the resolution into 1.0.
- **`<X>Telescope` as a nested class on `<X>Path`.** Rejected. Couples metadata to navigator presence at runtime; would
  break for consumers who have the holder on the classpath but stripped the Path class. Top-level is cheaper to reason
  about.
- **Expose `Type[]` / `Class<?>[]` metadata on the holder instead of typed `Telescope` constants.** Rejected. Forces the
  runtime to interpret reflection types again; the typed-constant shape sidesteps generic erasure entirely by moving
  container detection to codegen time.
- **Silent fallback on lookup miss.** Rejected. Defeats the purpose of opt-in codegen — users picked `@Focus` because
  they want compile-time-checked, fast dispatch. A miss signals a real problem and should surface loudly.
