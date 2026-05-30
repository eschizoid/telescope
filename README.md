<p align="center">
  <img src="img/logo.png" alt="telescope — deep-copy DSL for Java records and POJOs" width="320" />
</p>

# telescope

**Deep-copy DSL for Java records and POJOs.**

One type. No category-theory jargon. Update fields deep inside immutable records — through lists, sets, maps, optionals,
and sealed-type variants — without writing copy constructors by hand. Got POJOs? Navigate them natively or bridge them
to records; the same DSL applies.

---

## 30 seconds

### Records

```java
import org.telescope.Telescope;

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

Prefer to stay in records? Bridge a POJO to one with `fromBean` and navigate that instead — see
[Working with POJOs](#working-with-pojos).

That's the library. No `Iso`, `Lens`, `Prism`, `Affine`, `Traversal`, `Getter`, `Setter`, `Fold` in user-facing code.

---

## What it is _not_

- Not a MapStruct competitor. MapStruct owns compile-time bean mapping. For flat `Entity → Dto` work, write a static
  method or use MapStruct.
- Not a fuzzy auto-mapper. `.auto()` matches fields by exact name and type, nothing more — no fuzzy name heuristics, no
  flattening, no inferred relationships (that's ModelMapper / Dozer territory, and they lost to MapStruct for good
  reasons). Anything that isn't an exact match you declare yourself with `.field(...).to(...)`.
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
    implementation("io.github.eschizoid:telescope:0.1.0")
}
```

Maven:

```xml
<dependency>
  <groupId>io.github.eschizoid</groupId>
  <artifactId>telescope</artifactId>
  <version>0.1.0</version>
</dependency>
```

### Compile-time `@Focus` codegen (optional)

Add the processor only if you use the `@Focus` path. It's inert otherwise — the annotation is source-retention.

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation("io.github.eschizoid:telescope:0.1.0")
    annotationProcessor("io.github.eschizoid:telescope-codegen:0.1.0")
}
```

Maven:

```xml
<dependency>
  <groupId>io.github.eschizoid</groupId>
  <artifactId>telescope</artifactId>
  <version>0.1.0</version>
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
            <version>0.1.0</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

### JPMS / modular consumers

`core` is a named module, `org.telescope`. If your project has a `module-info.java`, add:

```java
requires org.telescope;
```

`telescope-codegen` is a compile-time-only processor and isn't required on the module path.

---

## The DSL surface

A single class, `Telescope<S, A>`, where `S` is the root type and `A` is the leaf you focus on.

### Which entry point?

Two questions decide it: are you working with **records** or **POJOs**, and do you want to **navigate** one type in
place or **convert** between two types?

| You want to…                          | Records                                              | POJOs                        | POJO ⇄ record                                  |
| ------------------------------------- | ---------------------------------------------------- | ---------------------------- | ---------------------------------------------- |
| **Navigate & update** in place        | `Telescope.of(R.class)`                              | `Telescope.ofBean(P.class)`  | bridge first (below), then navigate the record |
| **Convert / map** between two types   | `Telescope.map(A).to(B)` or `from(A).to(B).using(…)` | `Telescope.mapBean(A).to(B)` | `Telescope.fromBean(P).to(R)`                  |
| **Reflection-free** (compile-checked) | `@Focus` (navigate)                                  | `@BeanFocus` (navigate)      | `@Bridge` (convert, any pair)                  |

