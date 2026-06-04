# ADR-0001: The optic lattice stays package-private

**Status:** Accepted · **Date:** 2026-05-29

## Context

The optic lattice (`Iso`, `Lens`, `Prism`, `Affine`, `Traversal`, `Getter`, `Setter`, `Fold`) is the proven substrate
this library is built on — decades-old machinery from Haskell `lens` → Scala Monocle → Arrow Optics. The whole point of
the `Telescope<S, A>` DSL was to hide that vocabulary from Java users, who don't have HKTs/implicits/macros to make it
ergonomic in the first place.

## Decision

`io.github.eschizoid.telescope.internal.optics` stays package-private to the library, and the `internal` packages are
deliberately not exported by `module-info.java`. The single public type users see is `Telescope<S, A>`.

## Consequences

- Users never type `Iso`/`Lens`/`Prism`/etc. — that's the design.
- If a real interop case appears later (e.g. Higher-Kinded-J integration), promoting the package is one `exports` line
  away — but no use case has materialized in any session so far. **Don't preemptively expose them.**
- Internal refactors of the lattice (depth-deepening work) can land without API churn.
