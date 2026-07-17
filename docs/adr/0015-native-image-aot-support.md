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

  **Wall B — runtime `LambdaMetafactory` (the load-bearing one).** `Records` (line ~403) and `Beans` (lines ~206/224)
  build one `Function` / `Supplier` / `BiConsumer` accessor per member by calling `LambdaMetafactory.metafactory(...)`
  **at run time**, which defines a fresh hidden class per accessor. native-image's closed world forbids runtime class
  definition outright
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

Make the **runtime path AOT-capable out of the box** by fixing Wall B in the substrate, and ship a **`telescope-graalvm`
module** as the ergonomic layer that supplies the metadata Walls A and the DTO-reflection requirement need. The module
is optional — without it, the runtime path still works in native-image for everything that does not decode a method
reference; with it, adopters get the metadata registered for them.

### 1. Wall B → gated `MethodHandle` accessor branch in `:internal` (no new dependency)

Telescope already builds one accessor shape without `LambdaMetafactory`: `Records.buildCtorFn` uses
`MethodHandle.asSpreader(...).asType(...).invokeExact(...)` — a compile-time closure over a `MethodHandle`, no
synthesized class — precisely because LMF rejects the non-direct spreader handle. That same shape is the AOT-safe path
for the reader/writer accessors.

Add a one-time flag and branch the accessor builders in `Records` / `Beans` / `MetadataHolderProbe`:

```java
// JDK-only detection — no org.graalvm.nativeimage dependency added to :internal.
static final boolean IN_IMAGE = System.getProperty("org.graalvm.nativeimage.imagecode") != null;

Function<Object, Object> reader = IN_IMAGE
  ? mhInvokeClosure(handle) // obj -> handle.invoke(obj); compile-time lambda, no runtime class def
  : lmfFunction(handle); // LambdaMetafactory — the JVM hot path (ADR-0005), unchanged
```

`mhInvokeClosure` is a source-level lambda closing over the resolved `MethodHandle`; native-image supports it because
the lambda class is compile-time and the `MethodHandle` targets a member the `telescope-graalvm` Feature (or manual
metadata) has registered for reflection. This keeps `:internal` dependency-free — the detection is a JDK system
property, not `org.graalvm.nativeimage.ImageInfo`.

Rejected alternative for Wall B — **GraalVM `@Substitute` in the module.** Keeping `:internal` pristine and substituting
`Records`/`Beans` from `telescope-graalvm` was considered and rejected: substitutions are brittle (bound to internal
method signatures, silently rot when the substrate refactors) and would make `telescope-graalvm` **mandatory** for any
AOT use of the runtime path. The gated branch makes telescope AOT-capable with zero extra artifact — the stronger
"drop-in under native-image" story. The branch cost on the JVM is one already-hoisted `static final boolean` read that
the JIT folds; the JVM hot path is byte-for-byte the ADR-0005 LMF path.

### 2. Wall A + DTO reflection → the `telescope-graalvm` module (a GraalVM `Feature`)

New published module `telescope-graalvm` (group `io.github.eschizoid`, artifact `telescope-graalvm`), sibling to the
`:quarkus` / `:spring-boot-starter` starters. It ships one `org.graalvm.nativeimage.hosted.Feature`
(`TelescopeFeature`), registered via `META-INF/native-image/native-image.properties`, that at image-build time:

- **Registers telescope's own substrate for reflection** — the `Records` / `Beans` accessor targets the gated
  `MethodHandle` path resolves, so `mhInvokeClosure` can bind them.
- **Registers `@Focus` / `@BeanFocus` / `@Bridge`-annotated types for reflection** — including their record components /
  bean accessors / constructors, which is exactly what `Telescope.mapper(...)`'s `getRecordComponents()` walk needs. The
  annotation is the opt-in signal (no fuzzy classpath heuristics — consistent with
  [ADR-0002](0002-no-fuzzy-auto-mapping.md)). A type an adopter drives through the runtime mapper but never annotates is
  registered by a hand-written `reachability-metadata.json`, documented as the fallback.
