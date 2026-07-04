# ADR-0013: Introspectable mappings via `Mapper.explain()`

**Status:** Proposed (draft — the open decisions in §Open questions are unresolved and will be settled in design review
before this moves to Accepted) · **Date:** 2026-07-04

## Context

A telescope mapper already reasons about every field pairing — twice — and throws the reasoning away both times.

At construction, `Telescope.mapper(A, B, rows…)` routes through `DeepMap.computeAutoIso`, which for each field pair
calls `PairingRules.decidePair(srcType, tgtType, componentName)`, receives a sealed `PairDecision` (`Identity` /
`PrimitiveWrapper` / `RecursePair` / `OptionalToNullable` / `LiftContainer` / `Incompatible` / …), uses it to select
which `Iso` to build, and discards the decision. `PairingRules.matchFields` computes the `matched` / `unmatchedTargets`
/ `unmatchedSources` name sets and discards those too. The compile-time verifier (ADR-0012) runs the _same_ `decidePair`
walk a second time over the call site to raise diagnostics — and also discards the intermediate decisions once it has
emitted its errors.

That discarded stream — the ordered `(path, PairDecision)` sequence plus the `MatchResult` — is a complete, precise
answer to "what does this mapper do, and why did it skip what it skipped." Today there is no way to ask. The mapper is a
black box between `read(A)` and its result.

The comparison target's introspection story is "read the generated `.java`": you locate the generated mapper source,
read imperative assignment code, and infer the mapping table by eye. It works, but it is not a queryable structure — you
cannot assert on it in a test, log it from a running service, or surface it on a diagnostics endpoint — and it does not
state _why_ a target field was left unset (under a lenient unmapped-target policy an unmapped field simply doesn't
appear in the generated code; its absence is the only signal).

## Decision

Add an **introspection surface** to the row-based mapper factories that returns the discarded decision stream as a
structured, queryable value.

