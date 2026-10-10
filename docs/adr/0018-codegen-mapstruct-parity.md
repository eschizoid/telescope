# ADR-0018: Bring the generated mapper to MapStruct's feature surface

**Status:** Proposed · **Date:** 2026-10-09

## Context

The generated mapper is the path a MapStruct user moves to, because it compiles a mapping to plain Java the way
MapStruct does. `docs/mapstruct-parity.md` scores each MapStruct feature once, across both paths, and credits a feature
as covered when either path has it. Read for the generated mapper alone, the 29 features score 7 covered, 16 partial and
6 missing. Every feature the runtime mapper covers and the generated mapper does not is also a difference between the
two paths, which the 2.0 work set out to remove.

The features missing from `@Bridge` are these:

| Feature                         | What `@Bridge` does today                                                           |
| ------------------------------- | ----------------------------------------------------------------------------------- |
| Enum to enum                    | Two enums with the same constants are refused at compile time.                      |
| Nested source path              | A rename source must be a direct field, so `customer.address.city` cannot be named. |
| Multiple source parameters      | A bridge has one source type.                                                       |
| Context parameters              | `forward` takes only the source, so no per-call context reaches a nested bridge.    |
| Object factory, update in place | `patch` returns a new source; nothing writes into an existing target.               |
| Conditional mapping             | No row takes a predicate.                                                           |

Among the partial features, two come up in most migrations. A renamed field cannot also be converted, because
`@Transform` and `@ViaMapper` have no target attribute. Built-in conversions that MapStruct applies without being asked
(String and number, enum and String, List and Set) are refused. `@Compute` cannot read the source, so a value derived
from several source fields has no annotation.

The cycle guard is a correctness defect rather than a missing feature. The runtime mapper tracks the objects on the
active conversion path and cuts the back-reference to null, while the generated mapper recurses until the stack
overflows.

## Decision

Close the gaps on the generated path in the order below. Each step follows the rules the runtime and generated paths
already share: the decision lives in the shared pairing spec under `internal/pairing` where both paths need it, each
step adds rows to `CrossPathCorpusTest` so both paths are held to the same answer, and anything the generated path
cannot do is refused by name at compile time.

1. **Cycle guard.** A generated mapper for a pair whose source and target types can both reach themselves maps a
   reference back to an object still being converted to null on both paths, forward, backward and in `patch` (each
   partial slot on a path of its own); an object reached along two branches converts twice. A pair whose types cannot
   both reach themselves pays nothing, which the processor decides from the type graph at compile time.
2. **Enum to enum.** A pair of enums maps constant to constant by name, with a compile-time check that every source
   constant has a target. A rename for individual constants is a later addition.
3. **Conversion on a renamed field.** `@Transform` and `@ViaMapper` gain a target attribute, so one row can both rename
   and convert. Built-in conversions for String and number, enum and String, and List and Set follow, each decided in
   the shared spec so both paths convert the same pairs.
4. **Values computed from the source.** A `@Compute` form takes a function of the source, so a target field can be built
   from several source fields.
5. **Nested source and target paths.** A rename source can name a nested field. Reading through a nested path
   (flattening) comes first; writing through one (unflattening), which has to create the intermediate objects, comes
   second.
6. **Update in place.** For a target written through setters, the processor generates `into(target, source)`, which
   writes into an existing instance. That is the form a managed JPA entity needs.

Multiple source parameters, context parameters and conditional mapping stay on the runtime path for now. Each is decided
in its own ADR when an adopter needs it on the generated path.

`docs/mapstruct-parity.md` gains a generated-mapper status for every row, next to the existing combined status, so the
gap is visible and each step above updates it.

## Consequences

- A MapStruct user can move the common shapes, enums, renamed conversions, flattened fields and JPA updates, to
  `@Bridge` without falling back to the runtime mapper.
- Each step widens what the generated mapper accepts, so none of them breaks code that compiles today. The cycle guard
  changes behaviour only for graphs that currently overflow the stack.
- The cross-path corpus grows with every step, so the two paths stay in agreement as the generated path gains features.
- The parity document reports the generated path on its own, which makes the remaining gap measurable.

## Alternatives considered

- **Keep crediting a feature when either path has it.** Rejected: it hides the gap a MapStruct user meets first, and it
  hides differences between the two paths.
- **Point users at the runtime mapper for the missing features.** Rejected as the long-term answer: a user who chose
  generated code for speed, or for native images without reflection configuration, has no way to mix in a runtime-only
  row on one field.
- **Copy MapStruct's string-keyed `@Mapping(source = "a.b")`.** Rejected for nested paths in favour of a form the
  processor checks against the source type at compile time, which telescope already does for every other field name.

## Open questions

- The annotation shape for a nested source path, and whether it reuses `@Rename` or needs its own attribute.
- Whether `into(target, source)` should also exist for builder and constructor targets, where writing in place is not
  possible.