Conversions are bidirectional `Iso`s, so any cell in the middle row composes into a longer navigation path with
`.then(...)`. Mismatched names and dropped fields are handled by `.rename(...)` / `.ignoreUnmatched()`, covered under
[Working with POJOs](#working-with-pojos).

### Build

| Method                                                        | What it does                                                                                                                                          |
| ------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Telescope.of(Class<S>)`                                      | Start at the root type.                                                                                                                               |
| `Telescope.lens(getter, setter)`                              | Build a single-focus telescope directly, no reflection. Used by `@Focus` codegen; handy for hot paths.                                                |
| `Telescope.from(A).to(B).using(fwd, back)`                    | Build a `Telescope<A, B>` backed by an `Iso` — bidirectional type conversion that composes into longer paths.                                         |
| `Telescope.map(A).to(B).field(...).to(...).build()`           | Declarative field-by-field record mapping; synthesizes a bidirectional `Telescope<A, B>`.                                                             |
| `Telescope.ofBean(Class<P>)`                                  | Start a native POJO telescope — `.field`/`.each` navigate the bean directly, rebuilding via strategy (see [Working with POJOs](#working-with-pojos)). |
| `Telescope.fromBean(P).to(R).viaFields/Constructor/Builder()` | Bridge a POJO ⇄ record at runtime; `.via`/`.viaEach` convert nested objects/collections.                                                              |
| `Telescope.mapBean(A).to(B).build()`                          | Convert one POJO ⇄ another (name-matched, auto-detected rebuild strategy).                                                                            |
| `.field(Class::accessor)`                                     | Descend into a record field via method reference.                                                                                                     |
| `.field(String)`                                              | Descend by field name (when method refs aren't possible).                                                                                             |
| `.field(String, Class<B>)`                                    | Same as above with an inline type witness — removes the leading `.<B>field(...)` ceremony.                                                            |
| `.each(Class::collectionAccessor)`                            | Descend into a `List`/`Set`/`Iterable` field and broadcast over elements. Element type inferred from the method ref.                                  |
| `.each()` (no-arg)                                            | Broadcast over elements when you already hold a `Telescope<S, SomeContainer>` — also the only form that works for primitive arrays (`int[]`, etc.).   |
| `.eachValue(Class::mapAccessor)`                              | Like `each`, but for `Map` values (keys preserved).                                                                                                   |
| `.whenPresent(Class::optionalAccessor)`                       | Like `each`, but for `Optional` — no-op if empty.                                                                                                     |
| `.as(Class)`                                                  | Narrow to a sealed-type case. Non-matching values pass through.                                                                                       |
| `.filter(Predicate)`                                          | Restrict to elements matching the predicate.                                                                                                          |
| `.then(otherTelescope)`                                       | Compose two telescopes.                                                                                                                               |

### Read

| Method       | Returns                                    |
| ------------ | ------------------------------------------ |
| `.read(S)`   | The first focused value. Throws if absent. |
| `.find(S)`   | `Optional<A>` of the first focused value.  |
| `.toList(S)` | `List<A>` of all focused values.           |
| `.count(S)`  | How many values are focused.               |
| `.exists(S)` | `true` if there's at least one.            |

### Write

| Method                                         | Returns                                                                            |
| ---------------------------------------------- | ---------------------------------------------------------------------------------- |
| `.set(S, A)`                                   | New `S` with every focused value replaced by the given one.                        |
| `.update(S, Function<A, A>)`                   | New `S` with every focused value transformed.                                      |
| `.updateAsync(S, fn, Executor)`                | Bounded-concurrency async update; pass a fixed pool to cap concurrent invocations. |
| `.updateIndexed(S, BiFunction<Integer, A, A>)` | Transform every focused value with its 0-based position in traversal order.        |
| `.toListIndexed(S)`                            | `List<Indexed<A>>` — every focused value paired with its position.                 |

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

### Indexed traversal

When a read or update depends on position, not just value, use the indexed forms. The index is the 0-based position in
traversal order (flat across nested `each` levels):

```java
final Telescope<Team, String> members = Telescope.of(Team.class).each(Team::members);

members.toListIndexed(team);                       // [Indexed[0, "alice"], Indexed[1, "bob"], ...]
members.updateIndexed(team, (i, name) -> i + ": " + name);   // "0: alice", "1: bob", ...
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

### Field-by-field (`map / to / field / build`)

When the two records line up field-for-field (the common `Entity ↔ Dto` case), declaring the whole conversion function
twice is tedious. `Telescope.map(...)` lets you declare per-field correspondences and synthesizes the bidirectional
conversion for you:

```java
record UserEntity(String id, String email, String name) {}

record UserDto(String id, String email, String fullName) {}

final Telescope<UserEntity, UserDto> userMapper = Telescope.map(UserEntity.class)
  .to(UserDto.class)
  .field(UserEntity::id)
  .to(UserDto::id)
  .field(UserEntity::email)
  .to(UserDto::email)
  .field(UserEntity::name)
  .to(UserDto::fullName) // rename across the boundary
  .build();

UserDto dto = userMapper.read(entity);
```

Field types are checked at compile time — `.field(UserEntity::name)` (a `String`) requires `.to(UserDto::...)` to also
be a `String`. The result is an `Iso`, so it threads through longer paths exactly like the `from/to/using` form:

```java
Telescope.of(EntityPage.class)
        .each(EntityPage::items)
        .then(userMapper)
        .field(UserDto::email)
        .update(page, String::toLowerCase);   // entities modified by round-tripping through the DTO
```

Because the result is a bidirectional `Iso`, **every component on both records must be mapped** — a lossless round-trip
needs a value for every constructor parameter in both directions. `build()` throws if the mapping isn't a bijection.

**`.auto()` for same-name fields.** When the records line up (the common case), don't declare each pair — `.auto()` maps
every component whose name and type match, and you only spell out the renames:

```java
record OrderEntity(String id, long amount, String currency) {}

record OrderDto(String id, long amount, String currency) {}

final Telescope<OrderEntity, OrderDto> orderMapper = Telescope.map(OrderEntity.class).to(OrderDto.class).auto().build();

// auto() + explicit override for the one rename:
final Telescope<UserEntity, UserDto> userMapper = Telescope.map(UserEntity.class)
  .to(UserDto.class)
  .auto() // id, email map themselves
  .field(UserEntity::name)
  .to(UserDto::fullName) // the one rename
  .build();
```

`.auto()` is exact name + type match only — no fuzzy heuristics, no nested traversal. Explicit `.field(...).to(...)`
overrides the auto-mapped link for that target.

**Transforms** for type-converting fields, with both directions so the mapping stays a bijection:

```java
.field(Event::at).to(EventDto::at, Instant::toString, Instant::parse)
```

**Nested mappers** with `.via(...)` for sub-records (the nested mapper supplies both directions):

```java
final Mapper<AddrEntity, AddrDto> addressMapper = Telescope.map(AddrEntity.class)
  .to(AddrDto.class)
  .auto()
  .buildMapper();

final Telescope<UserEntity, UserDto> userMapper = Telescope.map(UserEntity.class)
  .to(UserDto.class)
  .field(UserEntity::name)
  .to(UserDto::name)
  .field(UserEntity::address)
  .via(UserDto::address, addressMapper)
  .build();
```

**`patch()` for sparse updates.** `buildMapper()` (instead of `build()`) returns a `Mapper<A, B>` that retains the field
links, so it can overlay a partially-populated target onto a full source — only the non-null patch fields change:

```java
final Mapper<User, UserPatch> mapper = Telescope.map(User.class).to(UserPatch.class).auto().buildMapper();

// dtoPatch has a new email, null everything else → only the email changes:
User updated = mapper.patch(user, new UserPatch(null, "new@x.com", null));
```

`Mapper.asTelescope()` gives you the composable telescope for threading through paths; `Mapper.read(a)` does a one-shot
forward conversion. For lossy or one-way conversions (dropping fields, non-invertible transforms), use `from/to/using`
with hand-written functions. This is still not auto-discovery: `.auto()` only matches exact names, and you name every
rename and transform explicitly.

---

## Working with POJOs

Telescope's core is records-only, but JavaBeans-style POJOs (Hibernate entities, Lombok `@Data`, any mutable bean) have
two routes: **convert** a POJO to/from a record (or another POJO) and operate on the result, or **navigate it natively**
with `ofBean`. Either way updates are immutable — nothing you pass in is mutated.

### Convert — bridges and mappers

**`fromBean` — POJO ⇄ record, runtime.** Matches the record's components to the POJO's getters by name; you pick how the
reverse (record → POJO) direction rebuilds the bean.

```java
class LegacyUser {
  /* getId(), getEmail(), getName() + a no-arg ctor / all-args ctor / builder() */
}

record UserRecord(String id, String email, String name) {}

// pick the reverse strategy at the terminal call:
final Telescope<LegacyUser, UserRecord> bridge = Telescope.fromBean(LegacyUser.class).to(UserRecord.class).viaFields(); // no-arg ctor + field injection

//                                                                                          .viaConstructor(); // all-args ctor, in component order
//                                                                                          .viaBuilder();     // static builder()

UserRecord rec = bridge.read(legacyUser); // forward:  POJO   -> record

LegacyUser back = bridge.set(legacyUser, rec); // reverse: record -> POJO
```

The result is a `Telescope<LegacyUser, UserRecord>` (an `Iso`), so it composes. The common shape is traversing a
collection of POJOs that hangs off a record:

```java
Telescope.of(Page.class)            // Page is a record holding List<LegacyUser>
        .each(Page::items)
        .then(bridge)               // each POJO ↔ record at this hop
        .field(UserRecord::email)
        .update(page, String::toLowerCase);
```

**Nested collections — `.viaEach` / `.via`.** A whole-object bridge is _shallow_: a record component that's a
`List<SubRecord>` whose POJO side is `List<SubPojo>` won't auto-convert (erasure would otherwise let the wrong element
type through). Supply an element bridge:

```java
final Telescope<OrderPojo, OrderRecord> order = Telescope.fromBean(OrderPojo.class)
  .to(OrderRecord.class)
  .viaConstructor();

final Telescope<CartPojo, CartRecord> cart = Telescope.fromBean(CartPojo.class)
  .to(CartRecord.class)
  .viaEach(CartRecord::orders, order) // List<OrderPojo> ⇄ List<OrderRecord>, element-wise
  .viaFields();
// .via(component, subBridge) does the same for a single nested sub-object.
```

**`mapBean` — POJO ⇄ POJO.** The bean analog of `map(...)`: properties matched by name, each side rebuilt via its
auto-detected strategy. Bidirectional, so it round-trips and composes.

```java
class LegacyUser {
  /* getId(), getEmail() + setters (or an all-args ctor / builder) */
}

class UserView {
  /* getId(), getEmail() + setters */
}

final Telescope<LegacyUser, UserView> bridge = Telescope.mapBean(LegacyUser.class).to(UserView.class).build();

UserView view = bridge.read(legacyUser); // forward:  LegacyUser -> UserView

LegacyUser back = bridge.set(legacyUser, view); // reverse: UserView   -> LegacyUser
```

**Renames and unmatched properties.** Both bridges match by name; when names differ, map them with `.rename(...)` —
`fromBean` maps a record component to a POJO property, `mapBean` maps property to property. By default every property
needs a counterpart, so the conversion stays a bijection. `mapBean.ignoreUnmatched()` drops that requirement: a property
with no match on the other side is left out (lossy, one-way). `fromBean` already tolerates extra POJO properties — the
record drives the mapping, so a POJO field with no matching component keeps its rebuilt default and doesn't round-trip.

