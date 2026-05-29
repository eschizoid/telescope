# ADR-0004: Runtime and codegen rebuild-strategy decisions are not unified

**Status:** Accepted · **Date:** 2026-05-29

## Context

Both the runtime (`org.telescope.internal.Beans.autoWriter` and its probes) and the codegen processors
(`BridgeProcessor`, `BeanFocusProcessor`) decide a POJO's rebuild strategy (builder → setters → ctor) with similar
naming conventions (`builder()` / `build()`, `setX` / `withX`, no-arg ctor). They look like duplicated logic and a
periodic architecture review suggests sharing them through one `BeanShape` abstraction.

## Decision

Do **not** unify runtime and codegen strategy detection behind a shared module.

## Reasons

1. **Two incompatible reflection models.** The runtime probes `java.lang.reflect.Class`; the codegen probes
   `javax.lang.model.element.TypeElement`. They can't call each other (no `Class` at compile time; no `TypeElement` at
   runtime). Unifying requires a wrapping abstraction over both models with dozens of delegating methods — a layer
   **larger** than the small precedence/naming logic it would unify. That's the textbook shallow seam: interface as
   complex as the implementation.
2. **The precedences deliberately differ per consumer:**
   - Runtime `autoWriter`: `builder → (no-arg + setters) → (no-arg + fields)`.
   - `@BeanFocus`: `builder → (no-arg + setters)` (no field injection: generated code can't `setAccessible`).
   - `@Bridge`: `all-args ctor (name-matched) → builder → (no-arg + setters)` (records use canonical ctor).

   What looks like "one rule" is really three different decisions sharing only naming conventions.

3. **The achievable, paying de-dup was inside codegen** — the three processors share their `javax.lang.model` probes via
   `AbstractTelescopeProcessor`. That's done.
4. **Risk of silent drift is low.** If the conventions ever diverge between runtime and codegen, the failure is loud — a
   codegen golden test or a runtime round-trip test breaks immediately. The risk that justifies forcing a shared module
   (silent divergence) isn't there.

## Consequences

- A future review that notices the runtime/codegen mirror and proposes a `BeanShape` SPI to unify them: this has been
  considered and rejected on depth grounds. Don't re-litigate without new information (e.g. the conventions ever
  actually drifting and going undetected).
