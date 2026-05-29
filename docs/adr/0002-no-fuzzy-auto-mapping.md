# ADR-0002: No fuzzy auto-mapping

**Status:** Accepted · **Date:** 2026-05-29

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
