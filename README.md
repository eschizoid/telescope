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

Telescope is the mapping layer between the REST, service and entity layers of a JVM service.

A service usually holds the same data three times: as the wire DTO a REST endpoint reads and writes, as the domain type
the service works with, and as the entity it persists. Telescope gives each hop between two of those layers one typed
mapper. You write the mapper as an ordinary Java expression with method references instead of strings, the compiler
checks every field it names, and an annotation processor turns it into plain Java. That generated code runs in about
MapStruct's time on the benchmarks below.

```java
public record Invoice(String id, Customer customer, long totalCents, String internalNote) {
  @Bridge
  static final ForwardMapper<Invoice, InvoiceDto> DTO = mapperForward(
    Invoice.class,
    InvoiceDto.class,
    // a renamed field
    to(Invoice::id, InvoiceDto::number),
    // a field read through a nested object
    to(path(Invoice::customer, Customer::email), InvoiceDto::billTo),
    // a field built from the whole invoice
    compute(InvoiceDto::total, Money::format),
    // a field left out on purpose
    drop(Invoice::internalNote)
  );

  public InvoiceDto toDto() {
    return DTO.forward(this);
  }
}

InvoiceDto dto = invoice.toDto();
```

Every field in that mapper is a method reference, so a typo is a compile error and an IDE rename updates the mapper
along with everything else. Fields with the same name on both sides map without a row, nested records included. The
`telescope-codegen` processor reads the expression and generates the conversion, which calls `id()`,
`customer().email()` and `Money.format(invoice)` directly, with no reflection. Leave the processor off the build and the
same field runs the same rows at run time instead, with the same result.

## From the wire to the database

| Hop                       | What telescope does there                                                                               | Guide                                                                                  |
| ------------------------- | ------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| REST endpoint and service | `@TelescopeGetMapping` and its siblings map the response, `@TelescopeRequestBody` maps the request body | [Spring Boot starter](spring-boot-starter/README.md), [Quarkus](quarkus/README.md)     |
| Inside the service        | typed rows on a `@Bridge` mapper field, run as `forward`, `backward`, `patch` or `into`                 | [docs/type-conversion.md](docs/type-conversion.md), [docs/codegen.md](docs/codegen.md) |
| Service and entity        | `into` updates a managed entity in place, and `ADDERS` fills a collection through its adder             | [docs/pojos.md](docs/pojos.md)                                                         |

At the REST endpoint, a mapped handler can return a single value, a `List`, `Set`, `Optional`, `Page` or
`ResponseEntity`, and with springdoc on the classpath the API documentation shows the response type. `toFormatted` and
`toFormattedNumber` rows write a date or a number as text in a pattern you give. `@TelescopeMapper` and
`@TelescopeTransformer` turn an interface into an injectable bean on Spring and on Quarkus.

Inside the service, `Telescope.merge(...)` builds one value from several sources, and fields convert between common
types such as a number and its text without a row. `explain()` and `trace()` describe what a mapper does, and the
compile-time verifier reports what the mapper would refuse. A type that implements its generated `<Type>Mappings`
interface converts itself, as in `order.toOrderDto()`.

At the entity, `into` clears and refills a collection the entity already holds instead of replacing it, so a managed
`@OneToMany` keeps its identity. Under `ADDERS`, each element goes through the entity's adder, which is where a
bidirectional association sets its back-reference. With Hibernate on the classpath, the runtime mapper reads a lazy
proxy as the entity class it stands for, except inside a native image.
[`examples/demo/spring/`](examples/demo/spring/README.md) is an order service on Spring Boot and Hibernate whose PATCH
runs `into` on the managed entity and refills its lines through `ADDERS`.

