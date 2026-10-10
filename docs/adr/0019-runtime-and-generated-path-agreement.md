# ADR-0019: Which differences between the runtime and generated paths may remain

**Status:** Proposed · **Date:** 2026-10-10

## Context

Telescope runs one mapping two ways: the runtime mapper (`Telescope.mapper`, `mapperForward`) and the generated mapper
(`@Bridge`). ADR-0004 keeps their strategies separate, and ADR-0012 puts the decisions both need in a shared pairing
spec. Cross-path test registers hold the two paths to the same answer and record every case where they still differ:
`KNOWN_DIVERGENCES`, `CONSTRUCTION_KNOWN_DIVERGENCES`, `GENERATED_PATH_LIMITS` and `ENUM_RUNTIME_REFUSALS` in
`CrossPathCorpusTest`, and `KNOWN_DIVERGENCES` in `BeanRebuildCorpusTest`. Each register fails the build when a recorded
difference stops differing.

The registers do not say which of their entries are meant to stay. Some differences come from what generated Java can
reach: a private constructor cannot be called from another class, and a package-private constructor cannot be called
from another package. Others come from one path not doing work the other already does. For example, the runtime mapper
cannot map between sealed roots while `@Bridge` dispatches by case, and codegen refuses a generic subclass that passes
its own type variable through while the runtime converts it.

## Decision

A difference between the paths may remain only when one path cannot do the work for a reason outside telescope's
control. Every other difference is a defect to close.

1. **Inherent limits stay, recorded and refused by name.** A difference is inherent when generated Java cannot express
   the operation, or when the JVM gives the runtime no way to perform it. Today that is a private constructor, and a
   package-private or protected constructor in a different package from the bridge. Each stays in a register, and the
   path that cannot do the work refuses by name at compile time or when the mapper is built.
2. **Every other difference is a defect.** It gets an issue and a fix that makes both paths give the same result, either
   both converting or both refusing by name. Converting on both is preferred when both can.
3. **New differences need this test.** A change that adds a register entry says in its pull request which kind it is. An
   entry that is not inherent comes with an issue.
4. **Runtime-only features are separate decisions.** Multi-source `merge`, context parameters and conditional rows exist
   only on the runtime path. Bringing one to the generated path, or leaving it runtime-only, is decided in its own ADR
   and is not a difference in this sense.

The registers keep their current shape. Each entry gains a short comment saying whether it is inherent, so the remaining
work can be read from the test sources.

## Consequences

- The cross-path registers shrink toward the inherent limits, which makes the remaining gap measurable.
- Some entries close by refusing on both paths, for example the runtime refusing a builder that has no method for a
  `final` field it would otherwise lose. That changes runtime behaviour and is marked as a breaking change when it does.
- The work is tracked in one epic, separate from the MapStruct parity work in ADR-0018, because parity adds features
  while this removes differences.

## Alternatives considered

- **Leave the registers as they are.** Rejected: an entry that records a gap looks the same as one that records a hard
  limit, so nothing pushes the gaps toward closing.
- **Close every difference, including the inherent ones.** Rejected: generated code cannot call a private constructor
  without reflection, and the generated path exists to avoid reflection.

## Open questions

- The case-pairing rule the runtime mapper uses for sealed roots: matching by simple name, by each case's own `@Bridge`,
  or both.
- Whether the runtime can read constructor parameter names without `-parameters`, or should refuse the name-matched
  constructor by name instead.
