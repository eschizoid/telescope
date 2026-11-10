# Telescope and MapStruct, in detail

The README keeps the short version. This page keeps the long one: how field names are checked, how the runtime and
generated mappers are held to the same result, what each refuses, the measured performance, and when each tool is the
better pick.

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
Telescope accepts a typed path as the target of a row, written with method references, as in
`to(Cart::customerName, path(CartDto::shipping, Shipping::recipient, Recipient::fullName))`, which needs no processor. A
navigator the `@Focus` annotation processor generates works too, and each of its steps is a method call that `javac`
checks.

```java
Telescope.mapper(Cart.class, CartDto.class,
  to(Cart::customerName, CartDtoTelescope.of().shipping().recipient().fullName()));
```

`@Bridge` has the `telescope-codegen` processor generate a mapper at compile time. Put on a mapper field, it names no
field at all: the processor compiles the rows of the field's `Telescope.mapper(...)` call, so the generated mapper is
checked by the same method references. Put on a class, it names only the types of a pair whose properties are matched by
name. `@FromMap(required = ...)` takes strings, which name map keys.

The [head-to-head module](../examples/mapstruct-vs-telescope/) tests MapStruct's default-policy unmapped-target case. A
rename failure can't be a passing test, because the rename has to be applied by hand to show it, so the module describes
the rename as a manual step.

## Runtime and generated mappers are tested to give the same result

Telescope can run the same mapping in two ways. A runtime mapper, built by `Telescope.mapper(...)`, does the conversion
while the program runs, with no annotations and no build step. Putting `@Bridge` on the field that holds the mapper
makes the `telescope-codegen` annotation processor compile that call's rows into plain Java, which is a generated
mapper. The field then runs the generated code, and the call site does not change. You can start with a runtime mapper
and add `@Bridge` later, when that code needs the speed.

```java
import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.to;

public record InvoiceDto(Long invoiceId, String customer, String currency, long totalCents) {}

public record Invoice(Long id, String customer, String currency, long totalCents) {
  @Bridge
  static final Mapper<Invoice, InvoiceDto> DTO = mapper(Invoice.class, InvoiceDto.class,
      to(Invoice::id, InvoiceDto::invoiceId),
      to(Invoice::currency, InvoiceDto::currency, CurrencyFn.class)); // CurrencyFn is a BridgeFn<String, String>
}

InvoiceDto dto = Invoice.DTO.forward(invoice); // the generated code when the processor ran
Invoice back   = Invoice.DTO.backward(dto);
Invoice.DTO.explain();                         // "Runs: generated — the bridge InvoiceToInvoiceDtoBridge, ..."
```

If you would rather write `invoice.toDto()`, add a one-line method to the record yourself. It costs one extra method
call over calling the `static final` field, and the DTO can carry the reverse. Telescope can't generate these, because
an annotation processor can't add methods to your record.

```java
public InvoiceDto toDto() {
  return DTO.forward(this);
} // on Invoice

public Invoice toInvoice() {
  return Invoice.DTO.backward(this);
} // on InvoiceDto
```

The field can sit on the source itself, as here, or with other mappers in a class or an interface of their own. A field
of an interface is `static final` without saying so:

```java
interface Mappers {
  @Bridge
  Mapper<Invoice, InvoiceDto> INVOICE = mapper(Invoice.class, InvoiceDto.class, to(Invoice::id, InvoiceDto::invoiceId));

  @Bridge
  Mapper<Customer, CustomerDto> CUSTOMER = mapper(Customer.class, CustomerDto.class);
}
```

### Why mappers work this way here

Java annotations only accept constants, so a mapper configured through annotations has to name its fields as strings.
Telescope keeps the mapping out of the annotation. You write it as an ordinary Java expression of method references and
assign it to a `static final` field, so the compiler checks every reference and an IDE rename carries through. Put
`@Bridge` on that field, and a standard annotation processor (javac only) reads the expression and generates a mapper
with no reflection in it. Leave the processor out, and the same expression runs as a runtime mapper with the same
result. Either way, the call site doesn't change.

The same field without the processor builds the runtime mapper from the same rows, so removing the processor leaves the
field's own results as they were. A mapper elsewhere that meets the pair nested then converts it by its own rules rather
than through the field's bridge, as with a `@Bridge` on a class; a `via` row naming the field holds the same either way.
A row the generated code cannot express is a compile error on that row, naming what to write instead: an inline lambda,
for one, is refused in favour of a method reference such as `String::trim`. The processor reads the rows through the
javac tree API. A compiler without it, such as Eclipse's, reports a warning on each `@Bridge` field and leaves the
mapper on the runtime path, which `explain()` reports.

### How the two paths are checked

Two tests run the same input through both kinds of mapper and fail when they disagree. `CrossPathCorpusTest` covers
container shapes, how a target is constructed, same-typed container copies, object graphs that lead back to an object
being converted, `patch`, and enum pairs. Every bridge it compiles from a `@Bridge` on a class whose declaration a
mapper's rows can express is compiled again from the same declaration written as mapper fields, and the two must
generate the same code. `BeanRebuildCorpusTest` covers the ways a bean target can be built. Both run as part of
`./gradlew check`, which is what CI runs. Null strategies and defaults aren't cross-checked by them.

The tests record each known difference between the two paths, and they fail when a recorded difference goes away, so the
list stays accurate.

### Built at run time, refused by the processor

- A target reachable only through a private constructor is built at run time. The processor refuses it when `@Bridge`'s
  `writeStrategy` asks for setters or for a constructor.
- A container class whose no-argument constructor is package-private or protected is built at run time. The processor
  refuses it when the bridge is generated into a different package from the class, because generated code there can't
  call that constructor.
