# ADR-0009: `@Bridge(lenient = true)` for the small-DTO → large-entity pattern

**Status:** Proposed (v1.1+ candidate) · **Date:** 2026-06-16

## Context

`@Bridge` enforces strict bijection at codegen time: every component on the source must have a same-name component on
the target, and vice versa. The check is the same one `Telescope.mapper(...)` enforces at runtime, and the rationale is
the same — a `Mapper<A, B>` is bidirectional, so unmatched fields would silently lose data on the backward direction.

This produces an unworkable shape for one common adopter case: small DTO → large entity. The migration-feedback report
names a concrete example — `CustomerCaseRequest` (7 fields) → `GovtIdDBData` (135 fields). Only 6 fields actually map.
The other 129 target fields stay at JLS defaults by design. Today, that requires 129
`@Constant(field = "x", value = "null")` entries on the `@Bridge` annotation. Completely impractical.

The runtime sibling of this gap was Enh 9 / PR #138: `Telescope.mapperForward(...)` made lenient by default, threading a
`lenient=true` flag through `DeepMap.populateIso`. The codegen path needs the symmetric move.

## Decision

Add a `lenient` attribute to `@Bridge` (default `false` to preserve today's strict semantics):

```java
@Bridge(
  value = GovtIdDBData.class,
  lenient = true,
  renames = {
    @Rename(source = "referenceID", target = "entRefncId"), @Rename(source = "policyNo", target = "policyNumber"),
  }
)
public class CustomerCaseRequestBridge {}
```

When `lenient = true`, `BridgeProcessor`:

- Skips the "every target component has a same-name source component" check.
- Emits writes only for the components named in `renames`, `transforms`, plus same-name auto-matches that exist on both
  sides.
- Leaves unmatched target components at their JLS default — the canonical-ctor call (records) or builder/setter chain
  (POJOs) for those positions takes the `NullDefaults.defaultFor(componentType)` value, exactly as the runtime lenient
  path does.
- Unmatched source components are silently ignored — same semantic as `mapperForward(...)` lenient default.

The generated `Iso<Source, Target>` is **forward-only-in-semantics-but-bidirectional-in-type** when `lenient = true`:
the backward direction still type-checks, but the rebuilt source has the same unmatched-on-source fields populated with
`NullDefaults` values. That's the same "partial round-trip" shape `mapperForward(...)` already exposes deliberately.
Document it loudly in the `@Bridge` javadoc: `lenient = true` opts out of the round-trip law; users who want round-trip
safety must keep `lenient = false`.

## Consequences

- **The small-DTO → large-entity pattern becomes one-line viable.** The `CustomerCaseRequest → GovtIdDBData` example
  drops from 130 annotation entries to 1 attribute + the actual rename rows. Adopter pain disappears.
- **Codegen symmetry with `mapperForward(...)` lenient default.** Both the runtime forward-only path and the codegen
  `@Bridge` path now expose the same lenient semantics, gated by a flag that defaults to the safe-bijection direction.
- **`BridgeProcessor` change is small.** One new annotation attribute parse, one branch in the bijection-validation step
  (`if (!lenient) { ... }`), and the existing same-name auto-matching loop just runs against a smaller match set. The
  emitted `Iso<S, T>` body itself is unchanged at the structural level.
- **`@Rename` and `@Transform` continue to express ALL non-default mappings explicitly.** Lenient mode doesn't add any
  heuristic — it only removes the bijection requirement. Adopters still get a static compile-time guarantee that every
  declared rename or value-transform refers to real components on both sides.
- **No silent corruption surface.** Unlike a fuzzy-match heuristic (which [ADR-0002](0002-no-fuzzy-auto-mapping.md)
  explicitly rejected), lenient mode only opts out of the "complete the match" check — same-name auto-matches and
  declared renames still go through their normal type-safety pipeline. A field that was matched correctly before
  `lenient = true` still matches correctly; the only change is that the absence of a match no longer fails compilation.
- **Documentation must call out the round-trip-loss explicitly, by direction name.** `lenient = true` users get a
  partial-Iso whose **`BRIDGE.set(source, target)` direction is the lossy one** — every `Source`-side field with no
  `Target` counterpart comes back populated at `NullDefaults` (zero / empty-string / null), regardless of what the
  original Source held. The forward direction (`BRIDGE.read(source)`) remains lossy-by-design in the well-understood
  small-DTO → large-entity shape (unmatched Target fields take JLS defaults, which is the whole point). Adopters who
  rely on backward round-trip safety must NOT set `lenient`. The `@Bridge` javadoc must spell this out next to the
  attribute declaration AND name `BRIDGE.set(source, target)` as the partial direction; the BridgeProcessor must emit a
  matching warning in the `<X>Bridge` class javadoc for any class compiled with `lenient = true`, again naming
  `BRIDGE.set(source, target)` as the asymmetric side so the IDE warning is unambiguous.

## Alternatives considered

- **Always lenient — flip the default.** Rejected. `@Bridge` has historical strict-bijection semantics; flipping the
  default silently changes behavior for every existing adopter and removes the safety net for codebases that genuinely
  want the round-trip law. Opt-in is the right posture.
- **Lenient-only via a separate annotation — `@BridgeForward`.** Rejected. Duplicates the `@Bridge` surface (annotation
  parser, processor dispatch, generated class shape) for what is really a one-flag variant. The attribute-flag form
  keeps a single annotation with one optional knob.
- **Auto-generate the missing `@Constant(field, null)` entries via a code-quick-fix.** Rejected. IDE-level annotation
  scaffolding is fragile (different IDEs implement quick-fixes differently, only the user's primary IDE benefits) and
  doesn't help build-time correctness. The flag approach fixes the build itself.
- **Per-field lenient — `@Lenient(field = "x")`.** Rejected as over-engineered. The real-world pattern is "almost
  everything is unmatched and that's fine" — not "some specific subset is unmatched." Granular per-field control adds
  surface area for no clear adopter win.
- **Symmetric runtime API — add a `lenient = true` knob to `Telescope.mapper(Class, Class, ...)`.** Out of scope here
  (separate ADR moment); the runtime bidirectional `mapper(...)` keeping strict-by-default for round-trip safety is
  load-bearing semantics. `mapperForward(...)` already covers the lenient runtime case.

## See also

- [ADR-0007](0007-cross-module-bridge-carrier.md) — sibling v1.1+ enhancement, cross-module `@Bridge` carrier (companion
  `@Bridge` surface)
- [ADR-0008](0008-fromMap-untyped-source-factory.md) — sibling v1.1+ enhancement, untyped-source factory
- [ADR-0002](0002-no-fuzzy-auto-mapping.md) — the no-fuzzy-matching guardrail that lenient mode
  opts-out-of-but-doesn't-violate