- **Registers serialization for the lambda-capturing types it is told about** (Wall A) — so `.field(User::name)` can
  `writeReplace()`. Adopter call-site classes that use `.field(methodref)` under AOT either carry an explicit
  registration or switch to `.fieldByName(String)` / a codegen navigator, both of which are `SerializedLambda`-free. The
  module documents this; the verifier registers its own capturing class.

### 3. Adopter contract (documented in `docs/native-image.md`)

- **Codegen path (`@Focus`/`@BeanFocus`/`@Bridge`/`@FromMap`): native-image with zero config.** Unchanged, already true.
- **Runtime mapper (`Telescope.mapper(A, B)`): works under native-image** with the gated branch (Wall B) plus the DTO
  types registered — automatic for `@Focus`/`@BeanFocus`/`@Bridge`-annotated DTOs when `telescope-graalvm` is on the
  build path, manual `reachability-metadata.json` otherwise.
- **Runtime navigation (`.field(methodref)`): works under native-image** once the call-site class's lambda serialization
  is registered (Wall A); `.fieldByName(String)` is the registration-free alternative.

## Consequences

- **Telescope is AOT-capable without a mandatory extra artifact.** The core walls fall to a substrate branch; the module
  only removes boilerplate. An adopter who hand-writes metadata does not need `telescope-graalvm` at all.
- **JVM hot path is unchanged.** The gated branch adds a folded `static final boolean` read; steady-state dispatch is
  the ADR-0005 LMF path unmodified. Benchmarks re-run to confirm no regression.
- **A new published coordinate** (`telescope-graalvm`) to maintain — JReleaser + release list + module-info. Justified:
  it is the natural home for the Feature and the adopter-facing native-image story, mirroring the starter modules.
- **The verifier becomes the standing proof.** Once the gated branch + Feature land, `NativeVerify`'s seven capabilities
  are expected to pass natively; the native-image workflow is the regression gate. The `docs/native-image.md` verdict
  moves from "codegen only" to "runtime + codegen, with the documented adopter contract."
- **Wall A stays a sharp edge for `.field(methodref)`.** It is inherent to method-reference field-name recovery under a
  closed world; the honest answer is `.fieldByName` / codegen, not a claim that every call site is transparent.

## Alternatives considered

- **Metadata only (no substrate change).** Rejected — cannot clear Wall B. A `reflect-config.json` / tracing-agent run
  registers reflection and serialization but does not stop `LambdaMetafactory.metafactory(...)` from defining a class at
  runtime. Empirically confirmed: adding reflection metadata changed the failure set but left the runtime-LMF wall
  standing (and perturbed the `.field` paths that had passed without it).
- **Substitutions in `telescope-graalvm` for Wall B.** Rejected — brittle and makes the module mandatory for AOT. See
  the gated-branch decision above.
- **Scope the native tripwire to the codegen path; declare the runtime path JVM-only under AOT.** Rejected as the target
  state (though it is the honest interim). It concedes a MapStruct-parity axis — MapStruct is codegen-only and
  AOT-clean; matching it on codegen while abandoning the runtime path under AOT is weaker than making the runtime path
  work too.
- **Do nothing.** Rejected. The verifier already surfaced the gap; leaving it means "native support" can only ever mean
  the codegen path.

## Build increments (one PR each, gated on a green native-image CI run)

1. **Wall B substrate branch** in `:internal` (`Records` / `Beans` / `MetadataHolderProbe`) + reflection metadata for
   the verifier's DTO types, proving the runtime **mapper** capabilities pass natively.
2. **`telescope-graalvm` module** — `TelescopeFeature` registering `@Focus`/`@BeanFocus`/`@Bridge` types + telescope
   substrate reflection, wired into the example build; drop the hand-written DTO metadata from increment 1.
3. **Wall A** — serialization registration for the verifier's method-reference call sites (or convert them to
   `.fieldByName`), turning the remaining `.field(methodref)` capabilities green, and finalize the
   `docs/native-image.md` adopter contract.
