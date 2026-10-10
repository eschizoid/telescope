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

Telescope is built around a reusable typed path. A path is a value, built from method references and checked by `javac`,
that points at data anywhere inside a nested structure.

```java
static final Telescope<Company, String> EMAILS = Telescope.of(Company.class)
  .each(Company::departments)
  .each(Department::teams)
  .each(Team::users)
  .field(User::email);
```

With a path in hand, you can read through it and collect or count every value it reaches. You can also rebuild the whole
tree through it without mutating anything, as in `EMAILS.update(company, String::toLowerCase)`. Mapping between two
types uses the same idea, with each row pairing a source path to a target path.

```java
Mapper<Order, OrderDto> mapper = Telescope.mapper(
  Order.class,
  OrderDto.class,
  to(Order::customerName, OrderDto::fullName)
);

OrderDto dto = mapper.forward(order); // same-name fields map automatically, and nesting recurses
mapper.explain();                     // the report is built from the mapper you are holding
```

The mapper is a value. You can hold it, reuse it across call sites, and ask it what it maps while the program runs. You
can also compose it into a longer path with `mapper.asTelescope().then(...)`. [MapStruct](https://mapstruct.org/) turns
a mapping into a generated class at compile time instead. The sections below explain where telescope differs for someone
who uses MapStruct today.

<p align="center">
  <img src="img/head-to-head.gif" alt="The telescope and MapStruct head-to-head test printing its own output: identical results, the default-policy unmapped-target case, a deep immutable update, and a mapper reporting what it maps." width="820" />
  <br />
  <sub>The <a href="examples/mapstruct-vs-telescope/"><code>mapstruct-vs-telescope</code></a> test printing its own
  output, trimmed for width.</sub>
</p>

## Field names are checked by the compiler

Every field a telescope mapping row names is a method reference. `javac` checks it, and your IDE's ordinary rename moves
it. MapStruct names fields with strings inside `@Mapping` annotations. Its processor validates those strings at compile
time. The [MapStruct IDEA plugin](https://mapstruct.org/documentation/ide-support/) refactors them according to its
documentation, but a plain rename in the editor doesn't touch them.

```java
// MapStruct
@Mapping(source = "customerName", target = "fullName")
OrderDto toDto(Order order);

// telescope
Telescope.mapper(Order.class, OrderDto.class, to(Order::customerName, OrderDto::fullName));
```

The two tools fail differently when a name goes stale. Renaming a source property that an explicit
`@Mapping(source = ...)` names fails the MapStruct build with an error. Without the plugin, you then fix the string by
hand in every mapper. Renaming or adding a target property with no source counterpart is different. Under MapStruct's
default `unmappedTargetPolicy = WARN` it only produces a warning, and the field is `null` at run time.
`ReportingPolicy.ERROR` turns that warning into a build failure with one line. `Telescope.mapper(...)` refuses unmapped
fields when the mapper is built, so telescope differs here only in its default.

Nested targets stay typed too. MapStruct writes `@Mapping(source = "flat", target = "a.b.c")` with a dotted string.
Telescope accepts a navigator as the target of a row. The `@Focus` annotation processor generates such a navigator for a
record, and each of its steps is a method call that `javac` checks.

```java
Telescope.mapper(Cart.class, CartDto.class,
  to(Cart::customerName, CartDtoTelescope.of().shipping().recipient().fullName()));
```

`@Bridge` is an annotation that has the `telescope-codegen` processor generate a mapper at compile time, and it does
name fields with strings. Its entries, e.g. `@Rename` and `@Compute`, take field names as strings. The processor reports
a name that isn't a field of the class as a compile error. `@FromMap(required = ...)` also takes strings, which name map
keys.

The [head-to-head module](examples/mapstruct-vs-telescope/) tests MapStruct's default-policy unmapped-target case. A
rename failure can't be a passing test, because the rename has to be applied by hand to show it, so the module describes
the rename as a manual step.

## Runtime and generated mappers are tested to give the same result

Telescope can run the same mapping in two ways. A runtime mapper, built by `Telescope.mapper(...)`, does the conversion
while the program runs, with no annotations and no build step. Putting `@Bridge(Target.class)` on the source type makes
the `telescope-codegen` annotation processor generate the same conversion as plain Java, which is a generated mapper.
You can start with a runtime mapper and move a mapping to `@Bridge` later, when that code needs the speed.

```java
@Bridge(InvoiceDto.class)
public record Invoice(Long id, String customer, long totalCents) {}

public record InvoiceDto(Long id, String customer, long totalCents) {}

// at run time, with or without the annotation
InvoiceDto viaMapper = Telescope.mapper(Invoice.class, InvoiceDto.class).forward(invoice);

// generated at compile time
InvoiceDto viaBridge = InvoiceBridge.BRIDGE_FN.forward(invoice);
```

Two tests run the same input through both kinds of mapper and fail when they disagree. `CrossPathCorpusTest` covers
container shapes, how a target is constructed, same-typed container copies, object graphs that lead back to an object
being converted, `patch`, and enum pairs. `BeanRebuildCorpusTest` covers the ways a bean target can be built. Both run
as part of `./gradlew check`, which is what CI runs. Renames, null strategies, and defaults aren't cross-checked by
them.

The tests record each known difference between the two paths, and they fail when a recorded difference goes away, so the
list stays accurate. The recorded differences are:

- A target reachable only through a private constructor is built at run time. The processor refuses it when `@Bridge`'s
  `writeStrategy` asks for setters or for a constructor.
- A container class whose no-argument constructor is package-private or protected is built at run time. The processor
  refuses it when the bridge is generated into a different package from the class, because generated code there can't
  call that constructor.
- A bean built through a constructor whose parameters are named after its properties converts on the generated path. The
  runtime mapper refuses it unless the bean was compiled with `-parameters`, because it matches the arguments by
  parameter name.
- A bean whose builder has no method for a `final` field with no initializer is built at run time, and the value is
  lost. The processor refuses it.
- `patch` on two sealed roots returns a new object from a generated bridge, which dispatches on the case. The runtime
  mapper refuses it with an `UnsupportedOperationException` that names the root.
- A nested pair can carry its own `@Bridge` with a `@Transform` or with `lenient = true`. An enum pair below it compiles
  on the generated path. The runtime mapper refuses it unless you give it a row for the nested pair.

## Unconvertible fields are refused

`Telescope.mapper(...)` and a `@Bridge` refuse a field they can't map or convert. The refusal comes when the mapper is
built or when the code compiles. The message names the field, the types involved, and the fix. A record `Tgt` with a
field `b` that `Src` doesn't have gets this message from `Telescope.mapper(Src.class, Tgt.class)`.

```text
Deep map Src → Tgt: target field 'b' has no same-name source field. Add a rename row to(sourceAccessor, targetAccessor) that maps to 'b'.
```

You can see the refusal before run time. With `telescope-codegen` on the annotation processor path, a mapper verifier
reports the same message as a compile error. It checks each `Telescope.mapper(...)` call whose types it can read.
`-Atelescope.verify=warn` turns it into a warning, and `=off` turns it off.

Some entry points are lenient on purpose. `mapperForward(...)` and `@Bridge(lenient = true)` leave an unmatched target
field at its default value, which is `null`, zero, or `false`. `Telescope.fromMap(...)` fills a component with no value
from a default for its type. `Mapper.into(...)` writes onto an existing bean and skips a property with no public setter.

Same-typed JDK collections are copied rather than shared, so changing the target's list never changes the source's.
[Mapping](#mapping) has the details.

## Measured performance

On the latest run, each call shape of the generated mapper ran close to MapStruct's time on every tier.

- Forward, from the source bean to the target record, the `BRIDGE_FN` constant that the `@Bridge` sample above calls
  took 1.00 to 1.01 times MapStruct's time on flat, 1.02 on nested, and 0.99 on deep.
- Forward, the composable `BRIDGE.read` value took 1.08 times MapStruct's time on flat, 1.06 on nested, 0.99 to 1.00 on
  deep, 1.05 on the Set field, and 0.99 to 1.11 on the Map field.
- Backward, from the record to the bean, `BRIDGE.set` took 1.05 times MapStruct's time on flat and 1.09 on the Map
  field. It took less time than MapStruct on nested, deep, and the Set field, at 0.87, 0.96, and 0.97.
- Telescope's generated mappers allocated the same bytes per call as MapStruct on every row.

<!-- metrics: from MapStructComparisonBenchmark, Actions run 38008688244 -->

| Tier, generated mapper against MapStruct | forward, `BRIDGE_FN` | forward, `BRIDGE.read` | backward, `BRIDGE.set` | bytes per call, both sides |
| ---------------------------------------- | -------------------- | ---------------------- | ---------------------- | -------------------------- |
| flat, 5 scalars                          | 1.00 to 1.01 times   | 1.08 times             | 1.05 times             | 32                         |
| nested, one nested type                  | 1.02 times           | 1.06 times             | 0.87 times             | 48                         |
| deep, 3 levels and list hops             | 0.99 times           | 0.99 to 1.00 times     | 0.96 times             | 376                        |
| Map field, 100 entries                   | not measured         | 0.99 to 1.11 times     | 1.09 times, see below  | 7,528                      |
| Set field, 100 entries                   | not measured         | 1.05 times, see below  | 0.97 times             | 7,576 forward, 7,544 back  |

The table comes from GitHub Actions run 38008688244 on `main` at `35caef83`, using the included JMH workloads with
MapStruct 1.6.3 on JDK 25. The run used 4 forks of 8 measured iterations each. MapStruct's own rows are the control, so
each ratio is read within this one run. Where the error bands of the two rows overlap, the table gives a range instead
of one ratio. The [methodology, per-fork figures, and earlier runs](docs/perf-mapstruct-comparison.md) are recorded
separately.

The container rows split across forks. On Map forward, three of telescope's four forks ran at 1.00 times MapStruct and
one at 1.21. On Map backward, two forks ran at 0.99 and two at about 1.2. On Set forward, two forks ran at 1.00 and two
at 1.10. MapStruct's own container forks agreed with each other on this run.

Runtime mappers are slower than generated ones, and the gap shrinks as the work per call grows. Without codegen,
`Telescope.mapper(...)` composes each record or bean pair into a single `MethodHandle`. On the same run it measured 3.36
times MapStruct forward on flat, 2.69 on nested, and 1.28 on deep. On the 100-entry fields it measured 1.11 times on Set
and 1.15 times on Map, and both rows split across forks. It allocated the same bytes as MapStruct on every tier except
the Map field, where three of its four forks allocated 32 bytes more. A fixed cost of about 7 ns per call is nearly the
whole gap on flat and nested shapes. Deep and container shapes add a cost per converted element, so their absolute gap
grows while the ratio falls.

Flat, nested, and deep conversions take well under a microsecond with both kinds of mapper. The 100-entry container rows
take more than one with both. Read the tier that matches your shape. You can reproduce any of it from the
[`Benchmarks`](.github/workflows/benchmarks.yaml) GitHub Action. The full matrix is in
[`benchmarks/README.md`](benchmarks/README.md#mapstruct-comparison).

---

## How it compares to MapStruct

MapStruct is a compile-time bean mapper with a mature ecosystem and broad adoption. It converts whole objects, including
nested graphs. It has dotted paths, automatic sub-mapping methods, collections, and builders, for example. Every
comparison here pins MapStruct 1.6.3, which is the version the head-to-head module and the benchmarks build against.

Telescope overlaps MapStruct on mapping and adds reusable typed paths. MapStruct has no way to point at
`company.departments[].address.city` as a value you can hold and read. Telescope can also update through such a value
without mutation, or run the update inside an async or validation result.

The runnable head-to-head module writes the same `Order` to `OrderDto` mapping both ways, in
[`examples/mapstruct-vs-telescope`](examples/mapstruct-vs-telescope/).

```bash
./gradlew :examples:mapstruct-vs-telescope:test
```

Its tests show both libraries producing the same `OrderDto`. They also cover MapStruct's default-policy unmapped-target
case and a deep immutable update. MapStruct's `@MappingTarget` updates mutate an existing instance in place, so a deep
immutable update sits outside its mapping model.

### Capability comparison

The rows below are differences in design rather than things MapStruct can't do. A mapping held as a value can be
composed, reversed, and asked about while the program runs. A mapping compiled into a generated class is complete at
build time, by design.

| Capability                        | telescope                                                          | MapStruct                                                            |
| --------------------------------- | ------------------------------------------------------------------ | -------------------------------------------------------------------- |
| Bidirectional mapping             | one row list, with `forward(...)` and `backward(...)` on one value | a second method plus `@InheritInverseConfiguration`, with exclusions |
| Deep nested navigation and update | `of(C).each(C::depts).field(D::address).update(c, fn)`             | not in scope, and `@MappingTarget` mutates in place                  |
| Update inside a result type       | `updateAsync`, `updateOptional`, `updateEither`, `updateValidated` | not in scope, so pair it with other code                             |
| Accumulating validation           | `Validated.combine(...)` collects every failure in one pass        | not in scope, so pair it with Bean Validation or write it            |
| Reading an untyped map            | `Telescope.fromMap(T.class, extract(...))` and `@FromMap`          | supported, with the per-key conversion as a separate mapping method  |
| Mapper introspection              | `explain()`, `trace(input)`, or a log level                        | read the generated source, which you can step through                |
| Unmapped-target safety            | strict at construction by default                                  | `WARN` by default, with `ERROR` a one-line opt-in                    |
| Sealed-root dispatch              | `Match.of(...).when(...).exhaustive()`, checked over the permits   | `@SubclassMapping`, broader hierarchies, no sealed check             |
| Multi-source merge, many to one   | `Telescope.merge(Target.class, from(...), ...)`                    | first-class multi-source methods, disambiguated by string            |
| No codegen required               | `Telescope.of(Class)`, with `@Focus` as a later opt-in             | compile-time only                                                    |
| GraalVM native-image              | `@Bridge` code needs no config, and runtime mappers work there too | fully AOT-compatible for codegen, with no runtime mapper to need it  |

The [coverage matrix](docs/mapstruct-parity.md) scores 29 MapStruct features against telescope. It rates 13 as covered
fully and 16 as covered partially. Each partial row states its limitation, and every verdict cites the source and tests
behind it. The [migration guide](docs/mapstruct-migration.md) turns the matrix into steps you apply one mapper at a
time.

### When MapStruct is the right pick

- You need mapping bodies written in an embedded expression language, such as `@Mapping(expression = "java(...)")` or
  qualifier dispatch. Telescope takes plain Java mappers passed to `Mapping.via(...)` instead.
- You need `@SubclassMapping` across hierarchies that are open rather than sealed. Telescope's `Match` covers sealed
  roots, and the [coverage matrix](docs/mapstruct-parity.md) scores the gap.
- Conversion is the whole job, with no path reuse or deep updates. Your team also treats readable generated mapper
  source as a feature.

### When telescope is the right pick

- Your problem includes deep navigation and updates alongside mapping, on records, POJOs, or a mix.
- You want field names checked by `javac`, and unmapped fields refused by default.
- You need to run an update inside an async or validation result, merge several sources, or handle every subtype of a
  sealed root.
- You read untyped `Map<String, Object>` payloads, or you deploy to GraalVM native-image and want mapping with no build
  step.

To try it, write your next mapper as one `Telescope.mapper(...)` call and leave every existing MapStruct mapper alone.
The [migration guide](docs/mapstruct-migration.md) covers running both side by side.

---

## Install

```kotlin
// Gradle (Kotlin DSL)
dependencies {
  implementation("io.github.eschizoid:telescope-core:2.0.0")
}
```

```xml
<!-- Maven -->
<dependency>
  <groupId>io.github.eschizoid</groupId>
  <artifactId>telescope-core</artifactId>
  <version>2.0.0</version>
</dependency>
```

The snippet above is the runtime. Compile-time codegen, the Spring Boot starter, the Quarkus extension, and JPMS setup
are [listed below](#published-artifacts).

---

## Quick start

You can update a field deep inside nested data without writing copy constructors. The example below is complete, so you
can paste it into a `main` and run it.

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

Everything else in the library starts from a path like `userCity`. Mapping between types and navigating containers both
use one. Running an update inside an async or validation result does too.

The guides go deeper on each part.

- [docs/navigation.md](docs/navigation.md) covers `List<X>`, `Optional<X>`, `Map<K, V>`, and the whole DSL surface.
- [docs/type-conversion.md](docs/type-conversion.md) covers conversion from record to record and from POJO to record.
- [docs/introspection.md](docs/introspection.md) covers `explain()`, `trace()`, and logging through a log level.
- [docs/effects.md](docs/effects.md) covers updates inside async, validated, either, and optional results.

---

## Choosing an entry point

Your choice depends on what you work with and what you want to do. Records and POJOs have separate entry points.
Navigating one type in place is separate from converting between two types.

| You want to                        | Records                                          | POJOs                                   | POJO and record together                        |
| ---------------------------------- | ------------------------------------------------ | --------------------------------------- | ----------------------------------------------- |
| **Navigate and update** in place   | `Telescope.of(R.class)`                          | `Telescope.ofBean(P.class)`             | convert first (below), then navigate the record |
| **Convert or map** between types   | `Telescope.mapper(A.class, B.class, to(...), …)` | `Telescope.mapper(A.class, B.class, …)` | `Telescope.mapper(P.class, R.class, …)`         |
| **Read an untyped map**            | `Telescope.fromMap(R.class, extract(...))`       | `Telescope.fromMap(P.class, …)`         | `@FromMap` for a generated binder               |
| **Bind at compile time** (codegen) | `@Focus` to navigate                             | `@BeanFocus` to navigate                | `@Bridge` to convert any pair                   |

The runtime entry points in the first three rows need no annotations and no build step. The last row lists the
annotations that the `telescope-codegen` processor reads. `@Focus` and `@BeanFocus` generate a navigator whose steps are
method calls. `@Bridge` generates a conversion as plain Java for a known pair. The codegen guide is
[docs/codegen.md](docs/codegen.md).

`Telescope.mapper(...)` returns a `Mapper`, which has `forward`, `backward`, and `patch`. `Telescope.map(...)` takes the
same rows and returns a `Telescope` path instead. A conversion that runs in both directions composes into a longer path
with `.then(...)`.

`patch(base, partial)` returns a copy of `base` with the partial's non-null reference fields, and all of its primitive
fields, written over it. The copy is a new object even when the partial is null or holds nothing but nulls, and only a
null `base` gives `null`. The copy is shallow. A field the partial leaves null holds the same object that `base` holds,
so a change to that nested object or container through the copy also shows in `base`. Some mappers refuse `patch` with
an `UnsupportedOperationException`:

- a mapper whose source is an enum, or a class no write strategy can build, such as a sealed interface
- a mapper from `liftList`, `liftSet`, `liftOptional`, or `liftMapValues`, whose source is a container with no fields
- a mapper from `Telescope.merge`, which only runs forward

Rows handle the cases that same-name matching can't. A field with a different name gets a
`Mapping.to(srcAccessor, tgtAccessor)` row. A class whose write strategy isn't detected gets a
`WriteHint.writeBean(target, strategy)` row. [docs/pojos.md](docs/pojos.md) covers both.

The rest of this README uses these terms.

- A **typed path** is a `Telescope<S, A>` value built from method references, such as `EMAILS` at the top of this page.
- A **runtime mapper** is a mapper built while the program runs, by `Telescope.mapper`, `Telescope.mapperForward`,
  `Telescope.merge`, or `Telescope.fromMap`, with no annotations and no build step.
- A **generated mapper** is the conversion the `telescope-codegen` processor writes at compile time for `@Bridge` or
  `@FromMap`.

For a sealed root, `Match.of(...)` dispatches over the permitted subtypes. `.exhaustive()` reads the permits and throws
when a subtype has no handler. The check happens when you call `.exhaustive()`, not at compile time.

---

## Records, mapping, beans, and untyped maps

The sections below show a deep update on records, a mapping to DTOs, an update on POJOs, and binding a
`Map<String, Object>`.

### Records

The examples in this section and the next use these records.

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

With telescope, the task is one call on the `EMAILS` path from the top of this page.

```java
final Company lowered = EMAILS.update(company, String::toLowerCase);
```

The first version is about 25 lines of manual reconstruction. Every constructor is spelled out, and every untouched
field is passed through by hand. The second version reuses a path, and the same path also reads.

```java
EMAILS.toList(company);   // List<String> of every email
EMAILS.count(company);    // how many there are
```

To log or record metrics at a point in the path, attach an observer after that step.

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

An observer runs synchronously when a terminal operation executes, once for each value reached at that step. Reads
report the current values from the outside in. Updates report rebuilt values from the inside out. Failed input futures,
`Either.Left`, `Optional.empty`, and `Validated.Invalid` skip observation. A later reconstruction failure can't undo
observations that were already emitted.

For example, you can count the users a read visits with an injected
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

`Telescope.all` folds several edits on one structure into a single reusable normalizer, with one `over(...)` row per
path.

```java
final Telescope<Company, Company> normalize = Telescope.all(
  over(EMAILS, String::toLowerCase),
  over(Telescope.of(Company.class).field(Company::name), String::strip)
);

final Company cleaned = normalize.apply(company);
```

The two edits above touch different fields of the same root, so the fold rebuilds that root once instead of twice. Edits
that share a longer prefix save more, because their shared steps are walked once. Paths from a generated navigator fuse
the same way, with each other and with hand-written paths through the same fields.

The fold runs its edits one after another instead when a path doesn't record its steps, as in these cases:

- a path built by `Telescope.lens(...)` or `fieldByName(...)`
- a path that goes through `observe(...)`, a bridge, or a mapper's `asTelescope()`
- a `then(...)` join where either side is one of the paths above

### Mapping

A mapping applies paths across two shapes. Below, the `Company` tree from [Records](#records) is translated to a
partner-facing `CompanyDto` with a few renamed fields.

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

You only name what changes. Same-name fields map automatically, including nested ones. `User::email`, `User::age`, and
all the list and tree wiring need no rows. Two different enum types convert by constant name with no row. A strict
mapper needs the same constant names on both sides, while `mapperForward(...)` and `@Bridge(lenient = true)` only need
every source constant to exist in the target. Any other enum pair is refused when the mapper is built, or at compile
time for `@Bridge`, with a message that names the missing constants.

An object graph can lead back to an object that is still being converted, as when an employee's manager lists the
employee among their reports. Telescope maps such a back-reference to `null`, on the runtime and the generated path
alike, so the conversion ends. An object reached along two separate branches isn't a cycle, and it's converted once for
each branch.

The same row list also runs backward for the rows that can be reversed. In MapStruct, the reverse direction is a second
method on the same interface. `@InheritInverseConfiguration` copies eligible configuration from the forward method, but
its javadoc excludes expressions, constants, and default values. Telescope has the same kind of limit, because
`constant`, `compute`, and one-way rows are forward-only.

Constants and computed values go in the same call. They match MapStruct's `@Mapping(constant = "...")` and
`@Mapping(expression = "java(...)")`.

```java
Telescope.mapper(Order.class, OrderDto.class,
  to(Order::id, OrderDto::id),
  constant(OrderDto::tenant, "production"),   // an eager literal
  compute(OrderDto::createdAt, Instant::now), // fresh on every call
  compute(OrderDto::traceId, UUID::randomUUID),
  compute(OrderDto::metadata, HashMap::new)); // a fresh container on every call
```

`constant` captures its value once when the row is built. `compute` calls the supplier on each forward call. Use
`compute` whenever a literal would share one mutable reference, as `HashMap::new` would. On the backward direction,
neither row writes the source field. A reference field comes back `null`, and a primitive comes back as zero or `false`.

Every mapper built this way can report what it does, with no generated source to read. `explain()` describes the top
level of the pair.

```java
dtoMapper.explain();
// Mapped:
//   ✓ name                                    → name
//
// Transformations:
//   • departments(java.util.List<Department>) → java.util.List<DepartmentDto>
```

The rendered text is a view of data you can assert on. For a strict bidirectional mapper,
`explain().skipped().isEmpty() && explain().unusedSources().isEmpty()` means every field on both sides is accounted for.
Constant and computed slots are filled rather than skipped, so they don't appear as rows. `trace(input)` reports real
values, and a log level can narrate every conversion. [docs/introspection.md](docs/introspection.md) covers both.

#### Same-typed containers

A field whose declared type is the same on both sides is handed across as the same instance, with some exceptions. A
`List`, `Set`, `Map`, or other JDK collection gets a shallow copy instead. The copy happens forward, backward, and in
`patch` for a container the partial supplies, while a container the partial leaves null is the one `base` holds. Adding
to or removing from the target's container then never changes the source's. Arrays aren't copied, so the target holds
the source's array. A field declared as your own collection class, such as `class Urls extends ArrayList<String>`, is
handed across as it is too.

The copy keeps the source's order, including a sorted set's comparator. It never creates an instance of a class
telescope doesn't know, such as a framework's own collection.

- The common JDK classes, such as `ArrayList`, `HashSet`, `TreeMap`, and `EnumMap`, are copied into their own class.
- A sorted set, sorted map, or priority queue of another class is copied into `TreeSet`, `TreeMap`, or `PriorityQueue`.
- Anything else is copied into the default class for the declared type. `Arrays.asList(...)` and
  `Collections.synchronizedList(...)` become an `ArrayList`, and a `LinkedBlockingQueue` declared as `Queue` becomes an
  `ArrayDeque`.
- The JDK's unmodifiable containers, from the `List.of` family and the `Collections` views, are handed across as they
  are. Other immutable collections, such as Guava's, are copied.

`Telescope.mapper(...)` and a `@Bridge` make the same copy. A collection backed by a lazy JPA collection, such as
Hibernate's `PersistentSet` or `PersistentBag`, is iterated while it's copied. Iterating is expected to load it inside a
session and to throw `LazyInitializationException` outside one. To keep a container shared, give the field a row with
its own functions.

```java
Telescope.mapper(Src.class, Tgt.class, to(Src::items, Tgt::items, x -> x, x -> x));
```

### Beans

POJOs don't need a mirror record. You navigate the bean directly with `ofBean`, and `set` and `update` build a new root.
The update rebuilds the modified path and never mutates the original. Untouched mutable subtrees are shared between the
old and new roots rather than cloned ([details](docs/pojos.md)).

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

If you'd rather work with records, convert a POJO with `Telescope.mapper(Pojo.class, Record.class, ...)` and navigate
the record instead. [docs/pojos.md](docs/pojos.md) covers that workflow.

### Reading an untyped map

`Telescope.fromMap` binds a `Map<String, Object>` to a record or POJO, with one `extract(...)` row per component you
want filled. Data from a JSON body, a JDBC row, or a message header often arrives in that shape.

```java
final ForwardMapper<Map<String, Object>, CaseListRequest> requests = Telescope.fromMap(
  CaseListRequest.class,
  extract("bookingType", CaseListRequest::getBookingType, Object::toString),
  extract("caseId", CaseListRequest::getCaseId, Object::toString),
  extract("priority", CaseListRequest::getPriority, (v) -> Integer.parseInt(v.toString()))
);

final CaseListRequest request = requests.forward(payload);
```

A component with no value takes a default for its declared type. The value is missing when its key is absent, when the
key holds `null`, or when no row names it. The default is `null` for a reference and zero or `false` for a primitive. A
component declared exactly `List`, `Set`, `Map` or `Optional` gets an empty one. The binder generated by `@FromMap`
produces the same values for the same record. A converter is called only for a value that's present. The `fromMap`
defaults differ from those of `mapperForward`, which leaves an unpaired container `null`.

Write `required(...)` in place of `extract(...)` where a key has to carry a value. A map with no value under that key is
refused with an `IllegalArgumentException` before any converter runs. The message names every missing required key
beside the component it was to fill. On a `@FromMap` type, `@FromMap(required = {"id"})` makes the generated binder
refuse the same maps with the same message. `explain()` reports each row as `default when absent` or `required`.

Some rows and components are refused while the mapper is built.

- A row that names a bean property with no setter, builder method, or constructor parameter is refused. The value it
  reads would be dropped.
- A component that no row names is refused when its type has no default of its own. Examples are an array, a concrete
  container like `ArrayList`, a `Collection`, or a class with no generated binder. The message names the component, its
  type, and the fix.
- A row that names such a component converts it, and an absent key leaves it `null`.

A generated `@FromMap` binder has to be registered before `fromMap` uses it. On the class path the processor registers
it for you. On the module path and in a fat jar you have to add a line or merge service files.
[docs/codegen.md](docs/codegen.md#registering-frommap-binders) has the details.

There's no backward direction. A flat `String`-keyed map is a boundary format rather than a typed counterpart. A round
trip would have to invent a key-encoding policy, and telescope doesn't pick one for you. Annotating the target with
`@FromMap` generates a binder at compile time that uses no reflection. It keys strictly by field name with no per-row
converter.

MapStruct maps from a map too. Its processor reads the keys as properties. A method declared as
`Target fromMap(Map<String, Object> src)` generates the same shape of binder, once you declare the conversion methods it
asks for. The difference is where a per-key conversion goes. Telescope takes it inline as the third argument to
`extract(...)`. MapStruct takes it as a separate mapping method on the interface.

---

## Integrations

### Spring Boot starter

`telescope-spring-boot-starter` collects every `Mapper<A, B>` bean into a `TelescopeMapperRegistry`, so you can look a
mapper up by its source and target class. With the starter on the classpath, the `telescope-codegen` annotation
processor also turns an annotated interface into a Spring bean. `@TelescopeMapper` gives you an injectable mapper, and
`@TelescopeTransformer` gives you a reusable clean-up step that a mapper runs on the source before it maps.

In the example below, `CustomerEmailTransformer` cleans an email, and `CustomerProjection` maps a `CustomerEntity` to a
`CustomerRestDto` after running that transformer. The processor writes both implementations, so the service only injects
`CustomerProjection` and calls `map`.

```java
record CustomerRestDto(String email) {}

record CustomerEntity(String email) {}

@TelescopeTransformer
interface CustomerEmailTransformer extends TelescopeTransformation<CustomerEntity, String> {
  default Telescope<CustomerEntity, String> path() {
    return Telescope.of(CustomerEntity.class).field(CustomerEntity::email);
  }

  default Transformation<String> transform() {
    return new Transformation<>("unknown@example.com", (email) -> email.strip().toLowerCase(Locale.ROOT));
  }
}

@TelescopeMapper(transformers = CustomerEmailTransformer.class)
interface CustomerProjection extends TelescopeProjection<CustomerEntity, CustomerRestDto> {}

@Service
class CustomerService {

  private final CustomerProjection projection;

  CustomerService(CustomerProjection projection) {
    this.projection = projection;
  }

  CustomerRestDto register(CustomerEntity customer) {
    return projection.map(customer);
  }
}
```

`map` runs the listed transformers in order, then maps. A null email becomes `"unknown@example.com"`, a non-null one is
trimmed and lowercased, and the entity itself is not changed. A projection is injected directly and is not added to the
registry, because it is not a `Mapper`. For fields with different names, add typed translation rows.

```java
record AccountEntity(String displayName, String phoneNumber) {}

record AccountRestDto(String name, String phone) {}

@TelescopeMapper("accountMapper")
interface AccountMapper extends TelescopeProjection<AccountEntity, AccountRestDto> {
  default void translate(MapperBuilder<AccountEntity, AccountRestDto> mapping) {
    mapping
      .from(AccountEntity::displayName)
      .to(AccountRestDto::name)
      .from(AccountEntity::phoneNumber)
      .to(AccountRestDto::phone);
  }
}
```

The generated Spring bean also has `forward`, `backward`, and `patch`. Transformers run on `map` and `forward` only, and
`backward` and `patch` don't run them. To register transformers from configuration, declare a typed
`TelescopeCustomizer<AccountMapper>` bean, with no bean names involved. The
[Spring starter guide](spring-boot-starter/README.md) covers both forms and their Spring wiring.

### Quarkus

`telescope-quarkus` adds the same registry to a Quarkus application as a CDI bean. Every `Mapper<A, B>` that a
`@Produces` method returns shows up in `TelescopeMapperRegistry` under its source and target class. The generated
`@TelescopeMapper` and `@TelescopeTransformer` beans exist only for Spring. The [Quarkus guide](quarkus/README.md) has
the setup.

### Lombok

`telescope-lombok` is a Lombok-aware variant of the annotation processor. It finds classes with `@Data`, `@Value`, or
`@Builder` on its own, and for each one it generates the same typed navigator that `@Focus` gives a record. It waits
until Lombok has added a class's getters, setters, and builder before it generates the navigator, so its place on the
processor path doesn't matter. The [Lombok guide](lombok/README.md) has the setup.

### Native image

Runtime mappers and typed paths work inside a GraalVM native image, so `Telescope.mapper(...)` and `.field(User::name)`
run there with no build step. You register your own types the way you would in any GraalVM application. A runtime
mapper's types go in `reflect-config.json`, and a class that passes method references such as `User::name` goes in
`serialization-config.json`, because telescope reads the field name from the method reference. Code generated by
`@Bridge` and `@FromMap` needs no configuration. A navigator generated by `@Focus` or `@BeanFocus` builds its paths from
method references, so the navigator class goes in `serialization-config.json` too.

Inside an image, telescope swaps its `LambdaMetafactory` accessors for plain `MethodHandle` closures, because
`LambdaMetafactory` defines classes at run time and native-image forbids that. One `static final boolean` picks the
branch. `telescope-core` carries its own native-image metadata.

CI checks native-image support in two ways. The `:core:imageTest` and `:internal:imageTest` tasks re-run each module's
whole suite with the `imagecode` property set. Every existing assertion then runs on the code an image uses, and both
tasks are part of `check`. A verifier covering eleven capabilities also compiles and runs as a native binary. It runs on
every push to `main` that changes `core`, `internal`, `codegen`, or the GraphQL example, and every week. Setup and
limits are in [`docs/native-image.md`](docs/native-image.md).

---

## Examples

The runnable modules below cover the surface, so pick the one that matches what you're evaluating.

| Module                                                                         | Stack                         | Pick when                                                                                                   |
| ------------------------------------------------------------------------------ | ----------------------------- | ----------------------------------------------------------------------------------------------------------- |
| [`examples/library/`](examples/library/)                                       | plain Java, no framework      | You want to see what the DSL does on its own, in a set of small capability demos, each with its own `main`  |
| [`examples/springboot/order-jpa/`](examples/springboot/order-jpa/)             | Spring Boot + JPA + Hibernate | You want everything at once, across several controllers over one realistic `Order` domain on a single stack |
| [`examples/springboot/product-starter/`](examples/springboot/product-starter/) | Spring Boot autoconfig        | You want registry discovery with no wiring, where you declare `@Bean Mapper<A, B>` and the starter finds it |
| [`examples/springboot/org-chart/`](examples/springboot/org-chart/)             | Spring Boot + JPA cycles      | You have a domain that refers to itself, such as an org chart or a thread, and want cycle-safe mapping      |
| [`examples/springboot/invoicing/`](examples/springboot/invoicing/)             | `@Bridge` codegen             | You want conversion bound at compile time on a hot path                                                     |

Start with [`order-jpa/`](examples/springboot/order-jpa/) for the broadest view. Start with
[`examples/library/`](examples/library/) to see telescope without a framework around it. The per-module guide is
[`examples/springboot/README.md`](examples/springboot/README.md).

---

## Published artifacts

Everything is published to Maven Central under `io.github.eschizoid`.

| Artifact                        | Role                                                                                                                                                                                                                                                                          |
| ------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `telescope-core`                | The DSL, meaning `Telescope`, `Mapper`, `Mapping`, `Either`, `Validated`, and the annotations. Add this one for typed paths and runtime mappers.                                                                                                                              |
| `telescope-internal`            | The optic lattice and reflection helpers. Transitive only, so it arrives automatically. A consumer that is itself a JPMS module can't compile against it, because its packages are exported only to telescope's own modules. A classpath consumer can reach it and shouldn't. |
| `telescope-codegen`             | The optional annotation processor for `@Focus`, `@BeanFocus`, `@Bridge`, and `@FromMap`, described in [docs/codegen.md](docs/codegen.md). It also registers the mapper verifier, which runs on every compilation and is turned off with `-Atelescope.verify=off`.             |
| `telescope-lombok`              | A Lombok-aware variant of the processor, for `@Data`, `@Value`, and `@Builder` POJOs.                                                                                                                                                                                         |
| `telescope-spring-boot-starter` | Spring Boot autoconfiguration and a `Mapper<A, B>` bean registry. With it on the classpath, `telescope-codegen` writes the `@TelescopeMapper` beans. Tested against Spring Boot 4.1.1.                                                                                        |
| `telescope-quarkus`             | The same registry as a Quarkus CDI bean. Built against Quarkus 3.40.1, with unit tests of the registry.                                                                                                                                                                       |

Installation snippets, Lombok on the processor path, and JPMS setup are in [docs/codegen.md](docs/codegen.md). On the
module path, typed paths and runtime mappers work on an application module's types once that module opens their package
to `io.github.eschizoid.telescope.internal`. An unqualified `opens` works too. Telescope adds the read edge to the
application module itself.

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

Telescope works on records and JavaBeans-style POJOs. Records rebuild through the canonical constructor. POJOs rebuild
through a write strategy telescope detects. It tries a builder, then an all-args constructor, then setters. You can
override the choice per class with `WriteHint.writeBean(...)`.

Paths take method references, not lambdas. `.field(User::name)` works. `.field(u -> u.name())` is rejected when the path
is built, with an error saying so. Telescope recovers the field name from the reference, and a lambda has none.

Accessor types are checked at compile time, and discovery happens at run time. `javac` verifies the source and focus
types of every method reference. Building a path then runs checks right away. They cover lambda rejection, bean write
strategies, and mapper rows. The late-bound entry points are `.fieldByName(String)` and its `Class<B>` overload. Their
names say so, and they resolve at first use.

Structural mapping is exact. Same-name matching pairs fields by exact name, recursively, and there is no fuzzy matching.
Without a row, a pair of fields converts only in these cases:

- the two types are the same, or one is a primitive and the other its wrapper
- one is an `Optional` and the other a nullable value
- the two are records or beans that map, or containers of the same kind whose elements convert
- the two are enums with matching constant names

There is no implicit conversion between `String` and numbers, between an enum and a `String`, or between dates and
strings. Any conversion like that is a row you write.

Null handling is uniform. Null containers and null `Optional` fields focus nothing. A null value in the middle of a path
also focuses nothing, so `find` returns an empty `Optional`, `toList` returns an empty list, and `read` throws
`NoSuchElementException`. A mapper's `forward(null)` and `backward(null)` return `null`. The full table is in
[docs/navigation.md](docs/navigation.md).

Telescope isn't a general transformation language. One path focuses one type. Bulk edits of different types go through
`Telescope.all` with one edit per path.

---

## Architecture

The public layer is one type, `Telescope<S, A>`, plus `Mapper`, `Mapping`, the two effect types, and the annotations.
Underneath it is an optic lattice of `Iso`, `Lens`, `Prism`, `Affine`, and `Traversal`, the same shapes as Haskell's
lens and Scala's Monocle. The public layer composes them, and none of them appears in user-facing code. JPMS qualified
exports keep the lattice invisible to consumers.

Runtime accessor dispatch uses lambdas built by `LambdaMetafactory`, or plain `MethodHandle` closures inside a native
image. Discovery is reflective and cached per class. The ADRs under [`docs/adr/`](docs/adr/) explain the rest, including
why the lattice is hidden and what the codegen emits.

---

## Build and test

```bash
./gradlew build          # everything: core, internal, codegen, lombok, the starters, and the examples
./gradlew check          # what CI runs: every test suite, the formatting gate, and the native-image suite runs
./gradlew :core:test     # the DSL surface only
./gradlew :benchmarks:jmh -Pjmh.includes=MapStructComparisonBenchmark   # the head-to-head numbers
```

Java 21 or later is enough to use telescope. The build itself uses a newer toolchain, and CI builds every module on
Temurin JDK 25.

---

## License

Telescope is licensed under Apache 2.0. See [LICENSE](LICENSE).