**1. `Mapper<A, B>.explain()` → `MappingReport`.** A new instance method on `Mapper` (and its siblings — see §Open
questions #6) returning a `MappingReport`: a **sealed public type family in `:core`**, following the existing
`Mapping.java` / `Edit.java` shape (sealed interface + package-private record impls, static factories, world-class
call-site ergonomics). The report is **data-primary** — a structure you can iterate, filter, and assert on — with a
`toString()` that renders the human-readable form (the `Mapped: … / Skipped: … / Transformations: …` layout). The pretty
print is a _view_ of the data, never the API.

**2. Derived from the mapping's own decisions — single source of truth.** The report is built from the exact same
`PairDecision` stream and `MatchResult` that `DeepMap` uses to assemble the executable `Iso` chain — not a parallel
re-walk with its own logic. This is the lattice-first discipline (mantra #3) applied: if `explain()` derived its answer
independently it could drift from what the mapper actually does and lie; deriving it from the one decision stream makes
that structurally impossible. It also means `explain()` is a _third lens_ on `PairingRules`, alongside `DeepMap`'s
"decision → `Iso`" and the ADR-0012 verifier's "decision → diagnostic" — the engine already shipped; this stops throwing
its output away.

**3. Report sections.** Three, mapping onto vocabulary telescope already computes:

- **Mapped** — same-name auto-matches and explicit `to(srcAcc, tgtAcc)` rename rows, rendered as `from → to` with dotted
  paths through nested `RecursePair` recursion (`address.city → city`).
- **Transformations** — rows/decisions that change the value's type: typed-transform rows `to(src, tgt, fwd, bwd)`, and
  cross-type `PairDecision`s (`PrimitiveWrapper`, `OptionalToNullable` / `NullableToOptional`, `LiftContainer`),
  rendered as `field(FromType) → ToType`.
- **Skipped** — a target field the mapper does not populate, with a reason: `DROPPED` (an explicit `Mapping.drop(src)`
  row) and, on the lenient factories only, `MISSING_SOURCE` (an `unmatchedTargets` entry). See §Open questions #2 — the
  `MISSING_SOURCE` case has real semantic subtlety.

**4. Zero hot-path cost.** The report is produced at construction time or on first `explain()` call (see §Open questions
#1) — never on the per-`forward()` / per-`read()` path. Consistent with ADR-0012's premise that all pairing reasoning
runs at build time only.

## Open questions (the design-review / grill targets)

These are deliberately unresolved in this draft. Each is load-bearing enough that guessing would bake in a decision the
review should own.

1. **Eager retention vs lazy re-walk.** Retain a small immutable `MappingReport` on the `Mapper` at build time
   (`explain()` is then a field read), or re-run the `PairingRules` walk on each `explain()` call (no retained state,
   re-walks). Leaning eager — mappers are built once and long-lived, the report is tiny — but the memory cost per mapper
   value and the "who else needs the retained decision stream" question want a real answer.

2. **Strict vs lenient `MISSING_SOURCE` semantics.** In strict `mapper(...)`, an unmatched target is a _hard
   construction error_ (the strict bijection guard throws) — it never becomes a "skipped" row, so a strict mapper's
   report has no `MISSING_SOURCE` section by construction. `MISSING_SOURCE` only exists on the lenient paths
   (`mapperForward`, `fromMap`). This is not a bug — it points at where `explain()` earns the most: the lenient/untyped
   surfaces, and especially `fromMap`, whose _verification_ is the deferred follow-up on the tracking issue. Decision:
   is `explain()` primarily a lenient-mode / `fromMap` feature, with strict mappers getting the Mapped/Transformations
   view only? Or do we surface strict mode's completeness differently?

3. **Report data model.** Sealed hierarchy (`sealed interface ReportEntry permits Mapped, Transformed, Skipped`) vs flat
   parallel lists (`List<Mapped>`, `List<Transformed>`, `List<Skipped>`) on the `MappingReport` record. Granularity of
   the `Skipped.Reason` enum (`DROPPED`, `MISSING_SOURCE`, and possibly `UNMAPPED_TARGET` / `NO_WRITE_STRATEGY`). What
   is the minimal shape that reads well at the call site _and_ is convenient to assert on in a test.

4. **Codegen parity.** The `@Bridge` verifier already holds this exact decision data at compile time. Should
   `BridgeProcessor` emit a `MappingReport` constant into the generated class so codegen `.explain()` is a precomputed
   constant (zero runtime cost, same `PairDecision` source, nice runtime/codegen symmetry)? Or is that scope creep for a
   first cut — ship the runtime surface, add codegen emission later?

5. **Bidirectional view.** Mappers are bidirectional; a typed transform has distinct forward/backward functions. Default
   to a single forward-facing report (consistent with the "bidirectional is overrated, demote it" positioning), or offer
   `explainForward()` / `explainBackward()`? Leaning forward-only for v1.

6. **Surface scope.** `explain()` lives naturally on `Mapper<A, B>` (the "conversion with named rows" abstraction) and
   its `ForwardMapper` / `fromMap`-result siblings. Does it also belong on the raw `Telescope<A, B>` returned by
   `Telescope.map(...)`, which carries the same rows but a thinner type? Where is the surface boundary.

7. **Path rendering.** Dotted flatten (`address.city → city`) vs a nested tree in the data model (dotted only in the
   `toString()`). The data model choice affects how a caller queries nested mappings.

8. **Runtime trace — in or out.** This ADR covers the _static structural_ report ("what would this mapper do"). A
   distinct, larger sibling is a _runtime trace_ ("given this specific input instance, here is what each field became").
   Explicitly scope it out of this ADR, or fold in a minimal hook now?

## Consequences (contingent on the above resolving)

- **New public API surface** — the `MappingReport` sealed family plus `explain()` methods. Additive; no change to
  existing signatures. Lands under the same `world-class ergonomics` bar as `Mapping` / `Edit`.
- **Zero hot-path cost** — the report never touches `forward()` / `read()`; construction- or first-call-time only.
- **A queryable introspection story the comparison target lacks** — assert-in-tests ("this mapper maps exactly these
  fields, skips exactly these"), log-from-service, surface-on-diagnostics — none of which "read the generated `.java`"
  offers. And it states the _reason_ for a skip, which generated imperative code does not.
- **Reinforces the ADR-0012 safety narrative** — the verifier says "your mapping is complete or it won't compile";
  `explain()` says "and here is exactly what complete means for this mapper," from the same engine, for a different
  audience (build gate vs developer introspection).
- **Natural home for the `fromMap` debugging story** — pairs with the deferred `fromMap` verification follow-up on the
  tracking issue.

## Alternatives considered

- **`toString()` on `Mapper`.** Too coarse and string-only — not iterable, not filterable, not assertable, no reason
  codes. The rendered text should be a _view_ of a structured report, not the interface.
- **Log the decisions during construction.** Ephemeral and side-channel; you cannot assert on a log line in a unit test
  or query it from a running service. The value is a returnable structure.
- **Compile-time report artifact only** (emit a `mapping-report.txt` next to the generated code, no runtime API). Misses
  the runtime-built and `fromMap` mappers entirely, and the test-assertion use case, which is where the queryable
  structure earns most.
- **Do nothing — "read the generated code."** Concedes the introspection gap the comparison target has, on a feature
  where telescope already computes the answer and merely discards it.