- A bean whose builder has no method for a `final` field with no initializer is built at run time, and the value is
  lost. The processor refuses it. The runtime can't do the same: in the class file, such a field looks the same as a
  `final` field given a non-constant value where it is declared.

### Built by the generated path, refused at run time

- A bean built through a constructor whose parameters are named after its properties converts on the generated path. The
  runtime mapper matches the arguments by parameter name, which it reads from a class compiled with `-parameters` or
  with `-g`, the debug level Gradle and Maven compile with by default. It refuses the bean compiled with neither, and
  inside a native image it refuses it without `-parameters`.
- A nested pair can carry its own `@Bridge`, on a field with rows of its own or on a class with `lenient = true`. An
  enum pair below it compiles on the generated path. The runtime mapper refuses it unless you give it a row for the
  nested pair.

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
from a default for its type. `Mapper.into(...)` is not lenient: it writes onto an existing bean through its setters, and
refuses by name a property the mapper writes that the bean has no setter for.

Same-typed JDK collections are copied rather than shared, so changing the target's list never changes the source's.
[docs/type-conversion.md](type-conversion.md) has the details.

Some conversions MapStruct makes without being asked are built in on both paths: a `String` and a number, an enum or a
boolean, both ways; a number widened forward on a forward-only mapper; and a list and a set, each into the other's kind.
A `String` that is no valid value fails on that value, naming the property and the value. Narrowing and dates against
text still need a row, and the refusal names it.

## Measured performance

The README's [Measured performance](../README.md#measured-performance) table has the current figures. On Actions run
38008688244, an earlier run, each call shape of the generated mapper ran close to MapStruct's time on every tier.

- Forward, from the source bean to the target record, the `BRIDGE_FN` constant a class-level `@Bridge` generates took
  1.00 to 1.01 times MapStruct's time on flat, 1.02 on nested, and 0.99 on deep.
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
of one ratio. The [methodology, per-fork figures, and earlier runs](perf-mapstruct-comparison.md) are recorded
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
[`Benchmarks`](../.github/workflows/benchmarks.yaml) GitHub Action. The full matrix is in
[`benchmarks/README.md`](../benchmarks/README.md#mapstruct-comparison).

---

## How it compares to MapStruct

MapStruct is a compile-time bean mapper with a mature ecosystem and broad adoption. It converts whole objects, including
nested graphs. It has dotted paths, automatic sub-mapping methods, collections, and builders, for example. Every
comparison here pins MapStruct 1.6.3, which is the version the head-to-head module and the benchmarks build against.

Telescope overlaps MapStruct on mapping and adds reusable typed paths. MapStruct has no way to point at
`company.departments[].address.city` as a value you can hold and read. Telescope can also update through such a value
without mutation, or run the update inside an async or validation result.

The runnable head-to-head module writes the same `Order` to `OrderDto` mapping both ways, in
[`examples/mapstruct-vs-telescope`](../examples/mapstruct-vs-telescope/).

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

| Capability                        | telescope                                                            | MapStruct                                                            |
| --------------------------------- | -------------------------------------------------------------------- | -------------------------------------------------------------------- |
| Bidirectional mapping             | one row list, with `forward(...)` and `backward(...)` on one value   | a second method plus `@InheritInverseConfiguration`, with exclusions |
| Deep nested navigation and update | `of(C).each(C::depts).field(D::address).update(c, fn)`               | not in scope, and `@MappingTarget` mutates in place                  |
| Update inside a result type       | `updateAsync`, `updateOptional`, `updateEither`, `updateValidated`   | not in scope, so pair it with other code                             |
| Accumulating validation           | `Validated.combine(...)` collects every failure in one pass          | not in scope, so pair it with Bean Validation or write it            |
| Reading an untyped map            | `Telescope.fromMap(T.class)`, `extract(...)` to override, `@FromMap` | supported, with the per-key conversion as a separate mapping method  |
| Mapper introspection              | `explain()`, `trace(input)`, or a log level                          | read the generated source, which you can step through                |
| Unmapped-target safety            | strict at construction by default                                    | `WARN` by default, with `ERROR` a one-line opt-in                    |
| Sealed-root dispatch              | `Match.of(...).when(...).exhaustive()`, checked over the permits     | `@SubclassMapping`, broader hierarchies, no sealed check             |
| Multi-source merge, many to one   | `Telescope.merge(Target.class, from(...), ...)`                      | first-class multi-source methods, disambiguated by string            |
| No codegen required               | `Telescope.of(Class)`, with `@Focus` as a later opt-in               | compile-time only                                                    |
| GraalVM native-image              | `@Bridge` code needs no config, and runtime mappers work there too   | fully AOT-compatible for codegen, with no runtime mapper to need it  |

The [coverage matrix](mapstruct-parity.md) scores 29 MapStruct features against telescope. It rates 13 as covered fully
and 16 as covered partially. Each partial row states its limitation, and every verdict cites the source and tests behind
it. The [migration guide](mapstruct-migration.md) turns the matrix into steps you apply one mapper at a time.

### When MapStruct is the right pick

- You need mapping bodies written in an embedded expression language, such as `@Mapping(expression = "java(...)")` or
  qualifier dispatch. Telescope takes a method reference in a `compute(...)` row, or a mapper passed to
  `Mapping.via(...)`, instead.
- You need `@SubclassMapping` across hierarchies that are open rather than sealed. Telescope's `Match` covers sealed
  roots, and the [coverage matrix](mapstruct-parity.md) scores the gap.
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
The [migration guide](mapstruct-migration.md) covers running both side by side.

---
