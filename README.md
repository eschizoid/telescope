<p align="center">
  <img src="img/logo.png" alt="telescope, an optics-based DSL for Java records and POJOs" width="320" />
</p>

# telescope

[![JVM 21+](https://img.shields.io/badge/JVM-21%2B-brightgreen.svg?&logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![Build](https://github.com/eschizoid/telescope/actions/workflows/ci.yaml/badge.svg)](https://github.com/eschizoid/telescope/actions/workflows/ci.yaml)
[![Codecov](https://codecov.io/gh/eschizoid/telescope/graph/badge.svg?token=a235ea8b-e6dc-45c6-8fea-e5050940c5d4)](https://codecov.io/gh/eschizoid/telescope)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.eschizoid/telescope-core.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.eschizoid/telescope-core)
[![Javadoc](https://javadoc.io/badge2/io.github.eschizoid/telescope-core/javadoc.svg?color=purple)](https://javadoc.io/doc/io.github.eschizoid/telescope-core)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

Telescope's one building block is a reusable typed path. A path is a value, built from method references and checked by
`javac`, that points at data anywhere inside a nested structure.

```java
static final Telescope<Company, String> EMAILS = Telescope.of(Company.class)
  .each(Company::departments)
  .each(Department::teams)
  .each(Team::users)
  .field(User::email);
```

Once you hold a path, the rest of the library follows from it. You can read through it, rebuild the whole tree through
it without mutating anything (`EMAILS.update(company, String::toLowerCase)`), collect or count every value it reaches,
and lift an update through an async or validation effect. You can also map between two types, because a mapping is the
same path applied across two shapes, with each row pairing a source path to a target path.

```java
Mapper<Order, OrderDto> mapper = Telescope.mapper(
  Order.class,
  OrderDto.class,
  to(Order::customerName, OrderDto::fullName)
);

OrderDto dto = mapper.forward(order); // same-name fields map automatically, and nesting recurses
Order back = mapper.backward(dto);    // reversible rows run backward from the same definition
mapper.explain();                     // the report is built from the mapper you are holding
```

The last line is the architectural difference with [MapStruct](https://mapstruct.org/) in one call. MapStruct's
abstraction is generated bean mapping, and it is very good at that job, so by design the mapping becomes a generated
class at compile time. Telescope's abstraction is the path, mapping is one use of it, and the result stays a value you
can compose with `.then(...)`, reuse across call sites, and ask questions of in production.

<p align="center">
  <img src="img/head-to-head.gif" alt="The telescope and MapStruct head-to-head test printing its own output: identical results, the default-policy unmapped-target case, a deep immutable update, and a mapper reporting what it maps." width="820" />
  <br />
  <sub>The <a href="examples/mapstruct-vs-telescope/"><code>mapstruct-vs-telescope</code></a> test printing its own
  output, trimmed for width.</sub>
</p>

There is one surface with two implementations behind it, and each one answers a different question. The runtime path
composes paths and mappers while the program runs, with no annotations and no build step, and it keeps working under
GraalVM native-image ([a native binary in CI checks this](docs/native-image.md)). When a loop turns hot, the `@Focus`
and `@Bridge` processors compile the same shapes to direct calls, which lands in the same performance range as
MapStruct's generated code in the included JMH workloads ([figures below](#measured-performance)). Telescope works on
Java records, POJOs, and Lombok `@Data` classes on Java 21 and later, and the Spring Boot starter and Quarkus extension
ship as separate artifacts.

For evidence, there is a [migration coverage matrix](docs/mapstruct-parity.md) scoring 29 MapStruct features against
telescope, with 13 covered fully and 16 covered partially, each partial row stating its limitation, and `file:line`
evidence throughout. There is also a [migration guide](docs/mapstruct-migration.md) that moves one mapper at a time, and
a [runnable head-to-head module](examples/mapstruct-vs-telescope/) where every claim is a passing test. The
[full comparison](#how-it-compares-to-mapstruct) is below.

## What telescope gives you

| Need                                      | Telescope gives you                                                                                       |
| ----------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| Deep reads and immutable updates          | One reusable `Telescope<S, A>` path, with `read`, `find`, `toList`, `set`, `update`, `updateValidated`.   |
| Mapping between records, POJOs, or both   | `Telescope.mapper(A.class, B.class, rows...)`, strict by default, bidirectional when rows are reversible. |
| Reading an untyped `Map<String, Object>`  | `Telescope.fromMap(T.class, extract(...))`, or `@FromMap` for a generated binder with no reflection.      |
| A gradual move from runtime to hot paths  | Start with no annotations, then move hot navigators and converters to `@Focus`, `@BeanFocus`, `@Bridge`.  |
| Production debugging without reading code | `explain()`, `trace(input)`, and opt-in `System.Logger` output, all from the mapper you already hold.     |
| Native-image and framework integration    | GraalVM metadata in core, a native verifier in CI, and Spring Boot and Quarkus registry artifacts.        |

If generated bean-to-bean mapping is all you need, MapStruct remains a strong choice. Telescope is for the cases where
the path itself is useful, which means deep updates, reusable navigation, effectful transforms, mappings you can run in
both directions, and composition at runtime that you can compile down later.

---

## Install

```kotlin
// Gradle (Kotlin DSL)
dependencies {
  implementation("io.github.eschizoid:telescope-core:1.8.0")
}
```

```xml
<!-- Maven -->
<dependency>
  <groupId>io.github.eschizoid</groupId>
  <artifactId>telescope-core</artifactId>
  <version>1.8.0</version>
</dependency>
```

The snippet above is the runtime. Compile-time codegen, the Spring Boot starter, the Quarkus extension, and JPMS setup
are [listed below](#published-artifacts).

---

## Quick start

Suppose you have nested data and you want to update a field deep inside it without writing copy constructors. The
example below is complete, so you can paste it into a `main` and run it.

```java
import io.github.eschizoid.telescope.Telescope;

record Address(String city, String zip) {}

record User(String name, Address address) {}

// 1. Build a typed path once. Telescope values are immutable and thread-safe, so static final suits them.
final var userCity = Telescope.of(User.class).field(User::address).field(Address::city);

// 2. Use the path for reading, updating, and everything else.
final var alice = new User("Alice", new Address("Springfield", "49007"));

String city = userCity.read(alice); // "Springfield"

User shouted = userCity.update(alice, String::toUpperCase); // city becomes "SPRINGFIELD", and alice is untouched
```

The model is that small. Everything else is the same path with a different terminal method, whether you are mapping
between types, navigating containers, or lifting through an async or validation effect.

Where to go next:

- Navigate `List<X>`, `Optional<X>`, and `Map<K, V>`, plus the whole DSL surface, in
  [docs/navigation.md](docs/navigation.md)
- Convert between types, either record to record or POJO to record, in
  [docs/type-conversion.md](docs/type-conversion.md)
- Ask a mapper what it maps, using `explain()`, `trace()`, or a log level, in
  [docs/introspection.md](docs/introspection.md)
- Lift through async, validated, either, and optional effects in [docs/effects.md](docs/effects.md)
- Bind navigators at compile time for hot paths in [docs/codegen.md](docs/codegen.md)

---

## Records, mapping, and beans

Quick start showed one deep update. The three shapes below are the ones you will spend most of your time in, and each
one fits in a screenful.

### Records

```java
import io.github.eschizoid.telescope.Telescope;

record Address(String city, String zip) {}

record User(String name, int age, String email, Address address) {}

record Team(String name, List<User> users) {}

record Department(String name, List<Team> teams) {}

record Company(String name, List<Department> departments) {}
```

Here is one task, lowercasing every user's email in a whole company tree, done both ways.

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

The first version is about 25 lines of manual reconstruction, where every constructor is spelled out and every untouched
field is threaded through by hand. The second is one path, and the path is reusable rather than single-use.

```java
emails.toList(company);   // List<String> of every email
emails.count(company);    // how many there are
```

To log or record metrics at a point in the path, attach an observer after that hop.

```java
final Telescope<Company, String> observedEmails = Telescope.of(Company.class)
  .each(Company::departments)
  .observe((dept) -> log.info("department: {}", dept.name()))
  .each(Department::teams)
  .observe((team) -> log.info("team: {}", team.name()))
  .each(Team::users)
  .observe((user) -> log.info("user: {}", user.email()))
  .field(User::email);

final Company result = observedEmails.update(company, String::toLowerCase);
```

An observer runs synchronously when a terminal operation executes, once for each value reached at that hop. Reads report
the current values from the outside in, and updates report rebuilt values from the inside out. Failed input futures,
`Either.Left`, `Optional.empty`, and `Validated.Invalid` skip observation, and a later reconstruction failure cannot
undo observations that were already emitted.

For example, you can count the users a read visits using an injected
[Micrometer `MeterRegistry`](https://docs.micrometer.io/micrometer/reference/concepts/counters.html).

```java
final var usersVisited = meterRegistry.counter("telescope.users.visited");

final Telescope<Company, String> meteredEmails = Telescope.of(Company.class)
  .each(Company::departments)
  .each(Department::teams)
  .each(Team::users)
  .observe((user) -> usersVisited.increment())
  .field(User::email);

final List<String> values = meteredEmails.toList(company);
```

When you want several edits on one structure rather than one, `Telescope.all` folds them into a single reusable
normalizer, with one `over(...)` row per path.

```java
final Telescope<Company, Company> normalize = Telescope.all(
  over(emails, String::toLowerCase),
  over(Telescope.of(Company.class).field(Company::name), String::strip)
);

final Company cleaned = normalize.apply(company);
```

The two edits above touch different fields of the same root, so the fold rebuilds that root once instead of twice. Edits
that share a longer prefix save more, because the hops they have in common are walked once. A path built by `then(...)`,
by `Telescope.lens(...)`, or by a generated navigator carries no hop record and falls back to running sequentially.

### Mapping

A mapping is the path applied across two shapes. Below is the same tree, translated to a partner-facing `CompanyDto`
with a few renamed fields, written as one definition that runs in both directions for the rows that are structurally
reversible.

```java
record AddressDto(String town, String postalCode) {}

record UserDto(String fullName, int age, String email, AddressDto address) {}

record TeamDto(String name, List<UserDto> users) {}

record DepartmentDto(String name, List<TeamDto> teams) {}

record CompanyDto(String name, List<DepartmentDto> departments) {}

final Mapper<Company, CompanyDto> dtoMapper = Telescope.mapper(
  Company.class,
  CompanyDto.class,
  to(User::name, UserDto::fullName), // a rename, applied everywhere User and UserDto recurse
  to(Address::city, AddressDto::town),
  to(Address::zip, AddressDto::postalCode)
);

final CompanyDto dto = dtoMapper.forward(company);

final Company restored = dtoMapper.backward(dto); // the same row list, run in reverse
```

Same-name fields map automatically and recursion is automatic, which covers `User::email`, `User::age`, and all the list
and tree wiring, so you only name what changes. For comparison, MapStruct's reverse direction is a second method on the
same interface, and `@InheritInverseConfiguration` derives eligible configuration from the forward method, though its
javadoc excludes expressions, constants, and default values from inheritance. Telescope has the mirror-image caveat,
because `constant`, `compute`, and one-way rows are forward-only, which is covered below.

When a flat field needs to land at a nested target leaf, which is MapStruct's
`@Mapping(source = "flat", target = "a.b.c")`, a navigator emitted by codegen is a first-class argument to
`Mapping.to(...)`.

```java
Telescope.mapper(Cart.class, CartDto.class,
  to(Cart::customerName, CartDtoTelescope.of().shipping().recipient().fullName()));
```

Every hop in that navigator is a typed method call, so `javac` checks each step and an IDE rename follows it.

For eager literals and per-call computed values stamped onto the target, which are MapStruct's
`@Mapping(constant = "...")` and `@Mapping(expression = "java(...)")`, you declare them in the same
`Telescope.mapper(...)` call.

```java
Telescope.mapper(Order.class, OrderDto.class,
  to(Order::id, OrderDto::id),
  constant(OrderDto::tenant, "production"),   // an eager literal
  compute(OrderDto::createdAt, Instant::now), // fresh on every call
  compute(OrderDto::traceId, UUID::randomUUID),
  compute(OrderDto::metadata, HashMap::new)); // a fresh container on every call
```

`constant` captures its value once when the row is built, and `compute` invokes the supplier on each forward call, which
is the right choice whenever a literal would share one mutable reference, as `HashMap::new`, `Instant::now`, and
`UUID::randomUUID` would. Both are forward-only by design, and the backward direction leaves the slot out of the source
rebuild, so references come back `null` and primitives come back at their JLS default. MapStruct documents the same
class of exclusion for inverse-inherited configuration.

Every mapper built this way can report what it does, with no generated source to read.

```java
dtoMapper.explain();
// Mapped:
//   ✓ name → fullName
//   ✓ city → town
//   ...
```

The rendered text is a view, and the structure behind it is data you can assert on. For a strict bidirectional mapper,
`explain().skipped().isEmpty() && explain().unusedSources().isEmpty()` means every field on both sides is accounted for,
and constant and computed slots are populated rather than skipped, so they do not appear as rows. The rest of the story,
including `trace(input)` with real values and narrating every conversion by flipping a log level, is in
[docs/introspection.md](docs/introspection.md).

### Reading an untyped map

Data arriving from a JSON body, a JDBC row, or a message header is often a `Map<String, Object>` rather than a typed
object. `Telescope.fromMap` binds one to a record or POJO, with one `extract(...)` row per component you want filled.

```java
final ForwardMapper<Map<String, Object>, CaseListRequest> requests = Telescope.fromMap(
  CaseListRequest.class,
  extract("bookingType", CaseListRequest::getBookingType, Object::toString),
  extract("caseId", CaseListRequest::getCaseId, Object::toString),
  extract("priority", CaseListRequest::getPriority, (v) -> Integer.parseInt(v.toString()))
);

final CaseListRequest request = requests.forward(payload);
```

A target component that no row names gets a type default rather than `null`. The table is in `NullDefaults`, so a
`String` comes back `""`, numbers come back zero, `boolean` comes back `false`, `List`, `Set`, and `Map` come back as
empty singletons, and `Optional` comes back as `Optional.empty()`. The defaults differ from those of `mapperForward`,
which leaves an unpaired container `null`, so do not assume the two are interchangeable. A component a row does name is
passed to its converter as written, including when the key is missing, so a converter that cannot accept `null` needs to
handle it.

The backward direction is deliberately absent. A flat `String`-keyed map is a boundary format rather than a typed
counterpart, so round-tripping to it would have to invent a key-encoding policy, and telescope does not pick one for
you. Annotating the target with `@FromMap` generates a binder at compile time that uses no reflection, described in
[docs/codegen.md](docs/codegen.md), and it keys strictly by field name with no per-row converter, so its defaults follow
the JLS rather than the `NullDefaults` table.

MapStruct maps from a map too. Its processor reads the keys as properties, and a method declared as
`Target fromMap(Map<String, Object> src)` generates the same shape of binder, once you also declare the conversion
methods it asks for. The difference is where a per-key conversion goes, since telescope takes it inline as the third
argument to `extract(...)` and MapStruct takes it as a separate mapping method on the interface.

### Beans

POJOs do not need a mirror record. You navigate the bean directly with `ofBean`, and `set` and `update` build a new root
and rebuild the modified path. The update is persistent-style rather than a deep clone, so the original is never
mutated, and untouched mutable subtrees are shared between the old and new roots ([details](docs/pojos.md)).

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
  .update(user, String::toUpperCase); // a new User, and `user` is untouched
```

If you would rather stay in records, convert a POJO with `Telescope.map(Pojo.class, Record.class, ...)` and navigate the
record instead, which [docs/pojos.md](docs/pojos.md) covers.

The library ends there. No `Iso`, `Lens`, `Prism`, `Affine`, `Traversal`, `Getter`, `Setter`, or `Fold` appears in
user-facing code, because the optics live inside, behind one type.

---

## Choosing an entry point

The tour used three entry points, and the table below is the whole map. Two questions decide which one you want. First,
are you working with records or POJOs. Second, do you want to navigate one type in place or convert between two types.

| You want to                        | Records                                       | POJOs                                | POJO and record together                        |
| ---------------------------------- | --------------------------------------------- | ------------------------------------ | ----------------------------------------------- |
| **Navigate and update** in place   | `Telescope.of(R.class)`                       | `Telescope.ofBean(P.class)`          | convert first (below), then navigate the record |
| **Convert or map** between types   | `Telescope.map(A.class, B.class, to(...), …)` | `Telescope.map(A.class, B.class, …)` | `Telescope.map(P.class, R.class, …)`            |
| **Read an untyped map**            | `Telescope.fromMap(R.class, extract(...))`    | `Telescope.fromMap(P.class, …)`      | `@FromMap` for a generated binder               |
| **Bind at compile time** (codegen) | `@Focus` to navigate                          | `@BeanFocus` to navigate             | `@Bridge` to convert any pair                   |

Conversions are bidirectional, so any cell in the second row composes into a longer navigation path with `.then(...)`.
Mismatched names get an explicit `Mapping.to(srcAccessor, tgtAccessor)` row in the `Telescope.map(...)` call, and
classes the auto-detection cannot handle get a `WriteHint.writeBean(target, strategy)` row. Both are covered in
[docs/pojos.md](docs/pojos.md).

The vocabulary is used consistently from here on. **Navigation** is `of`, `ofBean`, `.field`, and `.each`, which build a
typed path into one structure. **Automatic structural mapping** is `Telescope.map` and `mapper`, which match same-name
fields by exact name and type, recursively, and never fuzzily. **Explicit conversion** is `from/to/using`, where you
write both directions and nothing is automatic. **Generated structural mapping** is `@Bridge`.

For a sealed root, `Match.of(...)` dispatches over the permitted subtypes, and `.exhaustive()` reads the permits and
throws when a subtype has no handler. The check happens when you call `.exhaustive()`, not at compile time.

---

## Examples

When a screenful is not enough, the runnable modules below cover the surface, so pick the one matching what you are
evaluating.

| Module                                                                         | Stack                         | Pick when                                                                                                   |
| ------------------------------------------------------------------------------ | ----------------------------- | ----------------------------------------------------------------------------------------------------------- |
| [`examples/library/`](examples/library/)                                       | plain Java, no framework      | You want to see what the DSL does on its own, in a set of small capability demos, each with its own `main`  |
| [`examples/springboot/order-jpa/`](examples/springboot/order-jpa/)             | Spring Boot + JPA + Hibernate | You want everything at once, across several controllers over one realistic `Order` domain on a single stack |
| [`examples/springboot/product-starter/`](examples/springboot/product-starter/) | Spring Boot autoconfig        | You want registry discovery with no wiring, where you declare `@Bean Mapper<A, B>` and the starter finds it |
| [`examples/springboot/org-chart/`](examples/springboot/org-chart/)             | Spring Boot + JPA cycles      | You have a domain that refers to itself, such as an org chart or a thread, and want cycle-safe mapping      |
| [`examples/springboot/invoicing/`](examples/springboot/invoicing/)             | `@Bridge` codegen             | You want conversion bound at compile time on a hot path                                                     |

Start with [`order-jpa/`](examples/springboot/order-jpa/) for the broadest view, or with
[`examples/library/`](examples/library/) to see telescope without a framework around it. The per-module guide is
[`examples/springboot/README.md`](examples/springboot/README.md).

---

## How it compares to MapStruct

MapStruct is a compile-time bean mapper with a mature ecosystem and broad adoption, and it converts whole objects
including nested graphs, using dotted paths, automatic sub-mapping methods, collections, builders, multi-source methods,
and update mappings. Nothing below disputes any of that, because the comparison is about abstraction rather than
quality.

Telescope overlaps MapStruct on mapping, and it adds reusable typed paths. MapStruct has no way to point at
`company.departments[].address.city` as a value you can hold, read, immutably update, or lift through an effect. Where
the two overlap, the architectural difference is how fields are named. Telescope uses method references, which are Java
symbols, checked by `javac`, and moved by any IDE's standard rename. MapStruct uses annotation strings, which its
processor validates at compile time and which the
[MapStruct IDEA plugin](https://mapstruct.org/documentation/ide-support/) refactors according to its documentation. The
head-to-head module tests the `javac` behavior of both failure modes, and the IDE-plugin behavior is cited rather than
tested here. Dotted nested paths remain strings either way. The comparisons below pin MapStruct 1.6.3, which is the
version the head-to-head module and the benchmarks build against.

The cost of a stale string is worth stating precisely, because the two failure modes differ.

- **Renaming a source property named in an explicit `@Mapping(source = ...)`** fails the MapStruct build with an error.
  The string is not unsafe, it is hard to refactor without the IDE plugin, and it is fixed by hand across mappers when
  the plugin is absent.
- **Renaming or adding a target property with no source counterpart** succeeds with a warning under the default
  `unmappedTargetPolicy = WARN`, and the field is `null` at runtime. `ReportingPolicy.ERROR` is a one-line opt-in that
  turns it into a build failure, and serious MapStruct setups enable it. Telescope's strict `mapper(...)` refuses
  unmapped fields at construction by default, so the difference is the default rather than the ceiling.

> **Runnable head-to-head.** The same `Order` to `OrderDto` mapping is written both ways in one module,
> [`examples/mapstruct-vs-telescope`](examples/mapstruct-vs-telescope/).
>
> ```bash
> ./gradlew :examples:mapstruct-vs-telescope:test
> ```
>
> Every claim is a passing test, covering both rename failure modes above, each labelled, the default-policy
> unmapped-target case, and a deep immutable update, which sits outside MapStruct's mapping abstraction because its
> `@MappingTarget` updates mutate an existing instance in place.
>
> For the paper trail, the [coverage matrix](docs/mapstruct-parity.md) scores all 29 MapStruct features against
> telescope with `file:line` evidence per verdict, at 13 full and 16 partial, and the
> [migration guide](docs/mapstruct-migration.md) turns the matrix into a recipe you apply one mapper at a time.

#### Measured performance

In the included JMH workloads, using MapStruct 1.6.3 on CI hardware with JDK 25, telescope codegen and MapStruct codegen
land in the same performance range. Figures from a single run are comparable with each other, and the ranges are what
the same benchmark has produced across runs, with [methodology and history](docs/perf-mapstruct-comparison.md) recorded
separately.

| Tier, codegen against codegen | telescope against MapStruct                                            |
| ----------------------------- | ---------------------------------------------------------------------- |
| flat, 5 scalars               | about 1.07 times, which is a fifth of a nanosecond                     |
| nested, one nested type       | 1.04 to 1.46 times across runs, on a microbenchmark, not a service     |
| deep, 3 levels and list hops  | 1.06 to 1.18 times across runs, or 4 ns on a 62 ns conversion, at best |
| Set or Map field, 100 items   | the same allocation, and timings are pending a re-run                  |

Without codegen, `Telescope.mapper(...)` composes each record or bean pair into a single `MethodHandle`, with no
annotations and no build step, and it comes within about 1.04 to 3.3 times of MapStruct in the same workloads. Flat is
about 3.3 times, nested about 2.7, deep about 1.3, and container fields about 1.04 to 1.06. The ratio narrows as the
work per call grows, because a fixed cost of about 7 ns per call is nearly the whole gap on flat and nested shapes,
while the deep and container shapes add a further cost per converted element on top, so the absolute gap grows while the
ratio falls. Flat, nested, and deep conversions are well under a microsecond on both paths, and the 100-item container
rows are above it on both, so read the tier that matches your shape rather than the summary. Whether the runtime path is
fast enough is your call, and `@Bridge` puts you back in the codegen range when a loop turns hot. You can reproduce any
of it from the [`Benchmarks`](.github/workflows/benchmarks.yaml) GitHub Action, and the full matrix is in
[`benchmarks/README.md`](benchmarks/README.md#mapstruct-comparison-apples-to-apples).

#### Native-image

Codegen was never the question here, because MapStruct's generated mappers and telescope's are both free of reflection
and both build under GraalVM native-image with no configuration. The difference is the runtime path, which MapStruct
does not have and telescope's keeps working inside a native image, so `Telescope.mapper(...)` and `.field(User::name)`
run with no build step. Inside an image the substrate swaps its `LambdaMetafactory` accessors, which define classes at
runtime and which the closed world of native-image forbids, for plain `MethodHandle` closures, and one
`static final boolean` picks the branch. `telescope-core` carries its own native-image metadata, and you register your
own DTO types the way you would in any GraalVM application.

Two gates cover it. The `:core:imageTest` and `:internal:imageTest` tasks re-run each module's whole suite with the
`imagecode` property set, so every existing assertion runs on the substrate an image uses, and both are wired into
`check`, which is what CI runs. Separately, a verifier covering nine capabilities compiles and runs as a real native
binary in CI on every substrate push and weekly. Setup and limits are in [`docs/native-image.md`](docs/native-image.md).

#### Capability comparison

The rows below are differences in architecture rather than things MapStruct cannot do, and most of them trace to one
root. A mapping held as a runtime value can be composed, reversed, lifted through effects, and asked about after the
fact, while a mapping compiled into a generated class is complete at build time, by design.

| Capability                        | telescope                                                          | MapStruct                                                            |
| --------------------------------- | ------------------------------------------------------------------ | -------------------------------------------------------------------- |
| Bidirectional mapping             | one row list, with `forward(...)` and `backward(...)` on one value | a second method plus `@InheritInverseConfiguration`, with exclusions |
| Deep nested navigation and update | `of(C).each(C::depts).field(D::address).update(c, fn)`             | not in scope, and `@MappingTarget` mutates in place                  |
| Effectful update                  | `updateAsync`, `updateOptional`, `updateEither`, `updateValidated` | not in scope, so pair it with external machinery                     |
| Accumulating validation           | `Validated.combine(...)` collects every failure in one pass        | not in scope, so pair it with Bean Validation or write it            |
| Reading an untyped map            | `Telescope.fromMap(T.class, extract(...))` and `@FromMap`          | supported, with the per-key conversion as a separate mapping method  |
| Mapper introspection              | `explain()`, `trace(input)`, or a log level                        | read the generated source, which is genuinely debuggable             |
| Unmapped-target safety            | strict at construction by default                                  | `WARN` by default, with `ERROR` a one-line opt-in                    |
| Sealed-root dispatch              | `Match.of(...).when(...).exhaustive()`, checked over the permits   | `@SubclassMapping`, broader hierarchies, no sealed check             |
| Multi-source merge, many to one   | `Telescope.merge(Target.class, from(...), ...)`                    | first-class multi-source methods, disambiguated by string            |
| Runtime path, no codegen required | `Telescope.of(Class)`, with `@Focus` as a later opt-in             | compile-time only                                                    |
| GraalVM native-image              | codegen needs no config, and the runtime path survives AOT too     | fully AOT-compatible for codegen, with no runtime path to need it    |

The full accounting is the [coverage matrix](docs/mapstruct-parity.md), which scores all 29 MapStruct features with the
telescope idiom, its limitation, and `file:line` evidence.

#### When MapStruct is the right pick

- You need mapping bodies written in an embedded expression language, such as `@Mapping(expression = "java(...)")` or
  qualifier dispatch, inline in the annotation rather than as plain Java mappers passed to `Mapping.via(...)`
- You need `@SubclassMapping` fan-out across hierarchies that are open rather than sealed, since telescope's `Match`
  covers sealed roots and the [coverage matrix](docs/mapstruct-parity.md) scores the gap
- Conversion is the whole job, with no path reuse, deep updates, effects, or bidirectional values, and generated mapper
  source that your team can read is a feature rather than a cost

#### When telescope is the right pick

- Your problem includes deep navigation alongside mapping, where every extra level is one more hop on a value you
  already hold rather than another block of rebuild code
- You want both directions from one definition, using `forward(...)` and `backward(...)` on one value, with no second
  method to keep in sync
- You need to lift a mapping or a field update through an effect, using `updateValidated`, `updateAsync`,
  `updateEither`, or `updateOptional`
- You have mappers that read from several sources at once, where `Telescope.merge(...)` returns a `Mapper<Sources, T>`
  declared once
- You have a sealed root and want every permitted subtype accounted for, checked against the permits when the matcher is
  built
- You are navigating a mix of records and POJOs at any depth without creating intermediate DTOs
- You read untyped `Map<String, Object>` payloads at a boundary and want them bound to typed objects by the same library
- You are deploying to GraalVM native-image and want mapping with no build step
- You want one abstraction for reading, updating, mapping, and validation, which is the path

To try it, write your next mapper as one `Telescope.mapper(...)` call and leave every existing MapStruct mapper alone.
The [migration guide](docs/mapstruct-migration.md) covers running both side by side whenever you want to do more.

---

## Published artifacts

Everything is published to Maven Central under `io.github.eschizoid`, and the family has six artifacts.

| Artifact                        | Role                                                                                                                                                                                                                                                              |
| ------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `telescope-core`                | The DSL, meaning `Telescope`, `Mapper`, `Mapping`, `Either`, `Validated`, and the annotations. Add this one for the runtime path.                                                                                                                                 |
| `telescope-internal`            | The optic lattice and reflection helpers. Transitive only, so it arrives automatically. A consumer that is itself a JPMS module cannot compile against it, because the exports are qualified to `:core`; a classpath consumer can reach it and should not.        |
| `telescope-codegen`             | The optional annotation processor for `@Focus`, `@BeanFocus`, `@Bridge`, and `@FromMap`, described in [docs/codegen.md](docs/codegen.md). It also registers the mapper verifier, which runs on every compilation and is turned off with `-Atelescope.verify=off`. |
| `telescope-lombok`              | A Lombok-aware variant of the processor, for `@Data`, `@Value`, and `@Builder` POJOs.                                                                                                                                                                             |
| `telescope-spring-boot-starter` | Spring Boot autoconfiguration plus a `Mapper<A, B>` bean registry. Compiled and CI-tested against Spring Boot 4.1.1.                                                                                                                                              |
| `telescope-quarkus`             | A Quarkus CDI extension with the same registry shape. Compiled and CI-tested against Quarkus 3.39.4.                                                                                                                                                              |

Installation snippets, annotation-processor ordering with Lombok, and JPMS setup are in
[docs/codegen.md](docs/codegen.md).

---

## Documentation

| Doc                                                                    | What is in it                                                                                       |
| ---------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| [docs/navigation.md](docs/navigation.md)                               | The whole DSL surface, a cookbook for containers, sealed cases, filters, and multi-edit, plus nulls |
| [docs/type-conversion.md](docs/type-conversion.md)                     | Explicit conversions with `from/to/using`, and automatic structural mapping with `Telescope.map`    |
| [docs/pojos.md](docs/pojos.md)                                         | Bean navigation, write strategies, aliasing, and the workflow between POJOs and records             |
| [docs/effects.md](docs/effects.md)                                     | The four effects, exception semantics, and what the async executor does and does not bound          |
| [docs/introspection.md](docs/introspection.md)                         | `explain()`, `trace()`, and auto-logging, plus exactly what the report contains                     |
| [docs/codegen.md](docs/codegen.md)                                     | `@Focus`, `@BeanFocus`, `@Bridge`, `@FromMap`, Lombok ordering, and JPMS setup                      |
| [docs/native-image.md](docs/native-image.md)                           | The GraalVM contract, meaning what telescope ships and what your application registers              |
| [docs/mapstruct-parity.md](docs/mapstruct-parity.md)                   | The 29-feature coverage matrix, with evidence per row                                               |
| [docs/mapstruct-migration.md](docs/mapstruct-migration.md)             | Migration one mapper at a time, a coexistence setup, and a translation table                        |
| [docs/perf-mapstruct-comparison.md](docs/perf-mapstruct-comparison.md) | Benchmark methodology, the environment, and the full result set                                     |

---

## Constraints

- **Records and JavaBeans-style POJOs.** Records rebuild through the canonical constructor, and POJOs rebuild through an
  auto-detected write strategy, tried as builder, then setters, then fields, then constructor, and overridable per class
  with `WriteHint.writeBean(...)`.
- **Method references, not lambdas.** `.field(User::name)` works, and `.field(u -> u.name())` is rejected when the path
  is built, with an error saying so. Field names are recovered from the reference, and a lambda has none.
- **Accessor types are checked at compile time, and discovery happens at runtime.** `javac` verifies the source and
  focus types of every method reference. Path construction then runs eager checks, covering lambda rejection, bean
  write-strategy resolution, and mapper row validation at factory time. The late-bound entry points are
  `.fieldByName(String)`, its `Class<B>` overload, and the no-argument `each()`, all named to say so and all resolving
  at first use.
- **Structural mapping is exact.** Same-name matching is exact on name and type, recursively, with no fuzzy matching and
  no implicit conversion between `String` and numbers. What MapStruct generates silently, you write as a row.
- **Null semantics are uniform.** Null containers and null `Optional` fields focus nothing, null intermediate hops
  propagate on reads, and `forward(null)` returns `null`. The full table is in [docs/navigation.md](docs/navigation.md).
- **The runtime and codegen paths are checked against each other.** They are separate implementations, so a test
  enumerates the JDK's own `List`, `Set`, and `Map` types alongside adopter-shaped ones, and compares which of them each
  path accepts and refuses. A new disagreement fails the build. Known ones are recorded in the test with their
  direction, so the gate tells you which types compile under `@Bridge` and throw under `mapper(...)`, rather than
  claiming none do.
- **Not a general transformation language.** One path focuses one type, and heterogeneous bulk edits go through
  `Telescope.all` with one edit per path.

---

## Architecture

There are two layers. The public layer is one type, `Telescope<S, A>`, plus `Mapper`, `Mapping`, the two effect types,
and the annotations. The internal layer is an optic lattice, made of `Iso`, `Lens`, `Prism`, `Affine`, and `Traversal`,
which are the same shapes as Haskell's lens and Scala's Monocle, and the public layer composes them. JPMS qualified
exports keep the lattice invisible to consumers. Runtime accessor dispatch uses lambdas built by `LambdaMetafactory`, or
plain `MethodHandle` closures inside a native image, and discovery is reflective and cached per class. The rest,
including why the lattice is hidden and what the codegen emits, is in the ADRs under [`docs/adr/`](docs/adr/).

---

## Build and test

```bash
./gradlew build          # everything: core, internal, codegen, lombok, the starters, and the examples
./gradlew :core:test     # the DSL surface
./gradlew check          # adds the formatting gate and the AOT-substrate suite runs
./gradlew :benchmarks:jmh -Pjmh.includes=MapStructComparisonBenchmark   # the head-to-head numbers
```

Java 21 or later is enough to consume telescope, and the build itself uses a newer toolchain. CI builds every module on
Temurin JDK 25.

---

## License

Apache 2.0. See [LICENSE](LICENSE).
