# ADR-0002: No fuzzy auto-mapping

**Status:** Accepted · **Date:** 2026-05-29

> **Amendment (2026-10-09, after ADR-0018).** Two different enum types now convert without a row when their constants
> match by exact name: each constant maps to the constant of the same name, on the runtime mapper and on `@Bridge`
> alike. Exact constant-name matching is no heuristic, and it is what MapStruct does without being asked, which ADR-0018
> sets out to match on the generated path. A mapper that converts in both directions needs every constant on each side
> to have a counterpart; a forward-only mapper (`mapperForward`, `@Bridge(lenient = true)`) needs it for the source
> constants only. Any other enum pair is refused, naming the constants that have none.

> **Amendment (2026-11-05, built-in conversions).** A few conversions between two different types are built in, on the
> runtime mapper and on `@Bridge` alike, decided once in the shared pairing spec. They apply only to properties that
> already pair by exact name, or by a plain `to(source, target)` row; a rename is still declared, so nothing here
> matches by a guess.
>
> - **Text and values, both ways.** A `String` converts to and from `int`, `long`, `short`, `byte`, `float`, `double`,
>   their wrappers, `BigInteger` and `BigDecimal`; to and from an enum by constant name; and to and from
>   `boolean`/`Boolean`, read as `true` or `false` in any case. Each direction is the JDK's own call (`Long.parseLong`,
>   `Enum.valueOf`, `new BigDecimal(String)`, `String.valueOf`), so a value converts back to the text it came from. A
>   `String` that is no valid number, constant or boolean fails on that value with a message naming the property and the
>   value, such as `'total' got "12x", not a BigDecimal`, the same on both paths and never a bare
>   `NumberFormatException`.
> - **Widening, forward only.** A number converts to one it widens to by Java's widening primitive conversions, and the
>   integral types and `BigInteger` convert into `BigInteger` and `BigDecimal`, which hold each of their values. Java
>   counts `int` and `long` into `float` and `double` as widening although they round, and so does this. The way back
>   would narrow, so a mapper that converts both ways (`Telescope.mapper`, a strict `@Bridge`) refuses the pair, naming
>   the row to write, and a forward-only one (`mapperForward`, `@Bridge(lenient = true)`) takes it. A `float` or
>   `double` does not widen to `BigDecimal`, which has no NaN or infinity.
> - **List and Set, both ways.** A list and a set convert into each other's kind, each element converted as its own pair
>   decides, these conversions included. Into a set, elements that are equal collapse to the first; into a list, the
>   elements take the order the set iterates them in, so a round trip keeps the elements but not always the list.
>
> Still refused by name, each naming the `to(source, target, forward, backward)` row that converts it: narrowing (`long`
> to `int`, `double` to `float`), and a `String` against a date or time, which needs a format nobody has named. A
> `String` against a `char` is no built-in either. A null converts to null, or to the default of a primitive it is
> written into, as a wrapper does. These are what MapStruct converts without being asked, minus the lossy and
> format-dependent ones, so they move the generated path toward ADR-0018's parity without adding a heuristic.

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
