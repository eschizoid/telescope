<p align="center">
  <img src="img/logo.png" alt="telescope — optics-based DSL for Java records and POJOs" width="320" />
</p>

# telescope

[![JVM 21+](https://img.shields.io/badge/JVM-21%2B-brightgreen.svg?&logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![Build](https://github.com/eschizoid/telescope/actions/workflows/ci.yaml/badge.svg)](https://github.com/eschizoid/telescope/actions/workflows/ci.yaml)
[![Codecov](https://codecov.io/gh/eschizoid/telescope/graph/badge.svg?token=a235ea8b-e6dc-45c6-8fea-e5050940c5d4)](https://codecov.io/gh/eschizoid/telescope)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.eschizoid/telescope-core.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.eschizoid/telescope-core)
[![Javadoc](https://javadoc.io/badge2/io.github.eschizoid/telescope-core/javadoc.svg?color=purple)](https://javadoc.io/doc/io.github.eschizoid/telescope-core)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

**Telescope's primitive is the reusable typed path** — a value, built from method references and checked by `javac`,
that points at data anywhere inside a nested structure:

```java
static final Telescope<Company, String> EMAILS = Telescope.of(Company.class)
  .each(Company::departments)
  .each(Department::teams)
  .each(Team::users)
  .field(User::email);
```

Hold that value and the rest of the library follows from it: read through it, rebuild the whole tree through it
immutably (`EMAILS.update(company, String::toLowerCase)`), collect or count every value it reaches, lift an update
through an async or validation effect — and map between types, because a mapping is the same primitive applied across
two shapes, each row pairing a source path with a target path:

```java
Mapper<Order, OrderDto> mapper = Telescope.mapper(
  Order.class,
  OrderDto.class,
  to(Order::customerName, OrderDto::fullName)
);

OrderDto dto  = mapper.forward(order); // same-name fields map automatically, nesting recurses
Order back = mapper.backward(dto);     // structurally reversible rows run backward from the same definition
mapper.explain();                      // the mapping describes itself — it is a runtime value, not generated code
```

That last line is the architectural difference with [MapStruct](https://mapstruct.org/) in a single call. MapStruct's
abstraction is generated bean mapping — excellent at that job, and by design the mapping disappears into a generated
class at compile time. Telescope's abstraction is the navigation value; mapping is one application of it, and the result
stays a value: composable with `.then(...)`, reusable across call sites, able to explain and trace itself in production.

<p align="center">
  <img src="img/head-to-head.gif" alt="The telescope-vs-MapStruct head-to-head test narrating itself: identical output, the default-policy unmapped-target case, a deep immutable update, and the mapper explaining and tracing itself." width="820" />
  <br />
  <sub>The <a href="examples/mapstruct-vs-telescope/"><code>mapstruct-vs-telescope</code></a> test, narrating itself — real output, trimmed for width.</sub>
</p>

One surface, two implementations, each answering a different moment. The runtime path composes paths and mappers on the
fly — zero annotations, no build step — and keeps working under GraalVM native-image
([verified by a native binary in CI](docs/native-image.md)). When a loop turns hot, `@Focus` / `@Bridge` codegen
compiles the same shapes to direct calls, landing in the same performance class as MapStruct's generated code in the
included JMH workloads ([measured below](#performance-measured)). Works on Java records, POJOs, and Lombok `@Data`
classes, Java 21+; Spring Boot starter and Quarkus extension ship as separate artifacts.

**The evidence:** a [migration coverage matrix](docs/mapstruct-parity.md) (29 MapStruct features audited — 13 fully
covered, 16 partially, each with its honest limitation and `file:line` evidence), a
[one-mapper-at-a-time migration guide](docs/mapstruct-migration.md), and a
[runnable head-to-head](examples/mapstruct-vs-telescope/) where each claim is a passing test. The
[comparison](#how-it-compares-to-mapstruct) is below.

## At a glance

| Need                                      | Telescope gives you                                                                                       |
| ----------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| Deep reads and immutable updates          | One reusable `Telescope<S, A>` path: `read`, `find`, `toList`, `set`, `update`, `updateValidated`, more.  |
| Mapping between records, POJOs, or both   | `Telescope.mapper(A.class, B.class, rows...)`, strict by default, bidirectional when rows are reversible. |
| A gradual path from runtime to hot paths  | Start with zero annotations; move hot navigators/converters to `@Focus`, `@BeanFocus`, or `@Bridge`.      |
| Production debugging without source dives | `explain()`, `trace(input)`, and opt-in `System.Logger` output from the same mapping value.               |
| Native-image and framework integration    | GraalVM metadata in core, native verifier in CI, plus Spring Boot and Quarkus registry artifacts.         |

If all you need is generated bean-to-bean mapping, MapStruct is still a strong choice. Telescope is for the cases where
the path itself is valuable: deep updates, reusable navigation, effectful transforms, bidirectional mapping values, and
runtime composition that can later be compiled down.

---

## Install

```kotlin
// Gradle (Kotlin DSL)
dependencies {
  implementation("io.github.eschizoid:telescope-core:1.3.0")
}
```

```xml
<!-- Maven -->
<dependency>
  <groupId>io.github.eschizoid</groupId>
  <artifactId>telescope-core</artifactId>
  <version>1.3.0</version>
</dependency>
```

That's the runtime. Compile-time codegen, Spring Boot starter, Quarkus extension, and JPMS setup are
[listed below](#additional-artifacts).

---

## Quick start

You have nested data and you want to update a field deep inside without writing copy constructors. This example is
complete — paste it into a `main` and it runs:

```java
import io.github.eschizoid.telescope.Telescope;

record Address(String city, String zip) {}

record User(String name, Address address) {}

// 1. Build a typed path once. Telescope values are immutable and thread-safe — static final is their home.
final var userCity = Telescope.of(User.class).field(User::address).field(Address::city);

// 2. Use it for reading, updating, anything else.
final var alice = new User("Alice", new Address("Springfield", "49007"));

String city = userCity.read(alice); // "Springfield"

User shouted = userCity.update(alice, String::toUpperCase); // city becomes "SPRINGFIELD"; alice is untouched
```

That's the whole model. Everything else is the same path with a different terminal method: mapping between types,
navigating containers, lifting through async or validation effects.

**What's next:**

- Navigate `List<X>` / `Optional<X>` / `Map<K, V>` and the full DSL surface → [docs/navigation.md](docs/navigation.md)
- Convert between types (record↔record, POJO↔record) → [docs/type-conversion.md](docs/type-conversion.md)
- Ask any mapper what it maps (`explain()` / `trace()` / a log level) → [docs/introspection.md](docs/introspection.md)
- Lift through async / validated / either / optional effects → [docs/effects.md](docs/effects.md)
- Compile-time-bound navigators for hot paths → [docs/codegen.md](docs/codegen.md)

---

## The tour

Quick start showed one deep update. These are the three shapes you'll actually live in: records, mapping, and beans.
Each fits in a screenful.

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

That's ~25 lines of manual reconstruction, every constructor enumerated, every untouched field threaded through by hand.
Or one reusable path. And the path isn't single-use:

```java
emails.toList(company);   // List<String> of every email
emails.count(company);    // how many
```

### Mapping

Mapping is the navigation primitive applied across two shapes. Same tree, now translated to a partner-facing
`CompanyDto` with a few renamed fields — one definition, both directions for the rows that are structurally reversible:

```java
record AddressDto(String town, String postalCode) {}

record UserDto(String fullName, int age, String email, AddressDto address) {}

record TeamDto(String name, List<UserDto> users) {}

record DepartmentDto(String name, List<TeamDto> teams) {}

record CompanyDto(String name, List<DepartmentDto> departments) {}

final Mapper<Company, CompanyDto> dtoMapper = Telescope.mapper(
  Company.class,
  CompanyDto.class,
  to(User::name, UserDto::fullName), // rename, applies everywhere User↔UserDto recurses
  to(Address::city, AddressDto::town),
  to(Address::zip, AddressDto::postalCode)
);

final CompanyDto dto = dtoMapper.forward(company);

final Company restored = dtoMapper.backward(dto); // the same row list, run in reverse
```

Same-name fields map automatically and recursion is automatic (`User::email`, `User::age`, all the list/tree wiring) —
you only name what changes. For comparison, MapStruct's reverse direction is a second method on the same interface, with
`@InheritInverseConfiguration` deriving eligible configuration from the forward method (its javadoc excludes
expressions, constants, and default values from inheritance). Telescope's mirror-image caveat: `constant` / `compute` /
one-way rows are forward-only — details below.

Need a flat field to land at a nested target leaf — MapStruct's `@Mapping(source = "flat", target = "a.b.c")`? The
codegen-emitted navigator is a first-class argument to `Mapping.to(...)`:

```java
Telescope.mapper(Cart.class, CartDto.class,
  to(Cart::customerName, CartDtoTelescope.of().shipping().recipient().fullName()));
```

Every hop in that navigator is a typed method call; `javac` and the IDE refactor follow each step.

Need eager literals or per-call computed values stamped at the target — MapStruct's `@Mapping(constant = "...")` and
`@Mapping(expression = "java(...)")`? Declared in the same `Telescope.mapper(...)` call:

```java
Telescope.mapper(Order.class, OrderDto.class,
  to(Order::id, OrderDto::id),
  constant(OrderDto::tenant, "production"),       // eager literal
  compute(OrderDto::createdAt, Instant::now),      // fresh per call
  compute(OrderDto::traceId, UUID::randomUUID),
  compute(OrderDto::metadata, HashMap::new));     // fresh container per call
```

`constant` captures once at row construction; `compute` invokes the supplier each forward call (the right choice
whenever a literal would share one mutable reference — `HashMap::new`, `Instant::now`, `UUID::randomUUID`). Both are
forward-only by design; the backward direction leaves the slot out of the source rebuild (references come back `null`,
primitives at their JLS default) — the same class of exclusion MapStruct documents for inverse-inherited configuration.

And every mapper you build this way can tell you what it does — no generated source to read:

```java
dtoMapper.explain();
// Mapped:
//   ✓ name → fullName
//   ✓ city → town
//   ...
```

The render is a view; the structure is data you can assert on. For a strict bidirectional mapper,
`explain().skipped().isEmpty() && explain().unusedSources().isEmpty()` means every field on both sides is accounted for
(constant/computed slots are populated, not skipped, so they don't appear as rows). The full story, including
`trace(input)` with real values and narrating every conversion by flipping a log level, is in
[docs/introspection.md](docs/introspection.md).

### Beans

POJOs don't need a mirror record. Navigate the bean directly with `ofBean`; `set`/`update` build a new root and rebuild
the modified path — a persistent-style update, not a deep clone: the original is never mutated, and untouched mutable
subtrees are shared between old and new ([details](docs/pojos.md)):

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
[docs/pojos.md](docs/pojos.md).

That's the library. No `Iso`, `Lens`, `Prism`, `Affine`, `Traversal`, `Getter`, `Setter`, `Fold` in user-facing code —
the optics live inside, behind one type.

---

## Picking your entry point

The tour used three of these; here's the whole map. Two questions decide it: are you working with records or POJOs, and
do you want to navigate one type in place or convert between two types?

| You want to…                        | Records                                       | POJOs                                | POJO ⇄ record                                   |
| ----------------------------------- | --------------------------------------------- | ------------------------------------ | ----------------------------------------------- |
| **Navigate & update** in place      | `Telescope.of(R.class)`                       | `Telescope.ofBean(P.class)`          | convert first (below), then navigate the record |
| **Convert / map** between two types | `Telescope.map(A.class, B.class, to(...), …)` | `Telescope.map(A.class, B.class, …)` | `Telescope.map(P.class, R.class, …)`            |
| **Compile-time-bound** (codegen)    | `@Focus` (navigate)                           | `@BeanFocus` (navigate)              | `@Bridge` (convert, any pair)                   |

Conversions are bidirectional, so any cell in the middle row composes into a longer navigation path with `.then(...)`.
Mismatched names get an explicit `Mapping.to(srcAccessor, tgtAccessor)` row in the `Telescope.map(...)` call; classes
the auto-detect can't handle get a `WriteHint.writeBean(target, strategy)` row. Both are covered in
[docs/pojos.md](docs/pojos.md).

The vocabulary, used consistently from here on: **navigation** is `of` / `ofBean` / `.field` / `.each` (a typed path
into one structure); **automatic structural mapping** is `Telescope.map` / `mapper` (same-name fields matched by exact
name and type, recursively — never fuzzily); **explicit conversion** is `from/to/using` (you write both directions,
nothing is automatic); **generated structural mapping** is `@Bridge`.

---

## Examples

When a screenful isn't enough, five runnable demos cover the surface — pick the one matching what you're evaluating:

| Module                                                                         | Stack                         | Pick when                                                                                                     |
| ------------------------------------------------------------------------------ | ----------------------------- | ------------------------------------------------------------------------------------------------------------- |
| [`examples/library/`](examples/library/)                                       | plain Java, no framework      | You want to see what the DSL does in isolation — 10 atomic capability demos (`*Demo.java` mains)              |
| [`examples/springboot/order-jpa/`](examples/springboot/order-jpa/)             | Spring Boot + JPA + Hibernate | You want the kitchen sink — eight endpoints, one realistic `Order` domain, every telescope angle on one stack |
| [`examples/springboot/product-starter/`](examples/springboot/product-starter/) | Spring Boot autoconfig        | You want zero-wiring registry discovery — drop `@Bean Mapper<A, B>` declarations and the starter indexes them |
| [`examples/springboot/org-chart/`](examples/springboot/org-chart/)             | Spring Boot + JPA cycles      | You have a self-referencing domain (org charts, threads, graphs) and want to see cycle-safe mapping           |
| [`examples/springboot/invoicing/`](examples/springboot/invoicing/)             | `@Bridge` codegen             | You want compile-time-bound conversion on a hot path                                                          |

Start with [`order-jpa/`](examples/springboot/order-jpa/) for the broadest view, or
[`examples/library/`](examples/library/) to see telescope without a framework around it. The full per-module guide is
[`examples/springboot/README.md`](examples/springboot/README.md).

---

## How it compares to MapStruct

MapStruct is an excellent compile-time bean mapper with a mature ecosystem and broad adoption: whole-object conversion
including nested graphs (dotted paths, automatic sub-mapping methods, collections, builders, multi-source methods,
update mappings). Nothing below argues otherwise — the comparison is about abstraction, not quality. Telescope overlaps
it on mapping and then adds what a mapping framework doesn't have: reusable typed _paths_ — no way exists in MapStruct
to point at `company.departments[].address.city` as a first-class value and read it, immutably update it, or lift it
through an effect. Where the two overlap, the architectural difference is how fields are named: telescope uses method
references (Java symbols, checked by `javac`, moved by any IDE's standard rename), MapStruct uses annotation strings
(validated by its processor at compile time, refactorable with the
[MapStruct IDEA plugin](https://mapstruct.org/documentation/ide-support/) per its documentation — the head-to-head
module tests the `javac` behavior of both failure modes; IDE-plugin behavior is cited, not tested here. Dotted nested
paths remain strings either way). The comparisons below pin MapStruct 1.6.3, the version the head-to-head module and
benchmarks build against.

To be precise about what a stale string costs, because the failure modes differ:

- **Rename a source property named in an explicit `@Mapping(source = ...)`** — MapStruct fails the build with an error.
  The string isn't unsafe; it's un-refactorable without the IDE plugin, and hand-fixed across mappers with it absent.
- **Rename or add a target property with no source counterpart** — under the default `unmappedTargetPolicy = WARN` the
  build succeeds with a warning and the field is `null` at runtime. `ReportingPolicy.ERROR` is a one-line opt-in that
  makes this a build failure; serious MapStruct setups enable it. Telescope's strict `mapper(...)` refuses unmapped
  fields at construction by default — the difference is the default, not the ceiling.

> **Runnable head-to-head** — the same `Order → OrderDto` mapping written both ways in one module
> ([`examples/mapstruct-vs-telescope`](examples/mapstruct-vs-telescope/)):
>
> ```bash
> ./gradlew :examples:mapstruct-vs-telescope:test
> ```
>
> Each claim is a passing test: the rename failure modes above (both of them, labelled), the default-policy
> unmapped-target case, and a deep immutable update — which is outside MapStruct's mapping abstraction (its
> `@MappingTarget` updates mutate an existing instance in place).
>
> **The paper trail:** the [coverage matrix](docs/mapstruct-parity.md) scores all 29 MapStruct features against
> telescope with `file:line` evidence per verdict (13 full · 16 partial), and the
> [migration guide](docs/mapstruct-migration.md) turns it into a one-mapper-at-a-time recipe.

#### Performance, measured

In the included JMH workloads (MapStruct 1.6.3, CI hardware, JDK 25 —
[methodology, environment, and both runs](docs/perf-mapstruct-comparison.md)), telescope codegen and MapStruct codegen
land in the same performance class:

| Tier (codegen vs codegen)   | telescope vs MapStruct                                                                            |
| --------------------------- | ------------------------------------------------------------------------------------------------- |
| flat (5 scalars)            | ~1.07× — under a nanosecond                                                                       |
| nested (one nested type)    | near-parity, but JMH-noisy run-to-run — a framework-overhead microbench, not a real-service shape |
| deep (3 levels + list hops) | ~1.15× — a near-tie, ~6–7 ns on a ~50 ns conversion, stable across two CI runs                    |

No codegen? `Telescope.mapper(...)` composes each record/bean pair into a single MethodHandle: zero annotations, no
build step, within ~1.3–4× of MapStruct in the same workloads (flat ~4×, nested ~2×, deep ~1.3–1.9× — closest where
trees are deepest). That's sub-microsecond conversion; whether it's fast enough is your call, and when a loop turns hot,
`@Bridge` puts you back in the codegen class. Reproduce any of it from the
[`Benchmarks`](.github/workflows/benchmarks.yaml) GitHub Action; the full matrix is in
[`benchmarks/README.md`](benchmarks/README.md#mapstruct-comparison-apples-to-apples).

#### Native-image

Codegen was never the question: MapStruct's generated mappers and telescope's are both reflection-free and both build
under GraalVM native-image without configuration. The difference is the runtime path — MapStruct doesn't have one, and
telescope's keeps working inside a native image: `Telescope.mapper(...)` and `.field(User::name)`, no build step. Inside
an image the substrate swaps its `LambdaMetafactory` accessors (runtime class definition, which native-image's closed
world forbids) for plain `MethodHandle` closures; one `static final boolean` picks the branch. `telescope-core` carries
its own native-image metadata; you register your own DTO types, same as any GraalVM app. A nine-capability verifier
compiles and runs as a real native binary in CI on every substrate push plus weekly. Setup and limits:
[`docs/native-image.md`](docs/native-image.md).

#### The capability table

Architecture differences, not impossibilities — and most rows trace to one root. A mapping held as a runtime value can
be composed, reversed, lifted through effects, and interrogated after the fact; a mapping compiled into a generated
class is complete at build time, by design:

| Capability                         | telescope                                                             | MapStruct                                                        |
| ---------------------------------- | --------------------------------------------------------------------- | ---------------------------------------------------------------- |
| Bidirectional mapping              | one row list, `forward(...)` / `backward(...)` on one value           | second method + `@InheritInverseConfiguration` (with exclusions) |
| Deep nested navigation + update    | `of(C).each(C::depts).field(D::address).update(c, fn)`                | not in scope (`@MappingTarget` mutates in place)                 |
| Effectful update                   | `updateAsync` / `updateOptional` / `updateEither` / `updateValidated` | not in scope — pair with external machinery                      |
| Accumulating validation            | `Validated.combine(...)` collects every failure in one pass           | not in scope — pair with Bean Validation or hand-rolled          |
| Mapper introspection               | `explain()` / `trace(input)` / flip a log level                       | read the generated source (which is genuinely debuggable)        |
| Unmapped-target safety             | strict construction by default                                        | `WARN` by default; `ERROR` a one-line opt-in                     |
| Sealed-root dispatch               | `Match.of(...).when(...).exhaustive()` (sealed exhaustiveness)        | `@SubclassMapping` (broader hierarchies, no sealed check)        |
| Multi-source merge (N → 1)         | `Telescope.merge(Target.class, from(...), ...)`                       | first-class multi-source methods, string-disambiguated           |
| Runtime path (no codegen required) | `Telescope.of(Class)`, opt into `@Focus` later                        | compile-time only                                                |
| GraalVM native-image               | codegen zero-config; runtime path survives AOT too                    | fully AOT-compatible (codegen); no runtime path to need it       |

The full accounting is the [coverage matrix](docs/mapstruct-parity.md): all 29 MapStruct features, each scored with the
telescope idiom, its honest limitation, and `file:line` evidence.

#### When MapStruct is the right pick

- You need embedded expression-language mapping bodies — `@Mapping(expression = "java(...)")` or qualifier dispatch —
  inline in the annotation rather than as plain Java mappers passed to `Mapping.via(...)`
- You need `@SubclassMapping` fan-out across open (non-sealed) hierarchies — telescope's `Match` covers sealed roots,
  and the [coverage matrix](docs/mapstruct-parity.md) scores the gap honestly
- Conversion is the entire job — no path reuse, deep updates, effects, or bidirectional values — and generated,
  inspectable mapper source is a feature for your team

#### When telescope is the right pick

- Your problem includes **deep navigation** alongside mapping — the path value compounds with depth: every extra level
  is one more hop on a value you already hold, not another block of rebuild code
- You want **bidirectional from one definition** — `forward(...)` / `backward(...)` on one value, no second method
- You need to lift a mapping or field update through an **effect** — `updateValidated`, `updateAsync`, `updateEither`,
  `updateOptional`
- You have **multi-source mappers** (`N → 1`) — `Telescope.merge(...)` returns a `Mapper<Sources, T>`, declared once
- You have a **sealed root** and want compile-checked exhaustiveness over the permits
- You're navigating a mix of **records and POJOs** at any depth without materializing intermediate DTOs
- You're deploying to **GraalVM native-image** and want mapping without a build step
- You want one abstraction doing reading, updating, mapping, and validation — the path, one mental model

Ready to try it? Write your next mapper as one `Telescope.mapper(...)` call and leave every existing MapStruct mapper
alone — the [migration guide](docs/mapstruct-migration.md) covers coexistence whenever you want more.

---

## Additional artifacts

Published to Maven Central under `io.github.eschizoid`. The six artifacts in the family:

| Artifact                        | Role                                                                                                                                                                |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `telescope-core`                | The DSL — `Telescope`, `Mapper`, `Mapping`, `Either` / `Validated`, annotations. The one you add for the runtime path.                                              |
| `telescope-internal`            | Optic lattice + reflection helpers. Transitive only — pulled in automatically; users cannot reference it (JPMS qualified exports block visibility at compile time). |
| `telescope-codegen`             | Optional `@Focus` / `@BeanFocus` / `@Bridge` annotation processor — see [docs/codegen.md](docs/codegen.md).                                                         |
| `telescope-lombok`              | Lombok-aware variant of the processor for `@Data` / `@Value` / `@Builder` POJOs.                                                                                    |
| `telescope-spring-boot-starter` | Spring Boot autoconfig + `Mapper<A, B>` bean registry. Compiled and CI-tested against Spring Boot 4.1.0.                                                            |
| `telescope-quarkus`             | Quarkus CDI extension with the same registry shape. Compiled and CI-tested against Quarkus 3.37.3.                                                                  |

Installation snippets, annotation-processor ordering with Lombok, and JPMS setup: [docs/codegen.md](docs/codegen.md).

---

## Documentation

| Doc                                                                    | What's in it                                                                                       |
| ---------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| [docs/navigation.md](docs/navigation.md)                               | The full DSL surface, the cookbook (containers, sealed cases, filters, multi-edit), null semantics |
| [docs/type-conversion.md](docs/type-conversion.md)                     | Explicit conversions (`from/to/using`) and automatic structural mapping (`Telescope.map`)          |
| [docs/pojos.md](docs/pojos.md)                                         | Bean navigation, write strategies, aliasing, the POJO↔record workflow                              |
| [docs/effects.md](docs/effects.md)                                     | The four effects, exception semantics, what the async executor bounds (and doesn't)                |
| [docs/introspection.md](docs/introspection.md)                         | `explain()` / `trace()` / auto-logging, and precisely what the report contains                     |
| [docs/codegen.md](docs/codegen.md)                                     | `@Focus` / `@BeanFocus` / `@Bridge`, Lombok ordering, JPMS setup                                   |
| [docs/native-image.md](docs/native-image.md)                           | The GraalVM contract: what telescope ships, what your app registers                                |
| [docs/mapstruct-parity.md](docs/mapstruct-parity.md)                   | The 29-feature coverage matrix with per-row evidence                                               |
| [docs/mapstruct-migration.md](docs/mapstruct-migration.md)             | One-mapper-at-a-time migration, coexistence setup, translation table                               |
| [docs/perf-mapstruct-comparison.md](docs/perf-mapstruct-comparison.md) | Benchmark methodology, environment, and the full result set                                        |

---

## Constraints worth knowing

- **Records and JavaBeans-style POJOs.** Records rebuild through the canonical constructor; POJOs through an
  auto-detected write strategy (builder → setters → fields → constructor), overridable per class with
  `WriteHint.writeBean(...)`.
- **Method references, not lambdas.** `.field(User::name)` works; `.field(u -> u.name())` is rejected when the path is
  built, with an error saying exactly this. Field names are recovered from the reference; a lambda has none.
- **Accessor types are compile-checked; discovery is runtime.** `javac` verifies every method reference's source and
  focus types. Path construction then does eager runtime checks (lambda rejection, bean write-strategy resolution,
  mapper row validation at factory time). The one late-bound entry point is `.fieldByName(String)` — documented loudly,
  resolves the name at first use.
- **Structural mapping is exact.** Same-name matching is exact name + type, recursively — no fuzzy matching, no implicit
  String↔number conversions. What MapStruct generates silently, you write as a row.
- **Null semantics are uniform.** Null containers and null `Optional` fields focus nothing; null intermediate hops
  propagate on reads; `forward(null)` is `null`. The full table: [docs/navigation.md](docs/navigation.md).
- **Not a general transformation language.** One focused type per path; heterogeneous bulk edits are `Telescope.all`
  with one edit per path.

---

## Architecture (short version)

Two layers. The public layer is one type (`Telescope<S, A>`) plus `Mapper`, `Mapping`, the two effect types, and the
annotations. The internal layer is a proven optic lattice (`Iso` / `Lens` / `Prism` / `Affine` / `Traversal`, the same
shapes as Haskell's lens and Scala's Monocle) that the public layer composes — JPMS qualified exports keep it invisible
to consumers. Runtime accessor dispatch is `LambdaMetafactory`-built lambdas (plain `MethodHandle` closures inside a
native image); discovery is reflective and cached per class. The full story, including why the lattice is hidden and
what the codegen emits, is in the ADRs under [`docs/adr/`](docs/adr/).

---

## Build & test

```bash
./gradlew build          # everything: core, internal, codegen, lombok, starters, examples
./gradlew :core:test     # the DSL surface
./gradlew :benchmarks:jmh -Pjmh.includes=MapStructComparisonBenchmark   # the head-to-head numbers
```

Java 21+ to consume; the build itself uses a newer toolchain. CI builds every module on Temurin JDK 25.

---

## License

Apache 2.0. See [LICENSE](LICENSE).
