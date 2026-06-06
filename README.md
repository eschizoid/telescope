<p align="center">
  <img src="img/logo.png" alt="telescope — deep-copy DSL for Java records and POJOs" width="320" />
</p>

# telescope

**Deep-copy DSL for Java records and POJOs.**

One type. No category-theory jargon. Update fields deep inside immutable records — through lists, sets, maps, optionals,
and sealed-type variants — without writing copy constructors by hand. Got POJOs? Navigate them natively or bridge them
to records; the same DSL applies.

[![JVM 25+](https://img.shields.io/badge/JVM-25%2B-brightgreen.svg?&logo=openjdk)](https://openjdk.org/projects/jdk/25/)
[![Build](https://github.com/eschizoid/telescope/actions/workflows/ci.yaml/badge.svg)](https://github.com/eschizoid/telescope/actions/workflows/ci.yaml)
[![Codecov](https://codecov.io/gh/eschizoid/telescope/graph/badge.svg?token=a235ea8b-e6dc-45c6-8fea-e5050940c5d4)](https://codecov.io/gh/eschizoid/telescope)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.eschizoid/telescope.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.eschizoid/telescope)
[![Javadoc](https://javadoc.io/badge2/io.github.eschizoid/telescope/javadoc.svg?color=purple)](https://javadoc.io/doc/io.github.eschizoid/telescope)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

---

## 30 seconds

### Records

```java
import io.github.eschizoid.telescope.Telescope;

record Address(String city, String zip) {}

record User(String name, int age, String email, Address address) {}

record Team(String name, List<User> users) {}

record Department(String name, List<Team> teams) {}

record Company(String name, List<Department> departments) {}
```

One task — lowercase every user's email in the whole company tree — done both ways.

#### Without telescope

```java
final Company lowered = new Company(
  company.name(),
  company
    .departments()
    .stream()
    .map((d) ->
      new Department(
        d.name(),
        d
          .teams()
          .stream()
          .map((t) ->
            new Team(
              t.name(),
              t
                .users()
                .stream()
                .map((u) -> new User(u.name(), u.age(), u.email().toLowerCase(), u.address()))
                .toList()
            )
          )
          .toList()
      )
    )
    .toList()
);
```

#### With telescope

```java
final Telescope<Company, String> emails = Telescope.of(Company.class)
  .each(Company::departments)
  .each(Department::teams)
  .each(Team::users)
  .field(User::email);

final Company lowered = emails.update(company, String::toLowerCase);
```

~25 lines of manual reconstruction — every constructor enumerated, every untouched field threaded through by hand —
versus one reusable path. And the path isn't single-use:

```java
emails.toList(company);   // List<String> of every email
emails.count(company);    // how many
```

### Beans

POJOs don't need a mirror record. Navigate the bean directly with `ofBean`; `set`/`update` rebuild it immutably, so the
original is never mutated:

```java
class Address {
  /* getCity()/setCity(), getZip()/setZip() */
}

class User {
  /* getName(), getAddress() + setters */
}

final User moved = Telescope.ofBean(User.class)
  .field(User::getAddress)
  .field(Address::getCity)
  .update(user, String::toUpperCase); // new User; `user` untouched
```

Prefer to stay in records? Convert a POJO with `Telescope.map(Pojo.class, Record.class, ...)` and navigate that — see
[Working with POJOs](#working-with-pojos).

That's the library. No `Iso`, `Lens`, `Prism`, `Affine`, `Traversal`, `Getter`, `Setter`, `Fold` in user-facing code.

---

## What it is _not_

- Not a MapStruct competitor. MapStruct owns compile-time bean mapping. For flat `Entity → Dto` work, write a static
  method or use MapStruct.
- Not a fuzzy auto-mapper. `Telescope.map(...)` matches fields by exact name and type, nothing more — no fuzzy name
  heuristics, no flattening, no inferred relationships (that's ModelMapper / Dozer territory, and they lost to MapStruct
  for good reasons). Anything that isn't an exact name match you declare yourself with a `Mapping.to(srcAcc, tgtAcc)` or
  `Mapping.via(srcAcc, tgtAcc, nestedMapper)` row.
- Not category theory. Internally, it's the same idea as a Monocle "Traversal" (get-many + modify-many), but you never
  have to type those words.

---

## Installation

Published to Maven Central under `io.github.eschizoid`. Two artifacts: `telescope` (the DSL, required) and
`telescope-codegen` (the optional `@Focus` annotation processor — see
[Compile-time field navigation](#compile-time-field-navigation-focus-codegen)).

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation("io.github.eschizoid:telescope:0.3.0")
}
```

Maven:

```xml
<dependency>
  <groupId>io.github.eschizoid</groupId>
  <artifactId>telescope</artifactId>
  <version>0.3.0</version>
</dependency>
```

### Compile-time `@Focus` codegen (optional)

Add the processor only if you use the `@Focus` path. It's inert otherwise — the annotation is source-retention.

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation("io.github.eschizoid:telescope:0.3.0")
    annotationProcessor("io.github.eschizoid:telescope-codegen:0.3.0")
}
```

Maven:

```xml
<dependency>
  <groupId>io.github.eschizoid</groupId>
  <artifactId>telescope</artifactId>
  <version>0.3.0</version>
</dependency>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <configuration>
        <annotationProcessorPaths>
          <path>
            <groupId>io.github.eschizoid</groupId>
            <artifactId>telescope-codegen</artifactId>
            <version>0.3.0</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

### JPMS / modular consumers

`core` is a named module, `io.github.eschizoid.telescope`. If your project has a `module-info.java`, add:

```java
requires io.github.eschizoid.telescope;
```

`telescope-codegen` is a compile-time-only processor and isn't required on the module path.

---

## The DSL surface

A single class, `Telescope<S, A>`, where `S` is the root type and `A` is the leaf you focus on.

### Which entry point?

Two questions decide it: are you working with **records** or **POJOs**, and do you want to **navigate** one type in
place or **convert** between two types?

| You want to…                          | Records                                       | POJOs                                | POJO ⇄ record                                  |
| ------------------------------------- | --------------------------------------------- | ------------------------------------ | ---------------------------------------------- |
| **Navigate & update** in place        | `Telescope.of(R.class)`                       | `Telescope.ofBean(P.class)`          | bridge first (below), then navigate the record |
| **Convert / map** between two types   | `Telescope.map(A.class, B.class, to(...), …)` | `Telescope.map(A.class, B.class, …)` | `Telescope.map(P.class, R.class, …)`           |
| **Reflection-free** (compile-checked) | `@Focus` (navigate)                           | `@BeanFocus` (navigate)              | `@Bridge` (convert, any pair)                  |

Conversions are bidirectional `Iso`s, so any cell in the middle row composes into a longer navigation path with
`.then(...)`. Mismatched names get an explicit `Mapping.to(srcAccessor, tgtAccessor)` row in the `Telescope.map(...)`
call; classes the auto-detect can't handle get a `WriteHint.writeBean(target, strategy)` row. Both are covered under
[Working with POJOs](#working-with-pojos).

### Build

| Method                                                      | What it does                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| ----------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Telescope.of(Class<S>)`                                    | Start at the root type.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `Telescope.lens(getter, setter)`                            | Build a single-focus telescope directly, no reflection. Used by `@Focus` codegen; handy for hot paths.                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `Telescope.from(A).to(B).using(fwd, back)`                  | Build a `Telescope<A, B>` backed by an `Iso` — bidirectional type conversion that composes into longer paths.                                                                                                                                                                                                                                                                                                                                                                                                             |
| `Telescope.map(A.class, B.class, MapStep...)`               | **Recommended.** Deep recursive mapping for any combination of records and POJOs (record↔record, POJO↔POJO, cross-paradigm at any depth). Same-name components identity-map, nested records/beans recurse, `List`/`Set`/`Map`/`Optional` lift the inner Iso through the container automatically. Override rows (`Mapping.to`, `Mapping.via`) and write-strategy hints (`WriteHint.writeBean(target, strategy)`) apply at any depth where their type pair appears. Sibling `Telescope.mapper(...)` returns `Mapper<A, B>`. |
| `Telescope.ofBean(Class<P>)`                                | Start a native POJO telescope — `.field`/`.each` navigate the bean directly, rebuilding via strategy (see [Working with POJOs](#working-with-pojos)).                                                                                                                                                                                                                                                                                                                                                                     |
| `.field(Class::accessor)`                                   | Descend into a record field via method reference. **Compile-checked.**                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `.fieldByName(String)`                                      | Descend by field name — the runtime escape hatch for late-binding (config-driven paths). **Runtime-checked:** wrong name → runtime error.                                                                                                                                                                                                                                                                                                                                                                                 |
| `.fieldByName(String, Class<B>)`                            | Same as above with an inline type witness for cleaner `var` inference. The `Class<B>` is inference sugar, **not validated** against the actual field.                                                                                                                                                                                                                                                                                                                                                                     |
| `.each(Class::collectionAccessor)`                          | Descend into a `List`/`Set`/`Iterable` field and broadcast over elements. Element type inferred from the method ref. **Compile-checked.**                                                                                                                                                                                                                                                                                                                                                                                 |
| `.list(Class::accessor)` / `.set` / `.map` / `.optional`    | Typed-container variants: keep the container type for later traversal. Return `ListPath<S, X>` / `SetPath<S, X>` / `MapPath<S, K, V>` / `OptionalPath<S, X>` — sealed subclasses of `Telescope` whose typed terminal (`.each()` / `.values()` / `.present()`) descends into elements via pure lattice composition. **Compile-checked, no runtime dispatch.**                                                                                                                                                              |
| `Telescope.asList(path)` / `asSet` / `asMap` / `asOptional` | Promote a pre-built `Telescope<S, List<X>>` (or `Set`/`Map`/`Optional`) into the typed subclass so the compile-checked terminal becomes available. Useful when composing path fragments.                                                                                                                                                                                                                                                                                                                                  |
| `.eachValue(Class::mapAccessor)`                            | Like `each`, but for `Map` values (keys preserved).                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `.whenPresent(Class::optionalAccessor)`                     | Like `each`, but for `Optional` — no-op if empty.                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `.as(Class)`                                                | Narrow to a sealed-type case. Non-matching values pass through.                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `.filter(Predicate)`                                        | Restrict to elements matching the predicate.                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `.then(otherTelescope)`                                     | Compose two telescopes.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |

### Read

| Method         | Returns                                                                                                                                                                                                                                                                                      |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `.read(S)`     | The first focused value. Throws if absent.                                                                                                                                                                                                                                                   |
| `.find(S)`     | `Optional<A>` of the first focused value.                                                                                                                                                                                                                                                    |
| `.toList(S)`   | `List<A>` of all focused values.                                                                                                                                                                                                                                                             |
| `.count(S)`    | How many values are focused.                                                                                                                                                                                                                                                                 |
| `.exists(S)`   | `true` if there's at least one.                                                                                                                                                                                                                                                              |
| `.withIndex()` | Index-aware chainable view (`Telescope.WithIndex<S, A>`). Exposes `.update(S, BiFunction<Integer, A, A>)`, `.toList(S)` → `List<Indexed<A>>`, `.find(S)`, `.count(S)`, `.exists(S)` — the same operations as the parent, with each focused value paired with its 0-based traversal position. |

### Write

| Method                                         | Returns                                                                                                                                                |
| ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `.set(S, A)`                                   | New `S` with every focused value replaced by the given one.                                                                                            |
| `.update(S, Function<A, A>)`                   | New `S` with every focused value transformed.                                                                                                          |
| `.updateAsync(S, fn, Executor)`                | Bounded-concurrency async update; pass a fixed pool to cap concurrent invocations.                                                                     |
| `.updateIndexed(S, BiFunction<Integer, A, A>)` | Transform every focused value with its 0-based position in traversal order.                                                                            |
| `.toListIndexed(S)`                            | `List<Indexed<A>>` — every focused value paired with its position.                                                                                     |
| `.update(Telescope<S, X>, Function<X, X>)`     | Accumulate an edit through a pre-built path; returns `Telescope<S, S>` carrying the running chain. See [Multi-edit](#multi-edit). **Compile-checked.** |
| `.with(Function<A, A>)`                        | Accumulate an edit at the current focus (inline-path equivalent of `.update(path, fn)`); returns `Telescope<S, S>`. **Compile-checked.**               |
| `.apply(S)`                                    | Run every accumulated `.update(path, fn)` / `.with(fn)` edit against the source, in insertion order. Returns a new `S`.                                |

Multi-edit packing (static factories — see [Multi-edit](#multi-edit)):

| Method                                       | Returns                                                                                                                           |
| -------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| `Telescope.all(Edit<S>...)`                  | Reusable `Telescope<S, S>` normalizer that runs every edit, in argument order, on `apply(s)`. **Compile-checked.**                |
| `Edit.over(Telescope<S, X>, Function<X, X>)` | Pair a pre-built path with its per-leaf transformation. Static-import-friendly: `import static …Edit.over;`. **Compile-checked.** |

---

## Cookbook

### A single field

```java
final Telescope<User, String> name = Telescope.of(User.class).field(User::name);

name.read(alice);                            // "alice"
name.set(alice, "Bob");                      // User with name="Bob"
name.update(alice, String::toUpperCase);     // User with name="ALICE"
```

### Nested fields

```java
final Telescope<User, String> city = Telescope.of(User.class)
        .field(User::address)
        .field(Address::city);

city.update(alice, String::toUpperCase);
```

### Every element of a collection inside a record

```java
final Telescope<Team, String> userNames = Telescope.of(Team.class)
        .each(Team::users)
        .field(User::name);

userNames.update(team, String::toUpperCase);
userNames.toList(team);                       // List<String>
```

### Sealed-type case

```java
sealed interface Event permits Created, Updated, Deleted {}
record Created(String id) implements Event {}
record Updated(String id, String diff, int revision) implements Event {}
record Deleted(String id) implements Event {}

final Telescope<Event, String> updatedDiff = Telescope.of(Event.class)
        .as(Updated.class)
        .field(Updated::diff);

updatedDiff.update(event, s -> s + "!");      // no-op if not Updated
updatedDiff.find(event);                      // Optional<String>
```

### Optional field

```java
record Profile(String id, Optional<String> nickname) {}

final Telescope<Profile, String> nick = Telescope.of(Profile.class).whenPresent(Profile::nickname);

nick.update(profile, String::toUpperCase);   // no-op if nickname is empty
```

### Map values

```java
record Index(Map<String, Integer> byKey) {}

final Telescope<Index, Integer> values = Telescope.of(Index.class).eachValue(Index::byKey);

values.update(index, v -> v * 10);
```

### Typed container leaves (pre-built fragments)

When you want a path that ends _at_ the container (not at its elements), use the typed `.list(Class::accessor)` /
`.set(...)` / `.map(...)` / `.optional(...)` instance methods. They return narrower subclasses (`ListPath`, `SetPath`,
`MapPath`, `OptionalPath`) whose typed terminal step (`.each()` / `.values()` / `.present()`) descends into elements
with zero runtime container dispatch — pure lattice composition, fully compile-checked.

```java
record Box(List<String> tags) {}

// Build the list-typed path once; descend on demand.
final ListPath<Box, String> tags = Telescope.of(Box.class).list(Box::tags);
final Telescope<Box, String> elements = tags.each(); // typed .each() — compile-checked

elements.update(box, String::toUpperCase);

// Set / Map / Optional follow the same shape.
record Cart(Set<Item> items) {}

final SetPath<Cart, Item> items = Telescope.of(Cart.class).set(Cart::items);
items.each().field(Item::sku).update(cart, String::toUpperCase);
```

For pre-built paths from elsewhere — composed `Telescope.then(...)` fragments, return types of helper methods, etc. —
promote them with `Telescope.asList(...)` / `.asSet(...)` / `.asMap(...)` / `.asOptional(...)` so the typed terminal
becomes available:

```java
final Telescope<Company, List<Department>> raw = ...; // built somewhere else
Telescope.asList(raw).each().field(Department::name).update(co, String::toLowerCase);
```

### Indexed traversal

When a read or update depends on position, not just value, use the indexed forms. The index is the 0-based position in
traversal order (flat across nested `each` levels):

```java
final Telescope<Team, String> members = Telescope.of(Team.class).each(Team::members);

members.toListIndexed(team);                               // [Indexed[0, "alice"], Indexed[1, "bob"], ...]
members.updateIndexed(team, (i, name) -> i + ": " + name); // "0: alice", "1: bob", ...
```

### Filter mid-path

```java
final Telescope<Company, String> engineeringEmails = Telescope.of(Company.class)
        .each(Company::departments)
        .filter(d -> "Engineering".equals(d.name()))
        .each(Department::teams)
        .each(Team::users)
        .field(User::email);

engineeringEmails.update(company, String::toLowerCase);
// Engineering emails lowercased; Sales untouched.
```

### Sealed-case + collection

```java
record Stream(List<Event> events) {}

final Telescope<Stream, Integer> bumpRevisions = Telescope.of(Stream.class)
        .each(Stream::events)
        .as(Updated.class)
        .field(Updated::revision);

bumpRevisions.update(stream, r -> r + 1);
// Created / Deleted events pass through unchanged.
```

### Sibling access

A plain `update` lambda only sees the focused value. When the transform needs sibling fields (the focused price needs
the SKU; the focused user needs the team name), close over the source — it's already in scope, since you pass it as the
first argument.

```java
record Team(String name, List<User> users) {}

record User(String name, String bio) {}

static final Telescope<Team, User> USERS = Telescope.of(Team.class).each(Team::users);

// Set every user's bio to mention the team name. The lambda reads the sibling `team.name()`.
final Team stamped = USERS.update(team, (user) -> new User(user.name(), "Member of " + team.name()));
```

This works for every variant — `updateAsync`, `updateEither`, `updateValidated`, `updateOptional` — because the root the
lambda needs is the same value you already hold. If the source is an expression rather than a variable, hoist it to a
local first (`final var team = fetchTeam();`) and close over that.

### Multi-edit

To apply several edits at different paths in one go, declare each path once as a static final, then pack the edits with
`Telescope.all(over(...), over(...))`. Every step is fully compile-checked.

**Recommended form — `Telescope.all(over(...), over(...))`.** Each `over(PATH, fn)` is one edit; `Telescope.all(...)`
folds them into a reusable `Telescope<S, S>` whose `.apply(s)` runs every edit in argument order.

```java
import static io.github.eschizoid.telescope.Edit.over;

static final Telescope<Company, String> EMAILS = Telescope.of(Company.class)
  .each(Company::departments)
  .each(Department::teams)
  .each(Team::users)
  .field(User::email);

static final Telescope<Company, String> DEPT_NAMES = Telescope.of(Company.class)
  .each(Company::departments)
  .field(Department::name);

static final Telescope<Company, String> USER_NAMES = Telescope.of(Company.class)
  .each(Company::departments)
  .each(Department::teams)
  .each(Team::users)
  .field(User::name);

final Telescope<Company, Company> normalize = Telescope.all(
  over(EMAILS,     String::toLowerCase),
  over(DEPT_NAMES, String::trim),
  over(USER_NAMES, titleCase));

final Company done = normalize.apply(company);
normalize.apply(companyB);   // reusable across sources
```

`over(path, fn)` ties a `Telescope<S, X>` to a `Function<X, X>`; `javac` enforces the leaf type match. Each edit lives
on its own line, the count is visible at a glance, and there is no chain-blur between paths.

**Single-edit shortcut.** For one edit, just call `update` on the path:

```java
EMAILS.update(company, String::toLowerCase);
```

**Chain accumulator (alternative).** The same semantics as `Telescope.all(...)` are also available as a fluent chain via
`.update(path, fn)` and `.with(fn)` terminated by `.apply(source)` — useful when you want an inline path mid-chain
without naming it. The chain reads less clearly for multiple distinct paths (the navigation segments visually blur), so
prefer `Telescope.all(over(...))` when packing two or more edits.

```java
// Equivalent to the Telescope.all(...) form above:
Telescope.of(Company.class)
  .update(EMAILS,     String::toLowerCase)
  .update(DEPT_NAMES, String::trim)
  .update(USER_NAMES, titleCase)
  .apply(company);

// Inline one-shot trailing edit on a pre-built chain:
Telescope.of(Company.class)
  .update(EMAILS, String::toLowerCase)
  .each(Company::departments).field(Department::name).with(String::trim)
  .apply(company);
```

Edits run sequentially in argument / insertion order; the second sees the first's result, not the original source. An
empty `Telescope.all()` (or an unedited chain) returns the source unchanged from `.apply(...)`.

---

## Type conversion

Two records that represent the same data (`Entity ↔ Dto`) convert through a bidirectional `Iso` that composes into
longer paths like any other telescope.

### Hand-written (`from / to / using`)

Write the two conversion functions yourself; telescope doesn't auto-map (that's MapStruct's territory). What's different
is that the conversion becomes a value, so it threads into longer paths.

```java
final Telescope<UserEntity, UserDto> userIso = Telescope.from(UserEntity.class)
  .to(UserDto.class)
  .using((e) -> new UserDto(e.id(), e.email(), e.name()), (d) -> new UserEntity(d.id(), d.email(), d.name()));

UserDto dto = userIso.read(entity); // forward

UserEntity updated = userIso.update(entity, (d) -> new UserDto(d.id(), d.email().toLowerCase(), d.name()));
//                                                                                              ↑ round-trips through DTO, returns Entity
```

The conversion is an `Iso`, which means it composes into longer paths:

```java
record EntityPage(List<UserEntity> items, int total) {}

// Walk into the page, view each entity as a DTO, focus the email, lowercase it.
// Result is an EntityPage with UserEntity items — entities modified by round-tripping through DTO.
Telescope.of(EntityPage.class)
        .each(EntityPage::items)
        .then(userIso)                         // ← Iso participates in the lattice
        .field(UserDto::email)
        .update(page, String::toLowerCase);
```

### Deep recursive mapping (`Telescope.map(A.class, B.class, to(...)...)`)

The recommended shape for record-to-record (and POJO↔POJO, and cross-paradigm) conversion: pass the source and target
classes up front, then varargs of `MapStep` rows. **Recursion is the default.** Same-named components identity-map,
nested records / POJOs recurse, `List<X>↔List<Y>` / `Set<X>↔Set<Y>` / `Map<K, X>↔Map<K, Y>` / `Optional<X>↔Optional<Y>`
lift the inner-element Iso through the container automatically (to any depth — `List<Map<K, Set<X>>>` works by
construction). You only spell the _differences_.

```java
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.via;

// All same-name, no overrides — the pure-copy 1-liner:
final Telescope<UserEntity, UserDto> userMapper = Telescope.map(UserEntity.class, UserDto.class);

// Tree-deep mapping with two renames — every other field figures itself out:
final Telescope<CompanyEntity, CompanyDto> companyMapper = Telescope.map(
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

**Override forms.** Three accepted row shapes:

```java
to(UserEntity::name, UserDto::fullName)                                              // rename, same type
to(EventEntity::year, EventDto::year, Object::toString, Integer::parseInt)           // typed transform
via(UserEntity::address, UserDto::address, addressMapper)                            // drop in a pre-built nested mapper
```

Recursion is auto by default — there's no `auto()` row to declare.

**Result threads through longer paths** like any other telescope:

```java
Telescope.of(EntityPage.class)
        .each(EntityPage::items)
        .then(companyMapper)
        .field(CompanyDto::name)
        .update(page, String::toUpperCase);   // entities modified by round-tripping through the DTO
```

**`Telescope.mapper(A.class, B.class, ...)` — Mapper sibling.** Same factory, returns `Mapper<A, B>` instead of
`Telescope<A, B>`. Same row syntax; same recursion. Useful for nested-mapper composition via `via(src, tgt, mapper)`.

For lossy or one-way conversions (dropping fields, non-invertible transforms), use `from/to/using` with hand-written
functions. Telescope still won't auto-discover anything fuzzy — recursion only follows exact name matches plus the
same-shape container rule.

---

## Working with POJOs

Telescope's deep-mapping factory handles any combination of records and POJOs through one entry point. The same
`Telescope.map(A.class, B.class, ...)` call covers record↔record, POJO↔POJO, and the cross-paradigm record↔POJO mix at
any depth — the engine picks per side whether to drive the canonical constructor (records) or `Beans.autoWriter` (POJOs)
at every type pair the recursion encounters. The alternative is to navigate the POJO directly with
`Telescope.ofBean(...)`. Either way updates are immutable.

### Convert — `Telescope.map` / `Telescope.mapper`

**Unified deep mapping.** Pass the two root classes plus any override / hint rows; recursion does the rest. Same-name
components identity-map, nested records/POJOs recurse, `List`/`Set`/`Map`/`Optional` lift the inner Iso through the
container automatically (to any depth — `List<Map<K, Set<X>>>` resolves by construction). The result is a
`Telescope<A, B>` (an `Iso`), so it composes with anything else.

```java
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.via;

class LegacyUser {
  /* getId(), getEmail(), getName() + a no-arg ctor / all-args ctor / builder() */
}

record UserRecord(String id, String email, String name) {}

// Same-name 1-liner — every component lines up by getter/component name.
final Telescope<LegacyUser, UserRecord> bridge = Telescope.map(LegacyUser.class, UserRecord.class);

UserRecord rec = bridge.read(legacyUser); // forward

LegacyUser back = bridge.set(legacyUser, rec); // backward
```

**Renames and transforms.** When names differ, supply `Mapping.to(srcAcc, tgtAcc)`; for typed transforms,
`Mapping.to(srcAcc, tgtAcc, forward, backward)`; for pre-built nested mappers, `Mapping.via(srcAcc, tgtAcc, mapper)`.
Each row is keyed by the declaring class of its accessors via `SerializedLambda`, so a single row applies wherever the
recursion lands on that type pair — top level or N levels deep.

```java
final Telescope<AccountBean, AccountRecord> bridge = Telescope.map(
  AccountBean.class,
  AccountRecord.class,
  to(AccountBean::getName, AccountRecord::displayName), // rename
  to(EventBean::getYear, EventRecord::year, Integer::toString, Integer::parseInt)
); // typed transform
```

**Nested-collection bridges work automatically.** A record component `List<SubRecord>` whose POJO side is
`List<SubPojo>` recurses without a special hop — the container `Iso` is lifted by `Iso.liftList` once the inner pair is
resolved. Same for `Set`, `Map`-values, `Optional`, and arbitrarily-deep nesting:

```java
record OrderRecord(String sku, int qty) {}

record CartRecord(String id, List<OrderRecord> orders) {}

class OrderPojo {
  /* getSku(), getQty() + ... */
}

class CartPojo {
  /* getId(), getOrders() returns List<OrderPojo> */
}

final Telescope<CartPojo, CartRecord> cart = Telescope.map(CartPojo.class, CartRecord.class);
// CartPojo ↔ CartRecord, with the List<OrderPojo> ↔ List<OrderRecord> hop handled automatically.
```

**`writeBean` — pin a POJO write strategy.** `Beans.autoWriter` picks a ladder: `builder()` → no-arg ctor + setters →
no-arg ctor + reflective field injection → single public all-args ctor (when compiled with `-parameters` and ctor
parameter names match the property names). For classes the auto path refuses (immutable all-args-only POJOs without
`-parameters`, ambiguous multi-ctor classes), pass an explicit `WriteHint.writeBean(target, strategy)` row to force one
of `BUILDER` / `SETTERS` / `FIELDS` / `CONSTRUCTOR`:

```java
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.CONSTRUCTOR;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBean;

// OrderPojo has a public (String sku, int qty) ctor, no builder, no setters — autoWriter would
// refuse without -parameters. The hint forces the CONSTRUCTOR strategy explicitly.
final Telescope<OrderRecord, OrderPojo> conv = Telescope.map(
  OrderRecord.class,
  OrderPojo.class,
  writeBean(OrderPojo.class, CONSTRUCTOR),
  to(OrderRecord::sku, OrderPojo::getSku)
);
```

Validation is eager: a misconfigured hint (`BUILDER` on a no-builder class, hint targeting a record, duplicate hint,
unused hint) throws at `Telescope.map(...)` time — not on first `iso.to()` deep in production.

**Composing through a bridge.** The mapping result is a `Telescope<A, B>`, so it threads through a longer path the same
way any other telescope does:

```java
Telescope.of(Page.class)                  // Page is a record holding List<LegacyUser>
    .each(Page::items)
    .then(bridge)                         // each POJO ↔ record at this hop
    .field(UserRecord::email)
    .update(page, String::toLowerCase);
```

**`Telescope.mapper(...)` — the `Mapper<A, B>` sibling.** Same deep recursion, but the return is a `Mapper<A, B>`
exposing `read`/`forward`/`backward`/`patch`/`asTelescope`. `patch(base, partial)` overlays non-null fields of `partial`
onto `base` — useful for sparse JSON / form updates.

```java
final Mapper<UserBean, UserView> mapper = Telescope.mapper(UserBean.class, UserView.class);

final UserView withFresh = mapper.patch(view, new UserView(null, "new@x", null));
```

**`@Bridge` — reflection-free, compile-checked (any pair).** The codegen counterpart to `Telescope.map(...)`. Annotate
the source you own with the target type; the processor generates `<Source>Bridge.BRIDGE`, a `Telescope<Source, Target>`
built from direct component/getter reads and constructor / builder / setter calls. Both sides may be records or POJOs —
record⇄record, record⇄POJO, POJO⇄POJO. Fields match by name (a bijection); a name mismatch or a missing construction
strategy is a compile error, not a runtime one:

```java
import io.github.eschizoid.telescope.annotations.Bridge;

@Bridge(UserDto.class)
record UserEntity(String id, String email) {}

// Generated alongside:  UserEntityBridge.BRIDGE  (a Telescope<UserEntity, UserDto>)
UserDto dto = UserEntityBridge.BRIDGE.read(entity);

// BRIDGE is a Telescope value, so it threads through a longer path:
final Page lowered = Telescope.of(Page.class)
  .each(Page::entities) // each UserEntity on the page
  .then(UserEntityBridge.BRIDGE) // view it as a UserDto
  .field(UserDto::email)
  .update(page, String::toLowerCase);
```

It auto-detects each side's strategy at compile time (record canonical constructor; POJO name-matched constructor →
builder → no-arg + setters). Renames and per-field transforms can't be expressed in an annotation — use the runtime
`map` / `from/to/using` for those. Wire up `telescope-codegen` as shown under [Installation](#installation).

**`from/to/using` — hand-written.** When the mapping is lossy, one-directional, or just custom, write both functions
yourself:

```java
public static final Telescope<LegacyUser, UserRecord> USER_BRIDGE = Telescope.from(LegacyUser.class)
  .to(UserRecord.class)
  .using(
    (l) -> new UserRecord(l.getName(), l.getEmail(), l.getAddress()),
    (r) -> {
      final var u = new LegacyUser();
      u.setName(r.name());
      u.setEmail(r.email());
      u.setAddress(r.address());
      return u;
    }
  );
```

### Navigate — `ofBean`

When you'd rather not define a mirror record, navigate the POJO directly. `.field(Pojo::getX)` reads via the getter;
`set`/`update` rebuild the POJO immutably with that one property changed (write strategy auto-detected per type: builder
→ setters → field injection). Deep paths and `.each(...)` compose like records:

```java
Telescope.ofBean(LegacyUser.class)
  .field(LegacyUser::getAddress)
  .field(Address::getCity)
  .update(user, String::toUpperCase); // new LegacyUser; the original is untouched
```

**Cost — measured.** `ofBean` rebuilds the whole POJO and re-reads every getter at _each_ level of the path: a 3-level
update benchmarks at ~442 ns/op (~18x a hand-written copy, ~1.8x record reflection — see
[`benchmarks/`](benchmarks/README.md)). Fine for ordinary use (sub-microsecond); for a hot loop over many objects,
convert to a record once with `Telescope.map(Pojo.class, Record.class)` and navigate the record (or use `@BeanFocus`
codegen) instead. The runtime deep-mapping bridges are cheaper — ~114 ns (POJO→record) and ~142 ns (POJO↔POJO), in line
with the record→record mapper (~112 ns).

**Aliasing — beans aren't records.** An update rebuilds the _spine_ (the path to the changed field) with fresh objects
and shares references to untouched subtrees. With records that's always safe; with mutable POJOs the new and old object
share the same off-path sub-POJO instances, so mutating a shared sub-object afterward shows through both. Treat the
shared parts as effectively immutable.

### Scope

`Telescope.map(...)` / `@Bridge` match by exact name and need a same-named field on each side (with optional rename rows
via `Mapping.to(srcAcc, tgtAcc)`); nested collections recurse automatically. The `FIELDS` write strategy (and `ofBean`'s
field-injection fallback) uses `setAccessible`, so under JPMS the POJO's package must be `opens`'d to
`io.github.eschizoid.telescope` — `CONSTRUCTOR` / `BUILDER` / `SETTERS` (and all of `@Bridge`) use public members only.

---

## Compile-time, reflection-free navigation (`@Focus` / `@BeanFocus`)

The reflection-based `Telescope.of(User.class).field(User::name)` path resolves the field name at runtime — fast enough
for ordinary use (~100ns), but a typo or a rename surfaces as a runtime error, not a compile error. Annotate the types
you navigate with `@Focus` (records) or `@BeanFocus` (POJOs) and add the processor to your build; for each annotated
type the processor emits a sibling **fluent typed path navigator** that reads like the runtime DSL but is fully
compile-checked and reflection-free.

**Same path, two ways.** The two surfaces produce the same terminal `Telescope<Company, String>` and the same `update`
result — they only differ in _when_ the path is resolved (runtime vs `javac`) and _how_ it's dispatched (reflection vs
direct method-ref + constructor calls). On the [benchmarks](benchmarks/README.md), the reflective deep-field path
measures ~262 ns/op; the codegen lens path it desugars to measures ~45 ns/op (~5.8x).

```java
// Reflective — runtime resolution, ~100 ns per field hop
Telescope.of(Company.class)
  .each(Company::departments).each(Department::teams)
  .each(Team::users).field(User::email)
  .update(company, String::toLowerCase);

// Compile-time, reflection-free — same Telescope, generator-built
CompanyPath.start()
  .departments().each().teams().each()
  .users().each().email()
  .update(company, String::toLowerCase);
```

```java
import io.github.eschizoid.telescope.annotations.Focus;

@Focus record Address(String city, String zip) {}
@Focus record User(String name, int age, Address address) {}
@Focus record Team(String name, List<User> users) {}
@Focus record Company(String name, List<Team> teams) {}

// Generated: <X>Path<R> per annotated type plus a step class per collection-shaped component.
// Usage reads like the reflective DSL — but every hop is type-checked by javac and every read /
// rebuild is a direct method-ref + constructor call (no reflection):
final Telescope<Company, String> userNames = CompanyPath.start()
  .teams().each()        // step over List<Team> → TeamPath<Company>
  .users().each()        // step over List<User> → UserPath<Company>
  .name();               // terminal Telescope<Company, String>

final Company shouted = userNames.update(company, String::toUpperCase);

// Single fields are just as direct:
UserPath.start().address().city().update(alice, String::toUpperCase);
```

Each scalar component yields a terminal `Telescope<R, T>`; each sub-record component (also `@Focus`-annotated) yields a
`<Sub>Path<R>` to keep navigating; each container component yields a small step class whose `.each()` (List/Set/
Iterable), `.eachValue()` (Map values, keys preserved), or `.whenPresent()` (Optional) returns the element's `Path` when
the element is itself annotated, or a terminal `Telescope` otherwise. At any hop, `.get()` returns the current
`Telescope` — so a step or path _is_ a navigator, but every leaf is the same `Telescope<R, X>` value the reflective DSL
gives you.

**Ops at every hop, effects included.** Every generated `Path` and `Step` also forwards the full `Telescope` operation
surface — `read` / `find` / `toList` / `count` / `exists` / `set` / `update` / `updateIndexed` / `toListIndexed` /
`then` plus the four effect methods `updateAsync` (with or without `Executor`) / `updateOptional` / `updateEither` /
`updateValidated`. You don't need to terminate with `.get()` first; the navigator stands in for the wrapped Telescope at
any intermediate hop. So `CompanyPath.start().teams().each().users().each().updateAsync(company, svc::lookup, pool)`
returns a `CompletableFuture<Company>` directly, with the effect threaded through the generated chain.

**Bridge hops — conversion as a navigator step.** If a type carries both `@Focus`/`@BeanFocus` (so it has a `*Path`) and
`@Bridge(Target.class)` (so it has a `*Bridge.BRIDGE`), the navigator gains a fluent **`as<Target>()`** method that
chains the bridge in. The navigator becomes a single compile-checked surface for _both_ navigation _and_ conversion,
crossing paradigms naturally (record↔record, record↔POJO, POJO↔POJO):

```java
@Focus
@Bridge(UserDto.class)
record UserEntity(String id, String email) {}

@Focus
record UserDto(String id, String email) {}

// Navigate through the bridge into a target field, then update. The Iso round-trips, so the
// result is a new UserEntity:
final UserEntity lowered = UserEntityPath.start()
  .asUserDto() // → UserDtoPath<UserEntity>
  .email() // → Telescope<UserEntity, String>
  .update(entity, String::toLowerCase);
```

The return type degrades to a terminal `Telescope<R, Target>` when the target isn't itself annotated (so there's no
`<Target>Path` to chain into). The reverse direction (target's Path getting `.asSource()`) still goes through
`.then(SourceBridge.BRIDGE.reverse())` for now — forward only at the navigator level.

Gradle wiring:

```kotlin
implementation("io.github.eschizoid:telescope:0.3.0")
annotationProcessor("io.github.eschizoid:telescope-codegen:0.3.0")
```

`@Focus` and `@BeanFocus` are source-retention and inert without the processor, so annotating costs nothing if you don't
wire up codegen. Only top-level records / classes are supported (the generated top-level navigator can't reference a
nested type's constructor).

**`@BeanFocus` — the POJO analog.** Same surface as `@Focus`, applied to a POJO with either a static `builder()` or a
no-arg constructor + `setX` setters. Field injection isn't available to generated code, so a POJO that exposes neither
is a compile error; reach for runtime `Telescope.ofBean` in that case. Compare ~488 ns for the runtime `ofBean` 3-level
path vs ~15 ns for a generated `@Bridge` conversion in the benchmark — the navigator gets you the same reflection-free
win for navigation.

```java
import io.github.eschizoid.telescope.annotations.BeanFocus;

@BeanFocus public class UserBean { /* getId/getEmail + setters, or a static builder() */ }

// Generated alongside: UserBeanPath<R> with the same fluent surface as a record navigator.
UserBeanPath.start().email().update(user, String::toLowerCase);   // no reflection
```

---

## Effects

The same path that powers `update(...)` lifts into four common effects — async, all-or-nothing, short-circuit, and
error-accumulating. Pick the method by the function you have; the type system picks the applicative. Chaining stages of
different effects is handled by the bridge methods on `Either` / `Validated` — see [Chaining stages](#chaining-stages).

### Picking the method

| Your function returns  | Call this              | You get back           | Semantics                        |
| ---------------------- | ---------------------- | ---------------------- | -------------------------------- |
| `A → A` (pure)         | `update(...)`          | `S`                    | total, synchronous               |
| `CompletableFuture<A>` | `updateAsync(...)`     | `CompletableFuture<S>` | sequence; any failure propagates |
| `Optional<A>`          | `updateOptional(...)`  | `Optional<S>`          | any empty propagates             |
| `Either<E, A>`         | `updateEither(...)`    | `Either<E, S>`         | short-circuit on first `Left`    |
| `Validated<E, A>`      | `updateValidated(...)` | `Validated<E, S>`      | accumulate every error           |

**Picking between `updateEither` and `updateValidated`:**

- Use **`updateEither`** when failures should _halt work_: parsers where a malformed root makes children meaningless,
  dependent stages, expensive per-element calls. Subsequent elements are never even called.
- Use **`updateValidated`** when you want _every_ problem reported: form validation (show the user every wrong field at
  once), batch quality reports, cheap predicates over many elements. Every element is processed; failures are collected.

The difference is control flow, not just result shape. You can't recover short-circuit behavior by post-converting a
Validated result, and you can't recover all-errors reporting from an Either that stopped after the first failure.

### The four effects, one at a time

Each effectful method works on its own. Pick the one that matches the function you have. The examples below share this
tiny domain:

```java
record Order(String id, String email) {}

record Batch(List<Order> orders) {}

// Reusable path declared once, used by every example below.
static final Telescope<Batch, String> ALL_EMAILS = Telescope.of(Batch.class).each(Batch::orders).field(Order::email);
```

**`updateAsync` — fan out, gather back.**

```java
// Hit an HTTP service to normalize every email in parallel. The future completes
// when every per-element future has completed; failures propagate.
final CompletableFuture<Batch> done = ALL_EMAILS.updateAsync(batch, normalizer::normalizeAsync);
```

The path navigation, the per-element future creation, and the structural rebuild collapse into one method call. The
naive alternative — `stream().map(CompletableFuture::supplyAsync).collect(toList())` followed by
`CompletableFuture.allOf(...)` followed by manual reconstruction of the `Batch` — is the boilerplate this replaces.

**`updateValidated` — collect every error.**

```java
record EmailError(String email, String reason) {}

final Validated<EmailError, Batch> result = ALL_EMAILS.updateValidated(batch, this::checkEmail);

return result.fold(this::respondBadRequest, this::save);

// The per-element predicate lives in a named method — easier to read, easier to test:
private Validated<EmailError, String> checkEmail(final String email) {
  if (!email.contains("@")) return Validated.invalid(new EmailError(email, "missing @"));
  return Validated.valid(email.toLowerCase());
}
```

Every bad email across the entire batch is reported, not just the first one. The applicative does the accumulation. The
user code never touches an error list directly.

**`updateEither` — short-circuit on the first failure.**

```java
record ParseError(String input, String message) {}

final Either<ParseError, Batch> result = ALL_EMAILS.updateEither(batch, EmailParser::tryParse);

return result.fold(this::respondError, this::save);
```

The first email that fails to parse wins; subsequent emails aren't even called. Use this when the first failure is
enough — it's strictly cheaper than `updateValidated` because there's no accumulation.

**`updateOptional` — all-or-nothing.**

```java
// If any single email fails to mask (returns Optional.empty), the whole batch becomes empty —
// partial state is impossible.
final Optional<Batch> masked = ALL_EMAILS.updateOptional(batch, this::tryMask);
```

This is the right tool when a partially-updated structure would be a bug, not a feature.

### Bounded async

By default `updateAsync` invokes `fn` synchronously per focused element; concurrency is whatever the futures returned by
`fn` already had. To cap concurrent invocations, pass an `Executor`:

```java
try (final var pool = Executors.newFixedThreadPool(10)) {  // ≤10 in-flight HTTP calls
  final CompletableFuture<Batch> done = path.updateAsync(batch, this::fetchAsync, pool);
  done.join();
}
```

`fn` is wrapped in `CompletableFuture.supplyAsync(..., pool)`, so the executor bounds when `fn` is called. For fully
non-blocking `fn` (e.g. `HttpClient.sendAsync`) that's the right bound; for blocking work inside `fn`, the pool size is
the literal upper bound on in-flight operations.

### Working with `Either` and `Validated`

`Either<L, R>` and `Validated<E, A>` are sealed records shipped with the library, no Vavr/Arrow dependency. The typical
handler is `.fold(...)`:

```java
return parsed.fold(this::respondError, this::save);
```

Pattern matching also works when you need to destructure the value, but Java's inference can't elide the type parameters
in switch arms, so `.fold(...)` is usually less noisy:

```java
return switch (parsed) {
  case Either.Right<ParseError, Company>(var c) -> save(c);
  case Either.Left<ParseError, Company>(var err) -> respondError(err);
};
```

Both `Either` and `Validated` expose the same compact handler API:

| Method                                           | Notes                                                                                               |
| ------------------------------------------------ | --------------------------------------------------------------------------------------------------- |
| `fold(onLeft, onRight)`                          | Collapse both sides into a single value. Usually what you want.                                     |
| `map(f)`                                         | Transform the success side; failure passes through.                                                 |
| `isLeft()` / `isRight()`                         | Boolean tests, when a `switch` would be overkill.                                                   |
| `mapLeft(f)` (Either)                            | Transform the failure side; useful for normalizing error types at a boundary.                       |
| `mapErrors(f)` (Validated)                       | Same idea as `mapLeft`, applied to every accumulated error.                                         |
| `swap()` (Either)                                | Flip left and right.                                                                                |
| `flatMap(f)` (Either)                            | Sequence two Eithers; short-circuits on the first `Left`.                                           |
| `andThen(f)` (Validated)                         | Sequence two Validateds; short-circuits on `Invalid` (use `combine` to accumulate).                 |
| `Validated.combine(a, b, f)` (Validated, static) | Combine two Validateds; accumulates errors across both branches.                                    |
| `toValidated()` (Either)                         | Bridge to `Validated`: `Left(e)` becomes a single-element `Invalid([e])`.                           |
| `toEither()` (Validated)                         | Bridge to `Either`: `Invalid(errs)` becomes `Left(errs)`.                                           |
| `flatMapAsync(f)` (both)                         | Sequence an async stage; failures stay in the result, only success runs.                            |
| `toOptional()` (both)                            | Drop the error and bridge to JDK `Optional`. Use when downstream only cares about the success path. |
| `getOrElse(default)` (both)                      | Return the success value, or `default` on failure.                                                  |
| `getOrElseGet(supplier)` (both)                  | Same, with a lazy default for expensive cases.                                                      |
| `combineAll(List<…>)` (Validated, static)        | Combine a list of validations into a `Validated<E, List<A>>`; accumulates every error.              |

### Chaining stages

Multi-stage flows use the bridge methods on `Either` / `Validated` to keep the error channel consistent across different
effects. The pattern is: normalize each stage's error type with `mapErrors` / `mapLeft`, bridge between accumulating and
short-circuiting with `toEither` / `toValidated`, then `flatMap` / `andThen` for sync stages or `flatMapAsync` when the
next stage returns a `CompletableFuture`.

Sync-only example — validate emails, then look up users, with one unified `List<String>` error channel:

```java
// Stage 1: collect every bad email, then hand off to short-circuit code
// → Either<List<String>, Batch>
final Either<List<String>, Batch> afterEmails = emailPath
  .updateValidated(batch, this::checkEmail)
  .mapErrors(EmailError::reason) // EmailError -> String
  .toEither(); // accumulating -> short-circuit

// Stage 2: short-circuit on the first user lookup failure, normalize its error too
// → Either<List<String>, Batch>
final Either<List<String>, Batch> afterUsers = afterEmails.flatMap((b) ->
  userPath.updateEither(b, this::lookupUser).mapLeft((err) -> List.of(err.id() + " not found"))
);
```

Crossing into an async stage uses `flatMapAsync`, which mirrors `flatMap` but accepts a function returning a
`CompletableFuture`. Errors remain in the `Either` (or `Validated`) result; only the success side runs asynchronously:

```java
return afterUsers.flatMapAsync(ok -> enrichPath.updateAsync(ok, this::enrich));
// → CompletableFuture<Either<List<String>, Batch>>
```

---

## Constraints worth knowing

1. **Records only.** Field navigation rebuilds via the record's canonical constructor. Non-record types throw at runtime
   with a clear message. To work with POJOs, bridge them to a record — see [Working with POJOs](#working-with-pojos).
2. **Method references, not lambdas.** `User::name` works; `u -> u.name()` doesn't. The compiler synthesizes a name like
   `lambda$xx$0` and we can't recover the field name from it. The library throws a clear error.
3. **`List<T>` element types are inferred from the method-ref signature**, not from runtime generics. That's why
   `each(Team::users)` works without a type witness — `Team::users` has compile-time type `Function<Team, List<User>>`
   and Java unifies `E = User`.
4. **Reflection cost.** Field access uses `RecordComponent.getAccessor().invoke(...)` and the canonical constructor —
   roughly ~100ns per reflective field access, vs ~10ns for a hand-written record copy; the reflection-free `lens` path
   (`@Focus` codegen) sits in between. Fine for almost everything; matters for tight loops. See
   [`benchmarks/`](benchmarks/README.md) for measured numbers.
5. **Sibling-context updates close over the source.** A plain `update` lambda only sees the focused value. If you need
   to read sibling fields (e.g. focus `LineItem::unitPrice` but want the sibling `sku` to call a price service), the
   source is already in scope as the first argument — just reference it inside the lambda
   (`update(order, item -> … order.sku() …)`). Hoist the source to a local first if it's an expression.
6. **One documented runtime-check point on the runtime DSL.** Every typed entry point (`.field(Accessor)`,
   `.each(Accessor)`, `.list(Accessor)` / `.set` / `.map` / `.optional` and their typed terminals,
   `.eachValue(Accessor)`, `.whenPresent(Accessor)`, the static `Telescope.asList` / `asSet` / `asMap` / `asOptional`
   promotions, the bridges, `.with(fn)`, `.apply(S)`, every `update*` variant) is fully compile-checked. One escape
   hatch is _not_ compile-checked, by design, and it's named so the call site says so:
   - `.fieldByName(String)` / `.fieldByName(String, Class<B>)` — late-bound field name (config-driven paths). `javac`
     can't verify the name exists or that the inferred type matches the actual field. Wrong name → runtime error.

   For zero runtime-check points, use the **`@Focus` / `@BeanFocus` / `@Bridge` annotation processors** — they generate
   a typed `<X>Path<R>` navigator at compile time where every step is a typed method call.

7. **Pre-1.0 versioning policy — minor versions can break source and binary compatibility.** Telescope is still 0.x; we
   hold the right to evolve the public surface between minor releases when it improves the DSL. Recent breaks worth
   knowing about:
   - **The `Telescope.fromBean(...).viaX()` / `Telescope.mapBean(...).build()` fluent chains were demolished** in favor
     of the unified `Telescope.map(A.class, B.class, ...)` factory that handles record↔record, POJO↔POJO, and
     cross-paradigm in one entry point. Forcing a specific bean write strategy is now an explicit
     `WriteHint.writeBean(target, strategy)` row instead of `.viaFields()` / `.viaConstructor()` / `.viaBuilder()`.
   - **`Telescope.each()` no-arg (runtime-dispatched escape hatch) was deleted**; arrays are no longer first-class
     containers (wrap as `List`). Replacement: the typed `.list/.set/.map/.optional(accessor)` instance methods return
     narrower subclasses (`ListPath` / `SetPath` / `MapPath` / `OptionalPath`) whose `.each()` / `.values()` /
     `.present()` terminals are compile-checked; pre-built `Telescope<S, List<X>>` paths use
     `Telescope.asList(path).each()` (and friends).
   - **`.field(String)` / `.field(String, Class<B>)` renamed to `.fieldByName(...)`** so the runtime-check nature is
     loud at the call site (see constraint #6). Source-incompatible. No `@Deprecated` shim — clean break.

   After 1.0 these guarantees tighten — source + binary compat across minor versions, breaks only on majors. We're not
   there yet; keep your build configured to rebuild against each minor.

---

## Architecture

Two layers, one library:

- **`io.github.eschizoid.telescope.Telescope<S, A>`**: the DSL. The only thing users import. Wraps a `Traversal<S, A>`
  from the internal optics package.
- **`io.github.eschizoid.telescope.internal.optics.*`**: the optic lattice (`Fold`, `Getter`, `Setter`, `Traversal`,
  `Affine`, `Lens`, `Prism`, `Iso`) plus `Focus` factories and collection traversals. Package-private to the library.

Each DSL method builds the appropriate optic and composes it via the lattice:

| DSL call                | Built internally                                           | Composed via            |
| ----------------------- | ---------------------------------------------------------- | ----------------------- |
| `Telescope.of(C.class)` | `Iso.identity()`                                           | —                       |
| `.field(C::name)`       | `Records.fieldLens(name)` → `Lens<C, X>`                   | `Traversal.then(Lens)`  |
| `.each(C::items)`       | `Lens<C, Iterable<X>>` + `Traversals.eachIterable()`       | two `.then` calls       |
| `.list(C::items)`       | `Lens<C, List<X>>`; `.each()` adds `Traversals.eachList()` | one `.then` per step    |
| `.as(Updated.class)`    | `Prism.downcast(Updated.class)`                            | `Traversal.then(Prism)` |
| `.filter(p)`            | —                                                          | `Traversal.filter`      |

Operations (`read`, `set`, `update`, `toList`, `count`, `exists`) delegate to the underlying optic's methods. The
lattice handles all composition rules (`Lens.then(Prism) = Affine`, `Iso.then(Iso) = Iso`, etc.) and laws (get-set,
set-get, set-set, iso round-trip, prism round-trip).

If you ever want the optic types as public API (Monocle interop, or extending the library), they're already there in
`internal.optics`. Just promote the package or expose a typed factory.

---

## Build & test

```bash
./gradlew spotlessApply # format code
./gradlew build         # compile, run tests
```

The integration tests use Testcontainers and require a reachable Docker daemon. Linux and macOS Docker Desktop both work
out of the box (Testcontainers 2.x autodetects the socket). Without a reachable daemon the integration tests are
silently skipped, not failed.

---

## License

Apache 2.0 — see [LICENSE](LICENSE).
