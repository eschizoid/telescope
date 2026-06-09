# telescope-examples-library

**Telescope without a framework.** Ten plain-Java `main()` mains that each exercise one capability of the DSL in
isolation — no Spring, no JPA, no Jackson. Cleanest possible surface to evaluate what telescope actually does, untangled
from any framework wrapping it around.

If you're new to telescope, **read these first** (or at least skim three: `RuntimeNavigationDemo`, `MultiEditDemo`,
`DeepMappingDemo`) — then move on to the Spring Boot examples in [`../springboot/`](../springboot/) once you want to see
telescope inside a real stack.

## The demos

| Demo                      | One-liner                                                                                                                        |
| ------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `RuntimeNavigationDemo`   | `Telescope.of(Class)` for records, `Telescope.ofBean(Class)` for POJOs — the runtime entry                                       |
| `CodegenDemo`             | `@Focus` / `@BeanFocus` / `@Bridge` emit Path navigators consumed directly — no reflection                                       |
| `ContainerNavigationDemo` | Typed `list / setField / mapField / optional` subclasses + their terminals (`each` / `values` / `present`) — all compile-checked |
| `ConversionDemo`          | `Telescope.from(A).to(B).using(fwd, back)` — bidirectional Iso, composes via `.then(...)` for longer paths                       |
| `DeepMappingDemo`         | `Telescope.map / mapper(A, B, MapStep...)` — same-name auto-recursion, `to` / `via` overrides, `Mapper#patch`, `writeBean` hints |
| `MultiEditDemo`           | `Telescope.all(over(PATH, fn), ...)` — two-or-more independent edits on one root, count visible at a glance                      |
| `EffectfulUpdateDemo`     | `updateAsync` / `updateOptional` / `updateEither` / `updateValidated` — same `Traversal#modifyF` machinery, four effects         |
| `IndexedDemo`             | `.updateIndexed`, `.toListIndexed`, `.withIndex()` — position-aware traversal terminals                                          |
| `SealedAndFilterDemo`     | `.as(Class)` Prism narrow on sealed hierarchies + `.filter(Predicate)` restriction on many-focus paths                           |
| `LombokDemo`              | `telescope-lombok` integration — `LombokFocusProcessor` emits `<X>Path<R>` against `@Data` / `@Builder` synthesised properties   |

Each demo prints a sequence of values to stdout that shows the DSL doing its thing — read the javadoc at the top of each
file for the capability, then run the main to see the output.

## Running

Run a single demo:

```bash
./gradlew :examples:library:run -PmainClass=io.github.eschizoid.telescope.examples.RuntimeNavigationDemo
```

Or run them all (each is independent — there's no shared state across mains):

```bash
for demo in RuntimeNavigationDemo CodegenDemo ContainerNavigationDemo ConversionDemo DeepMappingDemo \
            MultiEditDemo EffectfulUpdateDemo IndexedDemo SealedAndFilterDemo LombokDemo; do
  ./gradlew :examples:library:run -PmainClass=io.github.eschizoid.telescope.examples.$demo
done
```

## What this directory is _not_

- **Not integration tests.** There's no JUnit, no assertions — these are demonstrations, not verifications. The real
  test surface lives in `:core`'s `src/test/`.
- **Not the recommended entry point for adopting telescope in a real app.** For that, look at
  [`../springboot/`](../springboot/). These mains are a learning aid, not a starter template.
- **Not exhaustive.** They cover the user-facing DSL surface, not every internal pathway. For the full lattice +
  composition law coverage see `:core/OpticLawsTest.java`.

## Why this exists

Two audiences:

1. **Evaluators** — when someone wants to know "what does this DSL look like in isolation, with no framework noise?",
   these are the smallest possible answers. Each demo fits on one screen.
2. **CI sanity** — every demo is compiled and runnable. If any one of them stops compiling, the `@Focus` / `@BeanFocus`
   / `@Bridge` / `@telescope-lombok` codegen has regressed in a way the `:codegen` and `:lombok` unit tests didn't
   catch. They're the smoke test of the user-facing surface.