Two modules are planned and not built yet. `telescope-jackson` ([ADR-0016](docs/adr/0016-jackson-integration.md)) would
register mappers with Jackson and bind JSON straight to a type. `telescope-jpa`
([ADR-0017](docs/adr/0017-jpa-integration.md)) would leave a collection the persistence provider has not loaded
untouched during a copy or an `into`. Typed `before` and `after` edit rows are proposed in
[ADR-0024](docs/adr/0024-typed-before-and-after-edit-rows.md).

Telescope can also replace MapStruct. The cheat sheet below pairs MapStruct's annotations with telescope's rows, and on
the latest benchmark run a generated mapper took 0.88 to 1.02 times MapStruct's time.

---

## Mapping, one row at a time

You name only what differs between the two types. Each kind of difference has one row.

| What differs                                | Row                                                         |
| ------------------------------------------- | ----------------------------------------------------------- |
| a field has another name                    | `to(Order::customerName, OrderDto::fullName)`               |
| a field has another type                    | `to(Order::placedAt, OrderDto::placedOn, IsoDate.class)`    |
| a field lives inside a nested object        | `to(path(Order::customer, Customer::city), OrderDto::city)` |
| a field is built from the source            | `compute(OrderDto::label, Labels::of)`                      |
| a field gets a fresh value on each call     | `compute(OrderDto::createdAt, Instant::now)`                |
| a field is the same literal every time      | `constant(OrderDto::tenant, "production")`                  |
| a field falls back to a default             | `toOrElse(Order::region, OrderDto::region, "EMEA")`         |
| a field converts through a mapper you wrote | `via(Order::lines, OrderDto::lines, LineMapper.class)`      |
| a source field is left out                  | `drop(Order::internalNote)`                                 |

`IsoDate` is a class implementing `BridgeFn<LocalDate, String>`, `Labels.of(Order)` is any static method taking the
source, and `LineMapper` is a hand-written class with static `forward` and `backward` methods. A nested path also works
as a target, so `to(Order::city, path(OrderDto::shipping, Shipping::city))` builds the `shipping` object it writes into.

A read through a nested path gives the property's null value when an object on the way is null: `null` for a reference
and zero for a primitive. A write through one builds a missing object on the way, unless the value written is null. A
`compute` row names no source property, so every source field still maps by name beside it. `constant` and `compute`
rows run on `forward` only, and `backward` and `patch` skip them.

`Telescope.mapper(...)` returns a two-way `Mapper` with `forward`, `backward` and `patch`. Its rows run in reverse on
`backward`, and it refuses, when it is built, a source or target field that no row accounts for and no field of the same
name pairs with. `Telescope.mapperForward(...)` returns a one-way `ForwardMapper`, which leaves a target field it can't
fill at its null value. Both take the same rows.

```java
Mapper<Company, CompanyDto> dtoMapper = Telescope.mapper(
  Company.class,
  CompanyDto.class,
  to(User::name, UserDto::fullName), // a rename, applied wherever User and UserDto meet in the tree
  to(Address::city, AddressDto::town),
  to(Address::zip, AddressDto::postalCode)
);

CompanyDto dto = dtoMapper.forward(company);

Company back = dtoMapper.backward(dto); // the same rows, run in reverse
```

A mapper is a value you can hold, pass around and ask questions of. `dtoMapper.explain()` lists the top-level fields it
pairs, converts or skips, and for a mapper a `@Bridge` field declares it also says whether the field runs the generated
bridge. `dtoMapper.trace(company)` shows the same for one input, with its values.
[docs/introspection.md](docs/introspection.md) covers both.

## Typed paths

A mapper is built from paths, and a path works on its own too. A path points at data anywhere inside a nested structure,
and you can read through it, count through it, or rebuild the whole structure with one change, without mutating
anything.

```java
static final Telescope<Company, String> EMAILS = Telescope.of(Company.class)
  .each(Company::departments)
  .each(Department::teams)
  .each(Team::users)
  .field(User::email);

Company lowered = EMAILS.update(company, String::toLowerCase); // a new Company, every email lowercased

List<String> all = EMAILS.toList(company);

long howMany = EMAILS.count(company);
```

