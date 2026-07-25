# Type conversion

Converting between types that represent the same data — hand-written `from/to/using` conversions and the deep recursive
`Telescope.map(...)` / `Telescope.mapper(...)` factories. [← back to README](../README.md)

Two records that represent the same data (`Entity ↔ Dto`) convert through a bidirectional conversion that composes into
longer paths like any other telescope.

## Hand-written (`from / to / using`)

Write the two conversion functions yourself; this factory maps nothing automatically — every field move is your code.
Use it when the conversion is lossy or custom; for same-name structural mapping, `Telescope.map(...)` /
`Telescope.mapper(...)` is the recommended path. What's different is that the conversion becomes a value, so it threads
into longer paths.

```java
final Telescope<UserEntity, UserDto> userConversion = Telescope.from(UserEntity.class)
  .to(UserDto.class)
  .using((e) -> new UserDto(e.id(), e.email(), e.name()), (d) -> new UserEntity(d.id(), d.email(), d.name()));

UserDto dto = userConversion.read(entity); // forward

UserEntity updated = userConversion.update(entity, (d) -> new UserDto(d.id(), d.email().toLowerCase(), d.name()));
//                                                                                              ↑ round-trips through DTO, returns Entity
```

The conversion composes into longer paths like any other telescope:

```java
record EntityPage(List<UserEntity> items, int total) {}

// Walk into the page, view each entity as a DTO, focus the email, lowercase it.
// Result is an EntityPage with UserEntity items — entities modified by round-tripping through DTO.
Telescope.of(EntityPage.class)
        .each(EntityPage::items)
        .then(userConversion)                  // ← the conversion participates in the path
        .field(UserDto::email)
        .update(page, String::toLowerCase);
```

## Deep recursive mapping (`Telescope.map(A.class, B.class, to(...)...)`)

The recommended shape for record-to-record (and POJO↔POJO, and cross-paradigm) conversion: pass the source and target
classes up front, then varargs of `MapStep` rows (`MapStep` is the sealed supertype of the `Mapping` field rows and the
`WriteHint` / `NullHint` behavior hints — one varargs slot for all three). **Recursion is the default.** Same-named
components identity-map, nested records / POJOs recurse, `List<X>↔List<Y>` / `Set<X>↔Set<Y>` / `Map<K, X>↔Map<K, Y>` /
`Optional<X>↔Optional<Y>` lift the inner-element conversion through the container automatically (to any depth —
`List<Map<K, Set<X>>>` works by construction). You only spell the _differences_.

> Auto means exact name + type, nothing more — no fuzzy heuristics, no flattening, no inferred relationships (that's
> ModelMapper / Dozer territory, and they lost to MapStruct for good reasons). Anything that isn't an exact match you
> declare yourself with a `Mapping.to(srcAcc, tgtAcc)` or `Mapping.via(srcAcc, tgtAcc, nestedMapper)` row.

```java
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.via;

// All same-name, no overrides — the pure-copy 1-liner:
final Telescope<UserEntity, UserDto> userConversion = Telescope.map(UserEntity.class, UserDto.class);

// Tree-deep mapping with two renames — every other field figures itself out:
final Telescope<CompanyEntity, CompanyDto> companyConversion = Telescope.map(
  CompanyEntity.class,
  CompanyDto.class,
  to(CompanyEntity::founded, CompanyDto::since), // top-level rename
  to(UserEntity::name, UserDto::fullName)
); // applies wherever User↔UserDto recurses
```

The second example covers a 5-level structure — `Company → Department → Team → User → Address` — with `List`, `Map`, and
`Optional` containers at multiple depths. Both renames are declared _once_; the `User::name → UserDto::fullName` rule
fires _every time_ recursion encounters the `UserEntity ↔ UserDto` type pair (in `users[]`, in
`department.head: Optional<User>`, in `company.ceo: Optional<User>` — all three at once).

