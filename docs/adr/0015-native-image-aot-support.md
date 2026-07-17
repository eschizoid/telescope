# ADR-0015: Native-image AOT support for the runtime path

**Status:** Accepted (qualifies [ADR-0005](0005-lambdametafactory-over-method-handle-invoke.md) under AOT) · **Date:**
2026-07-17

## Context

The `:examples:graphql` native-image verifier (`NativeVerify`) exercises telescope's full runtime + codegen surface
under a real GraalVM `native-image` build. The first two CI native builds established the boundary empirically:

- **The codegen path native-images cleanly, zero config.** `@FromMap` and `@Bridge` passed on every run — they compile
  to typed method calls, hold no runtime reflection, and their image-heap constants only need
  `--initialize-at-build-time` for the telescope + generated-model classes (already wired in the example build).
- **The runtime reflective path fails**, on two distinct walls:

  **Wall A — `SerializedLambda` decode.** `.field(User::name)` recovers the field name by serializing the `Serializable`
  method reference and reading `SerializedLambda.getImplMethodName()`. native-image does not synthesize `writeReplace()`
  for a lambda unless its capturing class is registered for serialization, so the decode throws and the navigation path
  reports `Expected a method reference`.

  **Wall B — runtime `LambdaMetafactory` (the load-bearing one).** `Records.buildReaders` and the `Beans` no-arg-ctor /
  setter / getter builders construct one `Function` / `Supplier` / `BiConsumer` accessor per member by calling
  `LambdaMetafactory.metafactory(...)` **at run time**, which defines a fresh hidden class per accessor. native-image's
  closed world forbids runtime class definition outright
  (`Classes cannot be defined at runtime by default when using ahead-of-time Native Image compilation`). No metadata
  file can lift this — it is a code-shape problem, not a registration gap.

A third, lesser requirement rode along with Wall B for the mappers: `Telescope.mapper(...)` walks the graph with
`Class.getRecordComponents()` and invokes accessors / the canonical constructor / bean setters reflectively, so the
converted **DTO types** must be registered for reflection (a standard native-image reachability-metadata need, distinct
from the two walls above).

This ADR does not touch the codegen path — [ADR-0004](0004-runtime-and-codegen-strategy-separate.md) keeps codegen and
runtime as separate strategies, and codegen is already AOT-clean. It qualifies
[ADR-0005](0005-lambdametafactory-over-method-handle-invoke.md): LMF remains the JVM hot-path dispatch primitive; under
native-image the same accessors take a class-definition-free shape.

## Decision

Make the **runtime path AOT-capable out of the box** by fixing Wall B in the substrate, ship telescope's own build-time
init as `native-image.properties` **inside `telescope-core`**, and leave adopter DTO / lambda metadata to standard
app-level GraalVM reachability files. **No separate `telescope-graalvm` module** — increment 1 proved it unnecessary
(see the amendment note below).

> **Amendment (2026-07-17, after the build).** The original decision was to ship a `telescope-graalvm` Feature module
> for Walls A and the DTO-reflection requirement. Building it out revealed the module earns nothing: the runtime-mapper
> DTO types are typically **un-annotated** (annotate with `@Focus` and you get the reflection-free codegen path, which
> needs none of this), so a Feature keyed on `@Focus`/`@BeanFocus`/`@Bridge` would not cover the common case, and
> un-annotated types cannot be discovered at build time — the app must declare them regardless. The graphql verifier
> reached **all seven capabilities green** under native-image with only: the Wall B substrate branch, telescope-core's
> `native-image.properties`, and the example's own `reflect-config.json` + `serialization-config.json`. Apps shipping
> reachability metadata for their own types is idiomatic GraalVM, not telescope's job. So the module is dropped; the
> section below records the shape that actually shipped.

### 1. Wall B → gated `MethodHandle` accessor branch in `:internal` (no new dependency)

Telescope already builds one accessor shape without `LambdaMetafactory`: `Records.buildCtorFn` uses
`MethodHandle.asSpreader(...).asType(...).invokeExact(...)` — a compile-time closure over a `MethodHandle`, no
synthesized class — precisely because LMF rejects the non-direct spreader handle. That same shape is the AOT-safe path
for the reader/writer accessors.

