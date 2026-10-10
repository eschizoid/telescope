# ADR-0002: No fuzzy auto-mapping

**Status:** Accepted · **Date:** 2026-05-29

> **Amendment (2026-10-09, after ADR-0018).** Two different enum types now convert without a row when their constants
> match by exact name: each constant maps to the constant of the same name, on the runtime mapper and on `@Bridge`
> alike. Exact constant-name matching is no heuristic, and it is what MapStruct does without being asked, which ADR-0018
> sets out to match on the generated path. A mapper that converts in both directions needs every constant on each side
> to have a counterpart; a forward-only mapper (`mapperForward`, `@Bridge(lenient = true)`) needs it for the source
> constants only. Any other enum pair is refused, naming the constants that have none.

## Context

Auto-mapping libraries that match fields by fuzzy heuristics at runtime (ModelMapper, Orika, Dozer) have been tried for
years and lost to MapStruct's compile-time codegen. Periodically someone suggests adding fuzzy `autoMap()` to
`Telescope.from(...).to(...)` so users don't have to declare per-field correspondences.

## Decision

Don't ship fuzzy auto-mapping. `Telescope.map(A).to(B).auto()` does **exact** name+type matching only (and is itself
opt-in). Anything that isn't an exact match is declared explicitly via `.field(A::x).to(B::y)` (renames) or
`.field(...).to(target, fwd, bwd)` (transforms). The codegen `@Bridge` annotation is bijection (same-name) only.

## Consequences

- Telescope deliberately doesn't compete with MapStruct on auto-discovery — that comparison loses.
- Its unique angle stays narrow and defensible: a mapping is a `Telescope<A, B>` _value_ that threads through optic
  paths (`.each(...)`, `.filter(...)`, `.then(...)`), which MapStruct mappers can't do.
- If a future review proposes "we should match `userName`→`user_name` automatically" — no. Run a normaliser at the
  boundary or declare the rename. The line is "exact name + type, declared explicitly otherwise."