Without telescope, the same update rebuilds every record on the way down, with nested `stream().map(...)` calls and
constructors that pass every untouched field through by hand. Several edits on the same structure fold into one pass:

```java
Telescope<Company, Company> normalize = Telescope.all(
  over(EMAILS, String::toLowerCase),
  over(Telescope.of(Company.class).field(Company::name), String::strip)
);

Company cleaned = normalize.apply(company);
```

Paths also go through `Optional`, `Map` values and sealed interfaces, and run an update inside a `CompletableFuture`,
`Optional`, `Either` or `Validated` result. [docs/navigation.md](docs/navigation.md) and
[docs/effects.md](docs/effects.md) have the details.

## One mapper, two ways to run it

The same mapper runs two ways, and the call site is the same either way.

- **Generated.** With `telescope-codegen` on the annotation processor path, `@Bridge` on a `static final` mapper field
  makes the processor compile the field's rows into Java. The field then runs that code.
- **At run time.** Without the processor, or without `@Bridge`, the field builds the mapper from its rows when the class
  loads. Nothing is generated and no build step is needed.

The processor also writes a `<Type>Mappings` interface for each type a `@Bridge` field converts. A type that names it in
its `implements` clause converts itself, and the call costs what calling the field costs:

```java
public record Order(Long id, String customer) implements OrderMappings {
  @Bridge
  static final Mapper<Order, OrderDto> DTO = mapper(Order.class, OrderDto.class, to(Order::id, OrderDto::number));
}

OrderDto dto = order.toOrderDto(); // Order.DTO.forward(order)
```

