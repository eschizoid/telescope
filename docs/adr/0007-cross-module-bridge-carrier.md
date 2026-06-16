# ADR-0007: Cross-module `@Bridge` via a declarative carrier class

**Status:** Proposed (v1.1+ candidate) · **Date:** 2026-06-16

## Context

`@Bridge(Target.class)` lives on the source model class. That places a hard constraint: the source class's Maven module
must see the target type at compile time. Adopters with split-module domain models (`a-entity`, `a-dto`, `b-entity`,
`b-dto`, each in its own Maven module) typically can't satisfy this — neither side has a dependency path to the other,
and adding one would create a cycle or pollute the entity module with DTO references it has no business knowing.

MapStruct sidesteps this by lifting the mapping declaration out of the model. The `@Mapper` interface is a standalone
file in a third "wiring" module that depends on both the source and target modules. The model classes stay
annotation-free.

Today, telescope adopters facing the constraint fall back to the runtime
`Telescope.mapper(Source.class, Target.class, ...)` factory — they lose the codegen path, accept the ~94×
runtime-vs-codegen perf gap, and write the type-pair inline at every call site. The migration-feedback report from a
12-mapper MapStruct → telescope adopter flagged this as a P1 blocker for any non-trivial multi-module codebase.

## Decision

Allow `@Bridge` on a third **carrier** class that declares the source and target explicitly via the annotation's
attributes:

```java
@Bridge(
  source = IdentityDocumentDBDetails.class,
  target = IdentityDocumentDetailsBO.class,
  renames = { @Rename(source = "icVerificationExt", target = "vendorExtendedResult") }
)
public class IdentificationBridgeDef {}
```

The carrier class lives in a module that sees both `source` and `target`. The `BridgeProcessor` emits the
`Iso<Source, Target>` constant as a sibling class in the **carrier's** package — `IdentificationBridgeDefBridge.BRIDGE`
— not in the source's package as it would for the model-anchored form. The model classes stay annotation-free.

The model-anchored form (`@Bridge(Target.class)` on the source class) is **retained as-is** for the single-module case;
it remains the recommended posture when adopters control both sides. The carrier form is the opt-in escape hatch for
cross-module setups.

## Consequences

- **MapStruct parity unlocked for split-module codebases.** The carrier shape mirrors `@Mapper`'s placement philosophy —
  declaration sits in a module that sees both sides, not on the model itself. Adopters porting from MapStruct don't need
  to restructure their module graph.
- **Codegen path stays the hot path even for cross-module pairs.** Today's fallback (runtime `Telescope.mapper(...)`)
  drops the ~94× perf advantage on the floor. The carrier form keeps codegen — same emitted `Iso<X, Y>` body, same
  `BRIDGE` constant, same JIT-inlinable dispatch.
- **`@Rename` (and any future per-field directives) accept their own annotation array.** The migration-feedback proposal
  uses `renames = { @Rename(source="...", target="...") }`. The existing inline-on-source attribute set (`renames`,
  `valueTransforms`, etc.) carries over verbatim — no new vocabulary to learn, same parser code.
- **`BridgeProcessor` gains a small dispatch fork.** `process(...)` needs one extra branch: if the annotated class
  carries a `source = ...` attribute → carrier path (read source + target from the annotation, emit
  `<Carrier>Bridge.BRIDGE` in the carrier's package); else → existing model-anchored path. The downstream emit pipeline
  is otherwise unchanged.
- **`as<Target>()` Path hop is only generated for the model-anchored form.** Carrier-anchored bridges don't have a
  source-class `<Source>Path<R>` to hang the hop off of, by design — the source class is annotation-free in the
  cross-module case. Adopters use the carrier's `<Carrier>Bridge.BRIDGE` constant directly via
  `Telescope.from(...) .to(...).using(BRIDGE)` or `Telescope.of(Source.class).then(BRIDGE)`. Documented limitation;
  symmetric with the reality that the source module can't see the carrier class either.

## Alternatives considered

- **Restructure adopter modules to share a common compile-time visibility.** Rejected. The constraint is structural
  ("entity modules must not depend on DTO modules") and is the right architectural call for the adopter's codebase. The
  library should not require its consumers to compromise their module graph.
- **External `@Mapper`-style standalone interface, with abstract methods telescope implements.** Rejected as too
  MapStruct-shaped. Telescope's compile-checked DSL is already the right ergonomic shape; this would re-introduce the
  "write the inverse method signature by hand" friction that bidirectional `Mapper<A, B>` already eliminated.
- **Two `@Bridge` annotations — `@Bridge.OnModel` and `@Bridge.External`.** Rejected. Two annotations doubles the
  surface and forces adopters to pick. The single `@Bridge` with attribute-based dispatch (presence of `source =` →
  carrier form, absence → model-anchored form) gives one mental model with two valid call shapes.
- **Generate the `<Source>Bridge` constant in the source's package even for the carrier form.** Rejected. The carrier's
  module can't write source files into the source's package without `javac --add-modules` gymnastics. Emitting in the
  carrier's package is the only sound choice — JSR-269 processors emit into the same module that triggered them.
- **Do nothing — adopters can keep using the runtime path.** Rejected. The migration-feedback adopter flagged this as
  P1; the runtime path drops codegen's perf advantage; cross-module is a real shape MapStruct users routinely use, not
  an edge case.