**How `to(...)` overrides are keyed.** Each `to(srcAccessor, tgtAccessor)` row carries its source and target record
classes implicitly via the method references. `Telescope.map(...)` reads them via `SerializedLambda` and uses
`(sourceClass, targetClass)` as the key. When the recursion lands on a matching pair, the row's correspondence is
applied; otherwise the recursion auto-resolves that component.

**Cycle handling.** Self-referencing structures (a `User` that contains `Optional<User>`) terminate naturally — the
recursion caches each type pair as it descends, and re-entry returns the in-progress entry instead of recursing forever.

**Override forms.** Static-import-friendly factories on `Mapping`:

| Factory                       | Purpose                                                                                         | MapStruct equivalent                        |
| ----------------------------- | ----------------------------------------------------------------------------------------------- | ------------------------------------------- |
| `to(src, tgt)`                | Rename, same leaf type                                                                          | `@Mapping(source, target)`                  |
| `to(src, tgt, fwd, bwd)`      | Bidirectional typed transform                                                                   | `@Mapping(source, target, qualifiedBy)`     |
| `toOneWay(src, tgt, fn)`      | Forward-only typed transform                                                                    | (separate `@Mapper` interface)              |
| `toOrElse(src, tgt, default)` | Null-coalesce to a default value                                                                | `@Mapping(defaultValue = "...")`            |
| `toOrElseGet(src, tgt, sup)`  | Null-coalesce via a `Supplier`                                                                  | `@Mapping(defaultExpression = "java(…)")`   |
| `enumTo(src, tgt, SE, TE)`    | By-name enum mapping, exhaustiveness checked at mapper construction                             | `@ValueMapping(source = "X", target = "Y")` |
| `via(src, tgt, mapper)`       | Drop in a pre-built nested mapper                                                               | (composition by hand)                       |
| `constant(tgt, value)`        | Forward-only literal at the target slot                                                         | `@Mapping(constant = "...")`                |
| `compute(tgt, supplier)`      | Forward-only supplier-computed value                                                            | `@Mapping(expression = "java(...)")`        |
| `drop(src)`                   | Skip the source field; backward fills it with null (references) or the JLS default (primitives) | `@Mapping(ignore = true)`                   |

Example — three of those rows together:

```java
import static io.github.eschizoid.telescope.mapping.Mapping.*;

Telescope.mapper(
  UserEntity.class,
  UserDto.class,
  to(UserEntity::name, UserDto::fullName),
  toOrElse(UserEntity::region, UserDto::region, "EMEA"),
  enumTo(UserEntity::status, UserDto::status, EntityStatus.class, DtoStatus.class)
);
```

The `via(...)` row works in two flavors: pass an **accessor-typed** mapper (e.g.,
`Mapper<List<UserEntity>, List<UserDto>>`) and telescope uses it as-is, or pass an **element-typed** mapper
(`Mapper<UserEntity, UserDto>`) and telescope detects the accessor's container shape (`List`, `Set`, `Optional`, `Map`
values) and the mapper lifts through the container automatically. One row either way — no separate `viaList` / `viaSet`
factories.

Recursion is auto by default — there's no `auto()` row to declare.

**Result threads through longer paths** like any other telescope:

```java
Telescope.of(EntityPage.class)
        .each(EntityPage::items)
        .then(companyConversion)
        .field(CompanyDto::name)
        .update(page, String::toUpperCase);   // entities modified by round-tripping through the DTO
```

**`Telescope.mapper(A.class, B.class, ...)` — Mapper sibling.** Same factory, returns `Mapper<A, B>` instead of
`Telescope<A, B>`. Same row syntax; same recursion. Useful for nested-mapper composition via `via(src, tgt, mapper)`.

For lossy or one-way conversions (dropping fields, non-invertible transforms), use `from/to/using` with hand-written
functions. Telescope still won't auto-discover anything fuzzy — recursion only follows exact name matches plus the
same-shape container rule.