That form needs the processor, since the interface exists only once it runs. The field form runs either way.
[docs/codegen.md](docs/codegen.md#a-value-that-converts-itself-typemappings) has the rules.

You can start with the runtime mapper and add `@Bridge` where a hot loop needs the speed. A test suite runs the same
inputs through both and fails when they give different results, and a compile-time verifier reports the refusals the
runtime would raise, for each `Telescope.mapper(...)` call whose types it can read.
[docs/mapstruct-comparison.md](docs/mapstruct-comparison.md#runtime-and-generated-mappers-are-tested-to-give-the-same-result)
explains how the two are held together and the few shapes one refuses and the other accepts.

## Measured performance

<!-- metrics: from MapStructComparisonBenchmark, Actions run 41877328599 -->

On the latest run, a generated mapper took between 0.88 and 1.02 times MapStruct's time on every shape in the table, and
allocated the same bytes per call or fewer. The runtime mapper, which needs no build step, took 1.15 to 3.0 times
MapStruct's time. On the flat and nested shapes it adds 4.5 to 6.7 ns per call, which is most of a small call's time,
and on the deeper and container shapes it adds more time in total but less as a share of the call.

| Shape                             | MapStruct | telescope, generated | telescope, runtime | bytes per call, all three |
| --------------------------------- | --------: | -------------------: | -----------------: | ------------------------- |
| flat, 5 fields, forward           |   3.31 ns |      3.37 ns (1.02×) |     10.0 ns (3.0×) | 32                        |
| flat, backward                    |   3.42 ns |      3.35 ns (0.98×) |     9.13 ns (2.7×) | 32                        |
| one nested record, forward        |   4.76 ns |      4.77 ns (1.00×) |     11.4 ns (2.4×) | 48                        |
| one nested record, backward       |   5.50 ns |      4.82 ns (0.88×) |     9.95 ns (1.8×) | 48                        |
| three levels with lists, forward  |   61.8 ns |      62.3 ns (1.01×) |    81.3 ns (1.31×) | 376                       |
| three levels with lists, backward |   67.7 ns |      62.5 ns (0.92×) |    78.0 ns (1.15×) | 376                       |
| a 100-entry `Set` field, forward  |  1,557 ns |     1,542 ns (0.99×) |   1,800 ns (1.16×) | 7,560 to 7,576            |
| a 100-entry `Map` field, forward  |  1,336 ns |     1,267 ns (0.95×) |   1,542 ns (1.15×) | 7,528 to 7,560            |

The flat and nested rows run a `@Bridge` mapper field; the deeper rows run a class-level `@Bridge`, which generates the
same kind of code. The run is GitHub Actions run 41877328599 on `main` at `75867893`, with MapStruct 1.6.3 on JDK 25, 4
forks of 8 measured iterations and the allocation profiler. MapStruct's rows are the control, so each ratio is read
within the one run. One of MapStruct's four forks on the Map row ran at about 1,550 ns and the other three at 1,256 to
1,277 ns, so read that ratio as a near tie.

[docs/perf-mapstruct-comparison.md](docs/perf-mapstruct-comparison.md) has the method and the per-fork figures of
earlier runs, and the [`Benchmarks`](.github/workflows/benchmarks.yaml) GitHub Action reproduces any of it.

## Coming from MapStruct

| MapStruct                                                  | telescope                                                        |
| ---------------------------------------------------------- | ---------------------------------------------------------------- |
| `@Mapping(source = "customerName", target = "fullName")`   | `to(Order::customerName, OrderDto::fullName)`                    |
| `@Mapping(source = "customer.city", target = "city")`      | `to(path(Order::customer, Customer::city), OrderDto::city)`      |
| `@Mapping(target = "tenant", constant = "production")`     | `constant(OrderDto::tenant, "production")`                       |
| `@Mapping(target = "label", expression = "java(...)")`     | `compute(OrderDto::label, Labels::of)`                           |
| `@Mapping(target = "region", defaultValue = "EMEA")`       | `toOrElse(Order::region, OrderDto::region, "EMEA")`              |
| `@Mapping(target = "shipDate", dateFormat = "dd.MM.yyyy")` | `toFormatted(Order::shipDate, OrderDto::shipDate, "dd.MM.yyyy")` |
| `@Mapping(target = "total", numberFormat = "$#.00")`       | `toFormattedNumber(Order::total, OrderDto::total, "$#.00")`      |
| `@Mapping(target = "internalNote", ignore = true)`         | `drop(Order::internalNote)`                                      |
| `@Mapper(uses = LineMapper.class)`                         | `via(Order::lines, OrderDto::lines, LineMapper.class)`           |
| `@InheritInverseConfiguration` on a second method          | `backward(...)` on the same mapper                               |
| `@MappingTarget` to update an existing bean                | `into(existingBean, source)`                                     |
| `unmappedTargetPolicy = ReportingPolicy.ERROR`             | the default for `Telescope.mapper(...)`                          |

A MapStruct mapper names its fields in strings inside annotations, because Java annotations only accept constants. Its
processor checks those strings when it compiles, but a plain IDE rename doesn't update them; only the MapStruct IDE
plugin does. Telescope keeps the mapping out of the annotation, so every field is a method reference the compiler
checks. The two tools land in the same performance class on the generated path.

MapStruct still covers a few cases telescope doesn't, such as `@SubclassMapping` across an open class hierarchy rather
than a sealed one. The [full comparison](docs/mapstruct-comparison.md) and the
[migration guide](docs/mapstruct-migration.md) cover both tools in detail, and
[docs/mapstruct-parity.md](docs/mapstruct-parity.md) scores 29 MapStruct features against telescope's equivalents.

---

## Install

```kotlin
// Gradle (Kotlin DSL)
dependencies {
  implementation("io.github.eschizoid:telescope-core:2.2.0")
  annotationProcessor("io.github.eschizoid:telescope-codegen:2.2.0") // optional: generated mappers and navigators
}
```

```xml
<!-- Maven -->
<dependency>
  <groupId>io.github.eschizoid</groupId>
  <artifactId>telescope-core</artifactId>
  <version>2.2.0</version>
</dependency>
```

Release 2.0.0 predates `path(...)`, `compute` rows that read the source, `to` and `via` rows that name a class,
`@TelescopeGetMapping` and `@Bridge` on a mapper field. Those ship in the next release. On Maven, the processor goes in
the compiler plugin's `annotationProcessorPaths`. [docs/codegen.md](docs/codegen.md) has that setup, Lombok on the
processor path, and JPMS.

## Quick start

The records and statements below run as written inside a `main` method, with the import at the top of the file.

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

POJOs work the same way through `Telescope.ofBean(...)`, which rebuilds a bean through its builder, an all-args
constructor or its setters and never mutates the original. [docs/pojos.md](docs/pojos.md) covers write strategies.

---

## Integrations

- **Spring Boot.** `telescope-spring-boot-starter` collects every `Mapper` bean into a registry you look up by source
  and target class. `@TelescopeMapper` turns an interface into an injectable mapper bean, and `@TelescopeGetMapping`
  lets a controller return a domain object while the client receives its DTO. See the
  [starter guide](spring-boot-starter/README.md).
- **Quarkus.** `telescope-quarkus` adds the same registry as a CDI bean, and `@TelescopeMapper` interfaces become CDI
  beans there too. See the [Quarkus guide](quarkus/README.md).
- **Lombok.** `telescope-lombok` generates typed navigators for `@Data`, `@Value` and `@Builder` classes. See the
  [Lombok guide](lombok/README.md).
- **GraalVM native image.** Runtime mappers, typed paths and generated code all run in a native image. Generated
  `@Bridge` and `@FromMap` code needs no reflection configuration, while generated navigators and the runtime path need
  your own types registered, as in any GraalVM application. See [docs/native-image.md](docs/native-image.md).

## Examples

[`examples/demo/`](examples/demo/README.md) is a small order service: a REST API over a database whose orders are
placed, paid, shipped or cancelled, with a public DTO that hides the cost price, a PATCH that changes only what the
client sent, an older API version the mobile app still calls, an import of untyped JSON and an invoice built from
several objects. Its README walks those problems in the order a request meets them, from the REST layer through the
service to the JPA entities, and quotes the mapper that solves each one. The mappers are written once, and the service
runs three ways:

| Module                                                                 | Runs the order service                                         |
| ---------------------------------------------------------------------- | -------------------------------------------------------------- |
| [`examples/demo/spring/`](examples/demo/spring/README.md)              | on Spring Boot, with JPA, mapped responses and request bodies  |
| [`examples/demo/quarkus/`](examples/demo/quarkus/README.md)            | on Quarkus, with the generated CDI beans                       |
| [`examples/demo/native/`](examples/demo/native/README.md)              | as a GraalVM native image, which is also the native-image gate |
| [`examples/mapstruct-vs-telescope/`](examples/mapstruct-vs-telescope/) | the same mappings in both tools, tested side by side           |

## Published artifacts

Everything is on Maven Central under `io.github.eschizoid`.

| Artifact                        | What it is                                                                                                       |
| ------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `telescope-core`                | typed paths, runtime mappers and the annotations; the one dependency you always add                              |
| `telescope-codegen`             | the optional annotation processor for `@Bridge`, `@Focus`, `@BeanFocus` and `@FromMap`, plus the mapper verifier |
| `telescope-lombok`              | the processor variant for Lombok `@Data`, `@Value` and `@Builder` classes                                        |
| `telescope-spring-boot-starter` | Spring Boot auto-configuration, the mapper registry and generated mapper beans                                   |
| `telescope-quarkus`             | the mapper registry as a Quarkus CDI bean, and generated mapper beans                                            |
| `telescope-internal`            | the engine underneath, which arrives with `telescope-core` and isn't meant to be used directly                   |

## Documentation

The guides are grouped by the hop they cover, then by the MapStruct comparison and the runtime.

### REST endpoint and service

| Guide                                                          | Covers                                                                                            |
| -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| [spring-boot-starter/README.md](spring-boot-starter/README.md) | mapped responses and request bodies, springdoc, the mapper registry and generated beans on Spring |
| [quarkus/README.md](quarkus/README.md)                         | the mapper registry and generated beans on Quarkus                                                |

### Inside the service

| Guide                                              | Covers                                                                                 |
| -------------------------------------------------- | -------------------------------------------------------------------------------------- |
| [docs/type-conversion.md](docs/type-conversion.md) | mappers, rows and structural conversion                                                |
| [docs/codegen.md](docs/codegen.md)                 | `@Bridge`, `<Type>Mappings`, `@Focus`, `@BeanFocus`, `@FromMap`, Lombok and JPMS setup |
| [docs/introspection.md](docs/introspection.md)     | `explain()`, `trace()` and logging                                                     |
| [docs/navigation.md](docs/navigation.md)           | every path operation, containers, sealed cases, filters and multi-edit                 |
| [docs/effects.md](docs/effects.md)                 | updates inside async, validated, either and optional results                           |

### Service and entity

| Guide                                                                | Covers                                                                      |
| -------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| [docs/pojos.md](docs/pojos.md)                                       | beans, write strategies, `into` and `ADDERS`, and mixing beans with records |
| [docs/adr/0017-jpa-integration.md](docs/adr/0017-jpa-integration.md) | the planned `telescope-jpa` module                                          |

### MapStruct and performance

| Guide                                                                  | Covers                                                       |
| ---------------------------------------------------------------------- | ------------------------------------------------------------ |
| [docs/mapstruct-comparison.md](docs/mapstruct-comparison.md)           | telescope and MapStruct side by side, in detail              |
| [docs/mapstruct-migration.md](docs/mapstruct-migration.md)             | moving one mapper at a time, and running both tools together |
| [docs/mapstruct-parity.md](docs/mapstruct-parity.md)                   | every MapStruct feature and its telescope equivalent         |
| [docs/perf-mapstruct-comparison.md](docs/perf-mapstruct-comparison.md) | benchmark method, environment and every result               |

### Runtime

| Guide                                        | Covers                            |
| -------------------------------------------- | --------------------------------- |
| [docs/native-image.md](docs/native-image.md) | what a GraalVM native image needs |

## Constraints

- **Method references, not lambdas.** `.field(User::name)` works and `.field(u -> u.name())` is refused, because a
  lambda carries no field name. `.fieldByName(String)` is the late-bound escape hatch, and its name says so.
- **Exact matching.** Fields pair by exact name, recursively, with no fuzzy matching. Without a row, a field converts
  only between the same type, a primitive and its wrapper, an `Optional` and a nullable value, two records or beans that
  map, two containers whose elements convert, two enums with the same constant names, or two sealed interfaces whose
  cases map. It also converts a `String` to and from a number, an enum by constant name and a boolean, a list and a set
  into each other's kind, and, on a forward-only mapper, a number to one it widens to. Narrowing, and text against a
  date or time, take a row.
- **Records and JavaBeans-style POJOs.** Records rebuild through the canonical constructor. POJOs rebuild through a
  builder, an all-args constructor or setters, in that order, and `WriteHint.writeBean(...)` picks one.
- **Nulls are uniform.** A null in the middle of a path focuses nothing, and a mapper maps `null` to `null`.
  [docs/navigation.md](docs/navigation.md) has the full table.

## Build and test

```bash
./gradlew build          # every module, the starters and the examples
./gradlew check          # what CI runs: every test suite, the formatting gate and the suites rerun on the native-image code path
./gradlew :benchmarks:jmh -Pjmh.includes=MapStructComparisonBenchmark   # the head-to-head numbers
```

Telescope's classes target Java 21. The build uses a newer toolchain, and CI builds every module on JDK 25. The design
decisions are recorded in [docs/adr/](docs/adr/).

## License

Telescope is licensed under Apache 2.0. See [LICENSE](LICENSE).
