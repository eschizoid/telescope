<p align="center">
  <img src="img/logo.png" alt="telescope, the typed mapping layer for Java services" width="320" />
</p>

# telescope

[![JVM 21+](https://img.shields.io/badge/JVM-21%2B-brightgreen.svg?&logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![Build](https://github.com/eschizoid/telescope/actions/workflows/ci.yaml/badge.svg)](https://github.com/eschizoid/telescope/actions/workflows/ci.yaml)
[![Codecov](https://codecov.io/gh/eschizoid/telescope/graph/badge.svg?token=a235ea8b-e6dc-45c6-8fea-e5050940c5d4)](https://codecov.io/gh/eschizoid/telescope)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.eschizoid/telescope-core.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.eschizoid/telescope-core)
[![Javadoc](https://javadoc.io/badge2/io.github.eschizoid/telescope-core/javadoc.svg?color=purple)](https://javadoc.io/doc/io.github.eschizoid/telescope-core)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

**One typed mapping layer for the whole request path: JSON, service and entity.**

Every Java service carries the same data in three shapes: the JSON a REST endpoint reads and writes, the types the
service works with, and the entities it persists. Today each crossing takes a different tool. A JSON library covers the
wire with its own annotations. A mapper generator covers DTOs, with field names written as strings. Nothing covers the
rest, so every team writes it by hand: the PATCH that must change only what the client sent, the entity update that must
not replace a managed collection, the response that must not load a lazy one.

Telescope covers all three crossings with one model. You write the relation between two types once, as an ordinary Java
expression made of method references. The compiler checks every field it names, and an annotation processor turns it
into plain Java that runs in the time of hand-written code. That one definition then does every job the pair needs: it
converts in both directions, applies a partial update, updates a managed entity in place, maps the REST request and
response, and documents the API.

The example is a small order service: two JPA entities written with Lombok, the body a PATCH sends, and the DTO the
client receives.

<!-- prettier-ignore -->
```java
@Entity @Getter @Setter
public class Order {
  @Id @GeneratedValue private Long id;
  private String number;
  @ManyToOne(fetch = FetchType.LAZY) private Customer customer;
  private Delivery delivery;
  private String internalNotes; // stays inside the service
  @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<OrderLine> lines = new ArrayList<>();

  public void addLine(OrderLine line) { line.setOrder(this); lines.add(line); } // sets the back-reference
}

@Entity @Getter @Setter
public class OrderLine {
  @Id @GeneratedValue private Long id;
  private String sku;
  private int quantity;
  private BigDecimal unitPrice;
  private BigDecimal costPrice; // what the shop paid, stays inside the service
  @ManyToOne private Order order;
}

public record OrderChanges(Delivery delivery, List<LineDto> lines) {} // a field the client leaves out is null

public record OrderDto(String number, String customerName, Delivery delivery,
                       List<LineDto> lines, BigDecimal total) {}
public record LineDto(String sku, int quantity, BigDecimal unitPrice) {} // a line on the wire, both ways
```

Three mapper fields say how those types relate, and the PATCH handler uses them:

```java
import static io.github.eschizoid.telescope.Telescope.*; // edits, mapperForward, path
import static io.github.eschizoid.telescope.mapping.Mapping.*; // every row: to, via, compute, service, drop, keep

public final class OrderMappers {

  // A PATCH: how an OrderChanges is written into an Order.
  @Bridge
  public static final EditMapper<OrderChanges, Order> EDITS = edits(
    OrderChanges.class,
    Order.class,
    keep(Order::getNumber), // the fields a PATCH may never change
    keep(Order::getCustomer),
    keep(Order::getInternalNotes)
  );

  // A line as a PATCH sends it, which EDITS finds by its pair. The price and the cost come from the catalog bean.
  @Bridge
  public static final EditMapper<LineDto, OrderLine> LINE = edits(
    LineDto.class,
    OrderLine.class,
    compute(OrderLine::getUnitPrice, service(Catalog::price)), // catalog.price(line)
    compute(OrderLine::getCostPrice, service(Catalog::cost)),
    drop(LineDto::unitPrice) // a price the client sends is ignored
  );

  // The response: what a client sees of an Order. @TelescopePatchMapping(to = OrderDto.class) finds it by its pair.
  @Bridge
  public static final ForwardMapper<Order, OrderDto> DTO = mapperForward(
    Order.class,
    OrderDto.class,
    to(path(Order::getCustomer, Customer::getName), OrderDto::customerName),
    compute(OrderDto::total, Pricing::total) // Pricing.total(order) sums the lines
  );
}

@Service // @ApplicationScoped on Quarkus
@RequiredArgsConstructor
public class Catalog {
  private final ProductRepository products;

  public BigDecimal price(LineDto line) { return products.bySku(line.sku()).getPrice(); }
  public BigDecimal cost(LineDto line) { return products.bySku(line.sku()).getCost(); }
}

@RestController @RequiredArgsConstructor
class OrderController {
  private final OrderRepository orders;
  private final EditMapper<OrderChanges, Order> edits; // OrderMappers.EDITS, bound to the Catalog bean

  @TelescopePatchMapping(value = "/orders/{number}", to = OrderDto.class) // the response goes through OrderMappers.DTO
  Order change(@PathVariable String number, @RequestBody OrderChanges changes) {
    final var order = orders.findByNumber(number); // the entity JPA manages
    edits.patchInto(order, changes); // writes only the fields the client sent, pricing each line it sends
    return order;
  }
}
```

That is the whole PATCH endpoint. Lombok writes the getters and setters the rows name, and the constructors. An edit
mapper's rows read from what the client sends and write into the entity, so `compute` runs on the way in:
`service(Catalog::price)` is `catalog.price(line)` on the `Catalog` bean, which looks the product up by the line's SKU,
for every line the client sends. The mapper fields stay `static final` declarations of rows. The starter registers each
field whose rows call a service as a bean of the field's own type, bound to the application's beans once at startup, so
the controller takes `EditMapper<OrderChanges, Order>` the way it takes its repository; with no `Catalog` bean, the
application fails to start, naming the field, the row and the service. `EDITS` names no row for the lines: the pair
`LineDto` to `OrderLine` has its own edit mapper, `LINE`, declared in the same class, so each line goes through it,
before or after `EDITS`, and `EDITS` needs the `Catalog` `LINE` calls. An element pair's own edit mapper is found in the
owner's class or in the pair's two classes; one declared anywhere else is named in a `via(...)` row, and the processor
refuses it otherwise. The delivery and the lines change, and a field the client left out keeps its value. The new lines
go through `order.addLine(...)`, the method `Order` declares to take an `OrderLine`, into the `@OneToMany` list the
entity already holds, so Hibernate keeps tracking it and each line gets its back-reference. No row says so: the mapper
finds the adder by its type, and with `telescope-jpa` the generated `@Id`s and the `mappedBy` back-reference need no row
either. The response is built by `DTO`, so it carries the customer's name and a computed total, and never the cost price
or the internal notes. The annotation finds `DTO` by its pair, `Order` to `OrderDto`, when the application starts, and
startup fails if no mapper or two mappers claim it. Every field is a method reference, so a typo is a compile error, and
a field added to `Order`, `OrderLine`, `OrderChanges` or `LineDto` later fails the build until someone decides what an
edit does with it. The `telescope-codegen` processor compiles every mapper into plain Java with no reflection; the
generated bridge takes the catalog as a parameter and calls it directly. A test with no container binds the mapper
itself: `OrderMappers.EDITS.with(new Catalog(products))`.

## What you stop writing

| Today, by hand or with a second tool                                    | With telescope                                                                          |
| ----------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| a PATCH handler full of `if (x != null) entity.setX(x)`                 | `edits.patchInto(entity, changes)`                                                      |
| clearing and refilling a `@OneToMany` list, setting each back-reference | the same call, through the entity's own `addLine`                                       |
| a `LazyInitializationException` when a mapper touches a lazy collection | an unloaded collection is never read                                                    |
| deleting and reinserting every child row to change one of them          | with ids on the elements, the changed row gets one UPDATE and the others are left alone |
| a controller that converts every return value to a DTO                  | `@TelescopeGetMapping(to = OrderDto.class)` on a handler returning the entity           |
| a 200 full of nulls because an annotation names the wrong DTO           | the application fails at startup, with the method named                                 |
| field names as strings in mapper annotations                            | method references the compiler checks and the IDE renames                               |
| a reflective mapper for convenience, a generated one for speed          | one mapper that runs both ways, tested to give the same result                          |

## What one mapper gives you

A hand-written converter does one thing, which is to turn an `Order` into an `OrderDto`. A generated mapper method does
the same. When the service later needs the reverse direction, a PATCH, an update of the entity it already holds, or the
same conversion at the REST layer, each one is more code, and each copy has to be kept in step with the others by hand.

A telescope mapper is a list of typed rows that the library can read, so one field answers every question a service asks
about that pair of types.

| From one mapper field                                            | You get                                                                                                                          |
| ---------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `forward` and `backward`                                         | both directions from the same rows, so there is no second mapper to keep in step                                                 |
| an edit mapper's `patchInto` and `patch`                         | a partial update that writes only what the client sent, with computed values run on the way in                                   |
| `into`                                                           | an update of the entity JPA already manages, with its collections refilled in place or merged by `@Id`                           |
| `@TelescopeGetMapping(to = ...)`, `@TelescopeResponse(to = ...)` | request and response bodies mapped on Spring MVC and Quarkus REST, and the wire type in the OpenAPI document                     |
| `TelescopeModule`                                                | Jackson reading and writing the domain type in its wire shape                                                                    |
| the processor and the verifier                                   | plain Java with no reflection, and a build that fails when a two-way mapper's type gains a field and nobody has said how it maps |
| `explain()` and `trace(input)`                                   | the mapper's own account of which fields it pairs, converts or skips                                                             |

All of them read the same rows, so they cannot drift apart. When you rename a field in the IDE, the conversion, the
partial update and the response body follow, or the build fails.

## One request, three hops

A client changes an order that has not been paid. The body names only what changes: the delivery, and a new list of
lines.

```bash
curl -X PATCH localhost:8080/orders/SO-1001 -H 'Content-Type: application/json' \
  -d '{"delivery":"EXPRESS","lines":[{"sku":"BAG-04","quantity":3}]}'
```

The request crosses the REST layer, the service and the entity, and the response crosses them again on the way out. The
code at the top does all three, and this section takes them one at a time.

### In: the handler takes the type the service works with

Spring's Jackson reads the body into `OrderChanges`. The handler returns the entity, and `@TelescopePatchMapping` names
the type the client receives. On Quarkus REST the resource method is the same, with `@TelescopeResponse` beside the
JAX-RS annotations:

```java
@PATCH
@Path("/{number}")
@TelescopeResponse(to = OrderDto.class)
public Order change(@PathParam("number") String number, OrderChanges changes) {
  return desk.change(number, changes);
}
```

Every mapped method is resolved when the application starts. Two mappers for one pair, a mapper that cannot be resolved,
or a DTO that shares no property name with the type it maps fails startup with the method named. With springdoc or
SmallRye OpenAPI on the classpath, the API document shows `OrderDto`, the type the client receives, and not the entity.
When the wire type differs from the type the service works with, `@TelescopeRequestBody(from = PlaceOrderV2.class)`, on
either framework, reads and validates the body as the wire type and maps it before the handler runs.

[`telescope-jackson`](jackson/README.md) works inside Jackson in both directions. `@FromJson` generates a binder for
JSON that comes from outside and has to be checked strictly: it reads the text straight into a record with no
reflection, honours Jackson's own annotations, and refuses a wrong value by its path and position. `TelescopeModule`
makes Jackson read and write a domain type in its wire shape, through a mapper you name.

```java
@FromJson
public record ImportedOrder(@JsonProperty("customer_email") String customerEmail, List<ImportedLine> lines) {}

public record ImportedLine(String sku, String quantity) {} // the feed sends every value as text

ImportedOrder imported = ImportedOrderFromJson.BINDER.read(body);

// a quantity sent as a number: 'lines[0].quantity' got 2, not a String, at line 1, column 74

JsonMapper json = JsonMapper.builder().addModule(TelescopeModule.of(CustomerCards.CARDS)).build();
// json.writeValueAsString(customer) writes the customer as its CustomerCard
```

### Through: the service changes the managed entity in place

`edits.patchInto(order, changes)` stands in for the code every service writes by hand:

```java
if (changes.delivery() != null) order.setDelivery(changes.delivery());
if (changes.lines() != null) {
  order.getLines().clear(); // never setLines(...): Hibernate tracks this list
  for (final var line : changes.lines()) order.addLine(catalog.line(line)); // priced from the catalog bean
}
// and one more branch for each field anyone adds later, if they remember
```

With `telescope-jpa` on the classpath, mapping an entity never loads a lazy collection and never throws
`LazyInitializationException` for one, and a lazy Hibernate proxy is read as the entity it stands for. Both starters
bring it.

The lines in this example carry no id, so a PATCH replaces them. When the elements a client sends do carry their `@Id`,
`telescope-jpa` merges the collection: an element that did not change issues no SQL, a changed one issues exactly one
UPDATE, and only a removed or a new element costs a DELETE or an INSERT. A repeated id, or a generated id the entity
does not hold, is refused before anything is written.

### Out: the entity leaves as the public DTO

The starter maps the entity the handler returned through `DTO`, where Spring MVC writes the body. The client receives
the new lines and the recomputed total, with the customer's name read from another entity:

```text
{"number":"SO-1001","customerName":"Ada Lovelace","delivery":"EXPRESS",
 "lines":[{"sku":"BAG-04","quantity":3,"unitPrice":15.00}],"total":45.00}
```

The code is condensed from [`examples/demo/`](examples/demo/README.md), an order service that runs on Spring Boot, on
Quarkus and as a GraalVM native image from the same mappers. Its README walks the rest of the service the same way: an
older API version the mobile app still calls, a partner feed where every value is text, and an invoice built from
several objects. To try it, run `./gradlew :examples:demo:spring:bootRun` and send the PATCH above.

| Hop                       | What telescope does there                                                                                                                                          | Guide                                                                                                                      |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------- |
| REST endpoint and service | maps response and request bodies on Spring MVC and Quarkus REST, documents the wire type in OpenAPI, binds JSON to a type, registers mappers as Jackson converters | [Spring Boot starter](spring-boot-starter/README.md), [Quarkus](quarkus/README.md), [telescope-jackson](jackson/README.md) |
| Inside the service        | typed rows on a mapper field, run as `forward`, `backward`, `patch` or `into`                                                                                      | [docs/type-conversion.md](docs/type-conversion.md), [docs/codegen.md](docs/codegen.md)                                     |
| Service and entity        | `into` and `patchInto` update a managed entity in place, merge its collections by `@Id`, and never load what is lazy                                               | [docs/pojos.md](docs/pojos.md), [telescope-jpa](jpa/README.md)                                                             |

## A mapper names only what differs

A mapper is a `static final` field on any class, often the type it converts.

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

Fields with the same name on both sides map without a row, nested records included. The generated conversion calls
`id()`, `customer().email()` and `Money.format(invoice)` directly. Leave the processor off the build and the same field
runs the same rows at run time instead, with the same result.

Each kind of difference between the two types has one row.

| What differs                                | Row                                                              |
| ------------------------------------------- | ---------------------------------------------------------------- |
| a field has another name                    | `to(Order::customerName, OrderDto::fullName)`                    |
| a field has another type                    | `to(Order::placedAt, OrderDto::placedOn, IsoDate.class)`         |
| a field lives inside a nested object        | `to(path(Order::customer, Customer::city), OrderDto::city)`      |
| a field is built from the source            | `compute(OrderDto::label, Labels::of)`                           |
| a field is the same literal every time      | `constant(OrderDto::tenant, "production")`                       |
| a field falls back to a default             | `toOrElse(Order::region, OrderDto::region, "EMEA")`              |
| a date or number is written as text         | `toFormatted(Order::shipDate, OrderDto::shipDate, "dd.MM.yyyy")` |
| a field converts through a mapper you wrote | `via(Order::lines, OrderDto::lines, LineMapper.class)`           |
| a source field is left out                  | `drop(Order::internalNote)`                                      |

`Telescope.mapper(...)` returns a two-way `Mapper`. It runs its rows in reverse on `backward`, and it refuses, when it
is built, a field on either side that no row and no same-named field accounts for. `Telescope.mapperForward(...)`
returns a one-way `ForwardMapper`, which leaves a target field it can't fill at its null value. `Telescope.edits(...)`
returns an `EditMapper`, whose rows run from what an edit sends into what it writes: it is strict on both sides,
`keep(...)` names a target field no edit writes, and it has no `backward`. All three take the same rows.

| The job                                                                   | The factory     |
| ------------------------------------------------------------------------- | --------------- |
| what a client sees of an entity or a service value                        | `mapperForward` |
| two types that convert both ways, where the round trip is the point       | `mapper`        |
| writing into an entity or another target, new or held: create, PUT, PATCH | `edits`         |

| Call                           | What it does                                                        |
| ------------------------------ | ------------------------------------------------------------------- |
| `forward(source)`              | builds the target from the source                                   |
| `backward(target)`             | builds the source from the target, the same rows run in reverse     |
| `patch(base, partial)`         | returns a new object with only the fields the partial sets          |
| `into(existing, source)`       | writes the source into an object you already hold                   |
| `patchInto(existing, partial)` | on an `EditMapper`, writes into it only the fields the partial sets |

A mapper is a value you can hold, pass around and ask questions of. `explain()` lists the fields it pairs, converts or
skips, and `trace(input)` shows the same for one input, with its values.
[docs/type-conversion.md](docs/type-conversion.md) covers every row and [docs/introspection.md](docs/introspection.md)
covers both questions.

## Generated or at run time

The same mapper field runs two ways, and the call site is the same either way.

- **Generated.** With `telescope-codegen` on the annotation processor path, `@Bridge` on a `static final` mapper field
  makes the processor compile the field's rows into plain Java. The field then runs that code.
- **At run time.** Without the processor, or without `@Bridge`, the field builds the mapper from its rows when the class
  loads. Nothing is generated and no build step is needed.

You can start with the runtime mapper and add `@Bridge` where a hot loop needs the speed. A test suite runs the same
inputs through both and fails when they give different results, and a compile-time verifier reports at build time what
the runtime mapper would refuse.

The processor also writes a `<Type>Mappings` interface for each type a `@Bridge` field converts, so a type that
implements it converts itself:

```java
public record Order(Long id, String customer) implements OrderMappings {
  @Bridge
  static final Mapper<Order, OrderDto> DTO = mapper(Order.class, OrderDto.class, to(Order::id, OrderDto::number));
}

OrderDto dto = order.toOrderDto(); // Order.DTO.forward(order)
```

[docs/codegen.md](docs/codegen.md) has the rules.

## Performance

A generated mapper runs in MapStruct's time and allocates what MapStruct allocates. The runtime mapper, which needs no
build step, adds a few nanoseconds per call.

| Shape                             | MapStruct | telescope, generated | telescope, runtime |
| --------------------------------- | --------: | -------------------: | -----------------: |
| flat, 5 fields, forward           |   4.56 ns |      4.58 ns (1.00×) |     8.16 ns (1.8×) |
| one nested record, forward        |   6.99 ns |      7.03 ns (1.01×) |     9.35 ns (1.3×) |
| three levels with lists, forward  |   53.6 ns |      50.4 ns (0.94×) |    64.3 ns (1.20×) |
| three levels with lists, backward |   56.6 ns |      49.9 ns (0.88×) |    63.4 ns (1.12×) |
| a 100-entry `Set` field, forward  |  1,213 ns |     1,197 ns (0.99×) |   1,293 ns (1.07×) |
| a 100-entry `Map` field, forward  |  1,251 ns |     1,271 ns (1.02×) |   1,343 ns (1.07×) |

The figures are JMH results from CI against MapStruct 1.6.3 on JDK 25.
[docs/perf-mapstruct-comparison.md](docs/perf-mapstruct-comparison.md) has the method, every run and its controls, and
the [`Benchmarks`](.github/workflows/benchmarks.yaml) workflow reproduces them.

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
processor checks those strings when it compiles, but a plain IDE rename doesn't update them. Telescope keeps the mapping
out of the annotation, so every field is a method reference the compiler checks.

MapStruct still covers a few cases telescope doesn't, such as `@SubclassMapping` across an open class hierarchy rather
than a sealed one. The [full comparison](docs/mapstruct-comparison.md) and the
[migration guide](docs/mapstruct-migration.md) cover both tools in detail, and
[docs/mapstruct-parity.md](docs/mapstruct-parity.md) scores every MapStruct feature against telescope's equivalent.

## Install

```kotlin
// Gradle (Kotlin DSL)
dependencies {
  implementation("io.github.eschizoid:telescope-core:2.3.0")
  annotationProcessor("io.github.eschizoid:telescope-codegen:2.3.0") // optional: generated mappers
}
```

```xml
<!-- Maven -->
<dependency>
  <groupId>io.github.eschizoid</groupId>
  <artifactId>telescope-core</artifactId>
  <version>2.3.0</version>
</dependency>
```

On Maven, the processor goes in the compiler plugin's `annotationProcessorPaths`; [docs/codegen.md](docs/codegen.md) has
that setup. Everything is on Maven Central under `io.github.eschizoid`, and you add a module for each layer you want
telescope to reach.

| Artifact                        | Add it for                                                                                                                               | Guide                                  |
| ------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------- |
| `telescope-core`                | mappers, typed paths and the annotations; the one dependency you always add                                                              | [docs/](docs/type-conversion.md)       |
| `telescope-codegen`             | the annotation processor behind `@Bridge`, `@FromJson`, `@FromMap` and the mapper verifier                                               | [docs/codegen.md](docs/codegen.md)     |
| `telescope-spring-boot-starter` | mapped responses and request bodies on Spring MVC, a mapper registry, generated mapper beans                                             | [guide](spring-boot-starter/README.md) |
| `telescope-quarkus`             | mapped responses and request bodies on Quarkus REST, a mapper registry, generated mapper beans; it brings `telescope-quarkus-deployment` | [guide](quarkus/README.md)             |
| `telescope-jackson`             | JSON read straight into a type, and mappers as Jackson 3 converters                                                                      | [guide](jackson/README.md)             |
| `telescope-jpa`                 | lazy collections, proxies and merge by `@Id` on JPA entities; both starters bring it                                                     | [guide](jpa/README.md)                 |
| `telescope-lombok`              | typed navigators for Lombok `@Data`, `@Value` and `@Builder` classes                                                                     | [guide](lombok/README.md)              |

The startup refusal of an endpoint whose types share no property name, and the OpenAPI document on Quarkus REST, arrive
with the release after 2.3.0. Everything runs in a GraalVM native image, and generated mappers and binders need no
reflection configuration there; [docs/native-image.md](docs/native-image.md) has the details.

## Typed paths, the part underneath

A mapper is built from paths, and a path works on its own too. A path points at data anywhere inside a nested structure.
You can read through it, or rebuild the whole structure with one change, without mutating anything.

```java
static final Telescope<Company, String> EMAILS = Telescope.of(Company.class)
  .each(Company::departments)
  .each(Department::teams)
  .each(Team::users)
  .field(User::email);

Company lowered = EMAILS.update(company, String::toLowerCase); // a new Company, every email lowercased

List<String> all = EMAILS.toList(company);
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

Paths also go through `Optional`, `Map` values and sealed interfaces, work on POJOs through `Telescope.ofBean(...)`, and
run an update inside a `CompletableFuture`, `Optional`, `Either` or `Validated` result.
[docs/navigation.md](docs/navigation.md) and [docs/effects.md](docs/effects.md) have the details.

## Documentation

| Layer                     | Guide                                                                  | Covers                                                                        |
| ------------------------- | ---------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| REST endpoint and service | [spring-boot-starter/README.md](spring-boot-starter/README.md)         | mapped responses and request bodies, springdoc, the registry and beans        |
|                           | [quarkus/README.md](quarkus/README.md)                                 | mapped responses and request bodies, SmallRye OpenAPI, the registry and beans |
|                           | [jackson/README.md](jackson/README.md)                                 | JSON straight into a type, `@FromJson`, mappers as Jackson converters         |
| Inside the service        | [docs/type-conversion.md](docs/type-conversion.md)                     | mappers, rows and structural conversion                                       |
|                           | [docs/codegen.md](docs/codegen.md)                                     | `@Bridge`, `<Type>Mappings`, `@FromMap`, navigators, Lombok and JPMS          |
|                           | [docs/introspection.md](docs/introspection.md)                         | `explain()`, `trace()` and logging                                            |
|                           | [docs/navigation.md](docs/navigation.md)                               | every path operation, containers, sealed cases, filters and multi-edit        |
|                           | [docs/effects.md](docs/effects.md)                                     | updates inside async, validated, either and optional results                  |
| Service and entity        | [docs/pojos.md](docs/pojos.md)                                         | beans, write strategies, `into`, adders and back-references                   |
|                           | [jpa/README.md](jpa/README.md)                                         | lazy collections, proxies and merge by `@Id` on JPA entities                  |
| Coming from MapStruct     | [docs/mapstruct-comparison.md](docs/mapstruct-comparison.md)           | the two tools side by side                                                    |
|                           | [docs/mapstruct-migration.md](docs/mapstruct-migration.md)             | moving one mapper at a time, and running both together                        |
|                           | [docs/mapstruct-parity.md](docs/mapstruct-parity.md)                   | every MapStruct feature and its telescope equivalent                          |
| Running it                | [docs/perf-mapstruct-comparison.md](docs/perf-mapstruct-comparison.md) | benchmark method, environment and every result                                |
|                           | [docs/native-image.md](docs/native-image.md)                           | what a GraalVM native image needs                                             |

The design decisions are recorded in [docs/adr/](docs/adr/).

## Constraints

- **Method references, not lambdas.** `.field(User::name)` works and `.field(u -> u.name())` is refused, because a
  lambda carries no field name. `.fieldByName(String)` is the late-bound escape hatch, and its name says so.
- **Exact matching.** Fields pair by exact name, with no fuzzy matching. Without a row, a field converts between types
  that hold the same value: a primitive and its wrapper, an `Optional` and a nullable value, nested records or beans,
  containers whose elements convert, enums with the same constant names, and a `String` against a number, an enum or a
  boolean. Narrowing, and text against a date or time, take a row.
- **Records and JavaBeans-style POJOs.** Records rebuild through the canonical constructor. POJOs rebuild through a
  builder, an all-args constructor or setters, in that order, and `writeBean(...)` picks one.
- **Nulls are uniform.** A null in the middle of a path focuses nothing, and a mapper maps `null` to `null`.

## Build and test

```bash
./gradlew build   # every module, the starters and the examples
./gradlew check   # what CI runs: every test suite, the formatting gate, and the suites rerun on the native-image code path
```

Telescope's classes target Java 21, and CI builds every module on JDK 25.

## License

Telescope is licensed under Apache 2.0. See [LICENSE](LICENSE).
