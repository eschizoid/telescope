# telescope-examples-library

The `examples/library` module holds twelve plain Java programs, and each one shows one part of the telescope DSL in its
own `main` method. The module depends only on telescope itself, its annotation processors, and Lombok. There is no
Spring, JPA or Jackson in it, so each demo shows what telescope does without a framework around it.

If you are new to telescope, start with `RuntimeNavigationDemo`, `MultiEditDemo` and `DeepMappingDemo`. The Spring Boot
examples in [`../springboot/`](../springboot/) show telescope inside an application.

## The demos

| Demo                      | What it shows                                                                                                                                            |
| ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `RuntimeNavigationDemo`   | `Telescope.of(Class)` for records, `Telescope.ofBean(Class)` for POJOs, and the two `fieldByName` forms that take a field name as a string               |
| `ContainerNavigationDemo` | The typed container paths `list`, `setField`, `mapField` and `optional`, with their terminals `each`, `values` and `present`                             |
| `SealedAndFilterDemo`     | `.as(Class)` to narrow a sealed hierarchy to one case, and `.filter(Predicate)` to restrict a path with many focuses                                     |
| `MultiEditDemo`           | `Telescope.all(over(path, fn), ...)` to apply several edits to one root, built once and reused on another value                                          |
| `IndexedDemo`             | `updateIndexed`, `toListIndexed`, and the `withIndex()` view with its `update`, `toList`, `count` and `find`                                             |
| `EffectfulUpdateDemo`     | `updateAsync`, `updateOptional`, `updateEither` and `updateValidated`                                                                                    |
| `ValidatedMappingDemo`    | `Validated.combine` and `Validated.combineAll`, which build a typed object from raw input and collect every validation error instead of stopping at one  |
| `ConversionDemo`          | `Telescope.from(A).to(B).using(forward, backward)`, read in both directions and composed into a longer path with `.then(...)`                            |
| `DeepMappingDemo`         | `Telescope.map` and `Telescope.mapper` with nested types, the `to` and `via` rows, `Mapper#patch`, and the `writeBean` hint for a constructor-only POJO  |
| `GraphQlMapToPojoDemo`    | `Telescope.fromMap` to build a record from a `Map<String, Object>`, the shape a GraphQL server receives its arguments in                                 |
| `CodegenDemo`             | The `<X>Telescope<R>` navigators that `@Focus` and `@BeanFocus` generate, and the `<Source>Bridge` class and `as<Target>()` hop that `@Bridge` generates |
| `LombokDemo`              | The navigators that telescope-lombok generates for a `@Data` class and a `@Builder` class                                                                |

In `DeepMappingDemo`, `patch` overlays a partial DTO onto a base entity. A null field in the partial keeps the base
value, and the result is always a new object.

Each demo prints labelled lines to standard output. The javadoc at the top of each file says what the demo covers.

## Running

Each demo has its own Gradle task, named `run` followed by the class name. To run one demo:

```bash
./gradlew :examples:library:runRuntimeNavigationDemo
```

To run all twelve:

```bash
./gradlew :examples:library:runAllDemos
```

The demos share no state, so they can run in any order.

## What this module does not cover

- The demos have no assertions. The tests live in each library module. The optic laws are tested in `OpticLawsTest`
  under `internal/src/test`.
- The demos are not a template for an application. Use the Spring Boot examples in [`../springboot/`](../springboot/)
  for that.
- The demos cover the public DSL only.

## Compilation as a check

CI runs `./gradlew check`, which compiles this module but does not run the demos. `CodegenDemo` and `LombokDemo` import
the generated navigators and bridge by name. If a processor stops generating one of those classes, or renames a method
on one, this module fails to compile. `LombokDemo` also fails to compile if telescope-lombok writes its navigators too
late in annotation processing for same-module code to use them.
