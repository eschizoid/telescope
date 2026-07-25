# Navigation — the DSL surface & cookbook

The full `Telescope<S, A>` method inventory, worked recipes for every path shape, and the null semantics of the whole
surface. [← back to README](../README.md)

## The DSL surface

A single class, `Telescope<S, A>`, where `S` is the root type and `A` is the leaf you focus on. The full method
inventory lives here as a reference; pick what you need by what you're trying to do, not by reading top-to-bottom.

### Build

| Method                                                             | What it does                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| ------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Telescope.of(Class<S>)`                                           | Start at the root type.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `Telescope.lens(getter, setter)`                                   | Build a single-focus telescope directly, no reflection. Used by `@Focus` codegen; handy for hot paths.                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `Telescope.from(A).to(B).using(fwd, back)`                         | Build a `Telescope<A, B>` backed by a bidirectional conversion that composes into longer paths.                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `Telescope.map(A.class, B.class, MapStep...)`                      | **Recommended.** Deep recursive mapping for any combination of records and POJOs (record↔record, POJO↔POJO, cross-paradigm at any depth). Same-name components identity-map, nested records/beans recurse, `List`/`Set`/`Map`/`Optional` lift the inner conversion through the container automatically. Override rows (`Mapping.to`, `Mapping.via`) and write-strategy hints (`WriteHint.writeBean(target, strategy)`) apply at any depth where their type pair appears. Sibling `Telescope.mapper(...)` returns `Mapper<A, B>`.                  |
| `Telescope.ofBean(Class<P>)`                                       | Start a native POJO telescope — `.field`/`.each` navigate the bean directly, rebuilding via strategy (see [Working with POJOs](pojos.md)).                                                                                                                                                                                                                                                                                                                                                                                                        |
| `.field(Class::accessor)`                                          | Descend into a record field via method reference. **Compile-checked.**                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `.fieldByName(String)`                                             | Descend by field name — the runtime escape hatch for late-binding (config-driven paths). **Runtime-checked:** wrong name → runtime error. A wrong name surfaces at the first operation, not at path build.                                                                                                                                                                                                                                                                                                                                        |
| `.fieldByName(String, Class<B>)`                                   | Same as above with an inline type witness for cleaner `var` inference. The `Class<B>` is inference sugar, **not validated** against the actual field.                                                                                                                                                                                                                                                                                                                                                                                             |
| `.each(Class::collectionAccessor)`                                 | Descend into a `List`/`Set`/`Iterable` field and broadcast over elements. Element type inferred from the method ref. **Compile-checked.**                                                                                                                                                                                                                                                                                                                                                                                                         |
| `.list(Class::accessor)` / `.setField` / `.mapField` / `.optional` | Typed-container variants: keep the container type for later traversal. Return `ListTelescope<S, X>` / `SetTelescope<S, X>` / `MapTelescope<S, K, V>` / `OptionalTelescope<S, X>` — sealed subclasses of `Telescope` whose typed terminal (`.each()` / `.values()` / `.present()`) descends into elements via pure lattice composition. **Compile-checked, no runtime dispatch.** `setField` / `mapField` (1.0 rename) disambiguate from the write terminal `set(S, A)` and the static deep-conversion factory `Telescope.map(Class, Class, ...)`. |
| `Telescope.asList(path)` / `asSet` / `asMap` / `asOptional`        | Promote a pre-built `Telescope<S, List<X>>` (or `Set`/`Map`/`Optional`) into the typed subclass so the compile-checked terminal becomes available. Useful when composing path fragments.                                                                                                                                                                                                                                                                                                                                                          |
| `.eachValue(Class::mapAccessor)`                                   | Like `each`, but for `Map` values (keys preserved).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `.whenPresent(Class::optionalAccessor)`                            | Like `each`, but for `Optional` — no-op if empty.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `.as(Class)`                                                       | Narrow to a sealed-type case. Non-matching values pass through.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `.filter(Predicate)`                                               | Restrict to elements matching the predicate.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `.then(otherTelescope)`                                            | Compose two telescopes.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |

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

name.read(alice);                        // "alice"
name.set(alice, "Bob");                  // User with name="Bob"
name.update(alice, String::toUpperCase); // User with name="ALICE"
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

updatedDiff.update(event, s -> s + "!"); // no-op if not Updated
updatedDiff.find(event);                 // Optional<String>
```

### Optional field