Add a one-time flag and branch the accessor builders in `Records` and `Beans`:

```java
// JDK-only detection — no org.graalvm.nativeimage dependency added to :internal.
static final boolean IN_IMAGE = System.getProperty("org.graalvm.nativeimage.imagecode") != null;

Function<Object, Object> reader = IN_IMAGE
  ? mhInvokeClosure(handle) // obj -> handle.invoke(obj); compile-time lambda, no runtime class def
  : lmfFunction(handle); // LambdaMetafactory — the JVM hot path (ADR-0005), unchanged
```

The MethodHandle closures are centralized in `internal/MhAccessors` — one `supplier` / `function` / `biConsumer` /
`biFunction` builder, each closing over an `asType`-adapted handle, so every branched site shares one proven
implementation per SAM shape. Each is a source-level lambda; native-image supports it because the lambda class is
compile-time and the `MethodHandle` targets a member the app's reflection metadata has registered. This keeps
`:internal` dependency-free — the detection is a JDK system property, not `org.graalvm.nativeimage.ImageInfo`.

The branched sites cover every runtime rebuild strategy: `Records.buildReaders` (record reads); the `Beans` no-arg-ctor
`Supplier`, the top-level and `SettersWriter` setters, and the getter invokers (the `SETTERS` / `FIELDS` strategies);
and the `BuilderWriter` strategy end to end — `builder()` factory, fluent / void setters, `build()`, and the
`builderDefaultSupplier` intermediate. `MetadataHolderProbe`'s `construct(Function)` binder is branched too, and
`Records.buildCtorFn` was already `MethodHandle`-based. The one deliberate exception is the Hibernate-proxy accessor
pair (`buildHibernateLazyInitializerFn` / `buildHibernatePersistentClassFn`): guarded by a Hibernate-on-classpath check,
unreachable in the verifier, and not convertible-with-proof there — documented as JVM/codegen-only under AOT rather than
shipped unverified.

Rejected alternative for Wall B — **GraalVM `@Substitute` in a separate module.** Keeping `:internal` pristine and
substituting `Records`/`Beans` was considered and rejected: substitutions are brittle (bound to internal method
signatures, silently rot when the substrate refactors) and would make an extra module **mandatory** for any AOT use of
the runtime path. The gated branch makes telescope AOT-capable with zero extra artifact — the stronger "drop-in under
native-image" story. The branch cost on the JVM is one already-hoisted `static final boolean` read that the JIT folds;
the JVM hot path is byte-for-byte the ADR-0005 LMF path.

### 2. Telescope's own build-time init → `native-image.properties` in `telescope-core`

`telescope-core` ships `META-INF/native-image/io.github.eschizoid/telescope-core/native-image.properties` with the
`--initialize-at-build-time` args for the telescope packages (`io.github.eschizoid.telescope`, `...internal`,
`...internal.optics`). native-image auto-loads it from the jar, so an adopter needs **no build args for telescope
itself** — the `@Bridge` / `@FromMap` heap constants and the optic wrappers (all stateless `MethodHandle` / `Function`
holders) initialize at build time automatically. This is the idiomatic "a library ships its own image metadata" pattern.

### 3. Adopter DTO reflection + Wall A → app-level reachability metadata (standard GraalVM)

The runtime reflective mapper's source/target types need reflection registration (`getRecordComponents()` + accessors +
constructor + setters), and `.field(methodref)` needs the call-site class registered as a lambda-capturing type for
serialization (Wall A). Both are **the app's own types**, so they belong in the app's own reachability metadata —
`reflect-config.json` + `serialization-config.json` under the app's `META-INF/native-image/...`, exactly as every
GraalVM app ships for its own domain types. The graphql example does this for its DTOs; the verifier registers its own
lambda-capturing class. Adopters can hand-write these or generate them with the GraalVM tracing agent.
`.fieldByName(String)` and codegen navigators are the `SerializedLambda`-free alternatives that need no serialization
registration at all.