```java
// fromBean: record component 'displayName' <-> POJO property 'name'
Telescope.fromBean(AccountBean.class).to(AccountRecord.class)
  .rename(AccountRecord::displayName, AccountBean::getName)
  .viaFields();

// mapBean: 'name' <-> 'fullName', and PersonView.role has no source -> drop it
Telescope.mapBean(PersonA.class).to(PersonView.class)
  .rename(PersonA::getName, PersonView::getFullName)
  .ignoreUnmatched()
  .build();
```

**`@Bridge` — reflection-free, compile-checked (any pair).** The codegen counterpart to `fromBean` / `mapBean` / `map`.
Annotate the source you own with the target type; the processor generates `<Source>Bridge.BRIDGE`, a
`Telescope<Source, Target>` built from direct component/getter reads and constructor / builder / setter calls. Both
sides may be records or POJOs — record⇄record, record⇄POJO, POJO⇄POJO. Fields match by name (a bijection); a name
mismatch or a missing construction strategy is a compile error, not a runtime one:

```java
import org.telescope.annotations.Bridge;

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
[`benchmarks/`](benchmarks/README.md)). Fine for ordinary use (sub-microsecond), but for a hot loop over many objects,
bridge to a record once with `fromBean` and navigate the record (or use `@BeanFocus` codegen) instead. The conversion
bridges are cheaper — `fromBean` ~123 ns, `mapBean` ~170 ns, in line with the record→record mapper (~112 ns).

**Aliasing — beans aren't records.** An update rebuilds the _spine_ (the path to the changed field) with fresh objects
and shares references to untouched subtrees. With records that's always safe; with mutable POJOs the new and old object
share the same off-path sub-POJO instances, so mutating a shared sub-object afterward shows through both. Treat the
shared parts as effectively immutable.

### Scope

`fromBean` / `mapBean` / `@Bridge` match by exact name and need a same-named field on each side; nested collections need
`.viaEach`. `viaFields` (and `ofBean`'s field-injection fallback) use `setAccessible`, so under JPMS the POJO's package
must be `opens`'d to `org.telescope` — `viaConstructor` / `viaBuilder` / setters (and all of `@Bridge`) use public
members only.

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
import org.telescope.annotations.Focus;

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
implementation("io.github.eschizoid:telescope:0.1.0")
annotationProcessor("io.github.eschizoid:telescope-codegen:0.1.0")
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
import org.telescope.annotations.BeanFocus;

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

---

## Architecture

Two layers, one library:

- **`org.telescope.Telescope<S, A>`**: the DSL. The only thing users import. Wraps a `Traversal<S, A>` from the internal
  optics package.
- **`org.telescope.internal.optics.*`**: the optic lattice (`Fold`, `Getter`, `Setter`, `Traversal`, `Affine`, `Lens`,
  `Prism`, `Iso`) plus `Focus` factories and collection traversals. Package-private to the library.

Each DSL method builds the appropriate optic and composes it via the lattice:

| DSL call                | Built internally                                  | Composed via            |
| ----------------------- | ------------------------------------------------- | ----------------------- |
| `Telescope.of(C.class)` | `Iso.identity()`                                  | —                       |
| `.field(C::name)`       | `Records.fieldLens(name)` → `Lens<C, X>`          | `Traversal.then(Lens)`  |
| `.each(C::items)`       | `Lens<C, List<X>>` + `Traversals.eachContainer()` | two `.then` calls       |
| `.as(Updated.class)`    | `Prism.downcast(Updated.class)`                   | `Traversal.then(Prism)` |
| `.filter(p)`            | —                                                 | `Traversal.filter`      |

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