```java
record Profile(String id, Optional<String> nickname) {}

final Telescope<Profile, String> nick = Telescope.of(Profile.class).whenPresent(Profile::nickname);

nick.update(profile, String::toUpperCase); // no-op if nickname is empty
```

### Map values

```java
record Index(Map<String, Integer> byKey) {}

final Telescope<Index, Integer> values = Telescope.of(Index.class).eachValue(Index::byKey);

values.update(index, v -> v * 10);
```

### Typed container leaves (pre-built fragments)

When you want a path that ends _at_ the container (not at its elements), use the typed `.list(Class::accessor)` /
`.setField(...)` / `.mapField(...)` / `.optional(...)` instance methods. They return narrower subclasses
(`ListTelescope`, `SetTelescope`, `MapTelescope`, `OptionalTelescope`) whose typed terminal step (`.each()` /
`.values()` / `.present()`) descends into elements with zero runtime container dispatch — pure lattice composition,
fully compile-checked.

```java
record Box(List<String> tags) {}

// Build the list-typed path once; descend on demand.
final ListTelescope<Box, String> tags = Telescope.of(Box.class).list(Box::tags);
final Telescope<Box, String> elements = tags.each(); // typed .each() — compile-checked

elements.update(box, String::toUpperCase);

// Set / Map / Optional follow the same shape.
record Cart(Set<Item> items) {}

final SetTelescope<Cart, Item> items = Telescope.of(Cart.class).setField(Cart::items);
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
  over(EMAILS, String::toLowerCase),
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
  .update(EMAILS, String::toLowerCase)
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

### Multi-edit in one pass — stop at the common ancestor

`Telescope.all(over(...), over(...))` runs one structural pass per edit; each `over(...)` navigates from the root and
rebuilds the spine. That's the right default: each edit stays on its own line and the paths can be completely unrelated.
But when several edits target fields under the same parent, you can collapse them into a single pass with no extra API.
Navigate to the common ancestor and do the multi-field work in one `update` lambda.

```java
// Two passes — the Company spine is rebuilt once per edit:
Telescope.all(
  over(EMAILS, String::toLowerCase),   // Company → … → User.email
  over(USER_NAMES, titleCase))             // Company → … → User.name
  .apply(company);

// One pass — stop one level shallower, at the User, and rebuild it once:
static final Telescope<Company, User> USERS = Telescope.of(Company.class)
  .each(Company::departments)
  .each(Department::teams)
  .each(Team::users);

USERS.update(company, u -> new User(titleCase.apply(u.name()), u.age(), u.email().toLowerCase(), u.address()));
```

One navigation, one spine rebuild; the per-field work happens in plain Java at the ancestor. The trade is explicitness
for throughput: the lambda names every component (including the untouched ones), so prefer the `Telescope.all(...)` form
for readability and reach for the common-ancestor form when the edits cluster under one parent and the update sits
somewhere hot. For a bean ancestor the same shape works with a copy-and-set lambda; for records, `javac` keeps the
lambda honest — adding a component to `User` breaks this call site until you decide what the new field does.

---

## Null semantics

What null does at every position on the surface, verified against the source:

| Situation                                        | Behavior                                                        |
| ------------------------------------------------ | --------------------------------------------------------------- |
| null root on `read()`                            | `NoSuchElementException` (no focused value)                     |
| null root on `find`/`toList`/`count`/`exists`    | empty / `[]` / 0 / false                                        |
| null root on `set`/`update` (records)            | returns null, fn never runs                                     |
| null intermediate hop (record or bean)           | null propagates on reads; see [pojos](pojos.md) for bean writes |
| null container field (List/Set/Map, any surface) | focuses nothing; update is a no-op                              |
| null `Optional` field (vs empty)                 | focuses nothing, same as empty                                  |
| null element inside a collection                 | visited — it is a focus; fn receives null                       |
| `mapper.forward(null)`                           | null in, null out                                               |

Telescope and Mapper values are immutable and thread-safe — build once, share freely, `static final` is the intended
home. Errors: lambdas (vs method refs) are rejected when the path is built; mapper rows are validated at factory time;
`fieldByName` typos surface at first use. `updateEither`/`updateValidated` do **not** catch exceptions — typed failure
is for values your `fn` returns; a thrown exception propagates raw. The two `updateAsync` overloads differ on a sync
throw from `fn`: the no-executor overload throws to the caller, the executor overload captures it in the returned
future.