### 4. Adopter contract (documented in `docs/native-image.md`)

- **Codegen path (`@Focus`/`@BeanFocus`/`@Bridge`/`@FromMap`): native-image with zero config.** Already true; unchanged.
- **Runtime mapper (`Telescope.mapper(A, B)`): works under native-image** with the Wall B substrate branch (free, in
  core) plus the DTO source/target types in the app's `reflect-config.json`.
- **Runtime navigation (`.field(methodref)`): works under native-image** with the call-site class registered as a
  lambda-capturing type in the app's `serialization-config.json`; `.fieldByName(String)` and codegen navigators are the
  registration-free alternatives.

## Consequences

- **Telescope is AOT-capable with no extra artifact.** Wall B falls to a substrate branch in core; telescope's own
  build-time init ships in core's `native-image.properties`. Adopters add only the reachability metadata for their own
  types, exactly as any GraalVM app does.
- **JVM hot path is unchanged.** The gated branch adds a folded `static final boolean` read; steady-state dispatch is
  the ADR-0005 LMF path unmodified. Full test suite green; benchmarks unaffected.
- **The verifier is the standing proof.** `NativeVerify`'s seven capabilities pass natively (empirically confirmed), and
  the native-image workflow is the regression gate. The `docs/native-image.md` verdict is "runtime + codegen both work
  under native-image, with the documented adopter metadata."
- **Wall A stays a sharp edge for `.field(methodref)`.** It is inherent to method-reference field-name recovery under a
  closed world; the honest answer is app-level lambda-serialization registration, or `.fieldByName` / codegen — not a
  claim that every call site is transparent.

## Alternatives considered

- **Metadata only (no substrate change).** Rejected — cannot clear Wall B. A `reflect-config.json` / tracing-agent run
  registers reflection and serialization but does not stop `LambdaMetafactory.metafactory(...)` from defining a class at
  runtime. Empirically confirmed: adding reflection metadata changed the failure set but left the runtime-LMF wall
  standing (and perturbed the `.field` paths that had passed without it).
- **A separate `telescope-graalvm` Feature module.** Considered and rejected after building it out — see the amendment
  under Decision. It earns nothing for the common (un-annotated) runtime-mapper case, and app-level DTO metadata is
  standard GraalVM practice regardless.
- **GraalVM `@Substitute`s for Wall B.** Rejected — brittle (bound to internal method signatures, rot on refactor) and
  would make an extra module mandatory for AOT; the gated branch is self-contained and dependency-free.
- **Scope the native tripwire to the codegen path; declare the runtime path JVM-only under AOT.** Rejected. It concedes
  a MapStruct-parity axis — matching MapStruct on codegen while abandoning the runtime path under AOT is weaker than
  making the runtime path work too, which it now does.
- **Do nothing.** Rejected. The verifier already surfaced the gap; leaving it means "native support" could only ever
  mean the codegen path.

## Build increments (what shipped)

1. **Wall B substrate branch** in `:internal` — every runtime rebuild strategy dispatches to an `MhAccessors` closure
   under `NativeImage.IN_IMAGE`: `Records.buildReaders`; the `Beans` no-arg-ctor `Supplier`, top-level + `SettersWriter`
   setters, and getter invokers; the whole `BuilderWriter` strategy (`builder()` / fluent + void setters / `build()` /
   `builderDefaultSupplier`); and `MetadataHolderProbe.construct(Function)`. `Records.buildCtorFn` was already MH-based.
   The Hibernate-proxy accessors are the one documented JVM/codegen-only exception.
2. **Metadata** — telescope-core's `native-image.properties` (build-time init for the telescope packages), plus the
   example's own `reflect-config.json` (DTO reflection, including the builder-only bean + its `Builder`) and
   `serialization-config.json` (lambda-capturing registration for Wall A).

All eight verifier capabilities — including the runtime record→builder-bean mapper, which exercises the `BuilderWriter`
path no other capability reaches — pass under native-image. The `telescope-graalvm` module planned as a third increment
was dropped as unnecessary (amendment above).
