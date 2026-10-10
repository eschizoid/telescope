# telescope and MapStruct side by side

The `mapstruct-vs-telescope` module writes the same `Order` to `OrderDto` mapping with MapStruct 1.6.3 and with
telescope, so you can run both and compare them. Run the tests with this command:

```bash
./gradlew :examples:mapstruct-vs-telescope:test
```

The source side is immutable records and the target side is mutable JavaBeans, each with a no-argument constructor and
setters. The domain has a nested object, a list, and one field whose name differs between source and target, which is
`Customer.email` to `CustomerDto.contactEmail`. Every other field has the same name on both sides.

| Source record                                               | Target bean                                   |
| ----------------------------------------------------------- | --------------------------------------------- |
| `Order(String id, Customer customer, List<LineItem> lines)` | `OrderDto` with `id`, `customer`, `lines`     |
| `Customer(String name, String email)`                       | `CustomerDto` with `name`, `contactEmail`     |
| `LineItem(String sku, int quantity, BigDecimal price)`      | `LineItemDto` with `sku`, `quantity`, `price` |

MapStruct is configured the usual way in `OrderMapStructMapper`, and the first test checks that both libraries produce
equal `OrderDto` values for the same input. The tests log what each one shows through `System.Logger`, so the test
output reads as a walkthrough.

## The mappings

Both libraries need the one renamed field spelled out, and both map the nested customer, the list, and the same-named
fields without more configuration. Telescope names the field with method references in `TelescopeMappings`:

```java
public static final Mapper<Order, OrderDto> ORDER_MAPPER = Telescope.mapper(
  Order.class,
  OrderDto.class,
  to(Customer::email, CustomerDto::getContactEmail)
);
```

MapStruct names it with strings in `OrderMapStructMapper`:

```java
@Mapper
public interface OrderMapStructMapper {
  OrderMapStructMapper INSTANCE = Mappers.getMapper(OrderMapStructMapper.class);

  OrderDto toDto(Order order);

  @Mapping(source = "email", target = "contactEmail")
  CustomerDto toDto(Customer customer);

  LineItemDto toDto(LineItem line);
}
```

The telescope mapper also converts in the other direction. A test checks that `ORDER_MAPPER.backward(...)` applied to
the result of `ORDER_MAPPER.forward(order)` gives back an `Order` equal to the original. MapStruct needs a second method
for the reverse direction, usually with `@InheritInverseConfiguration`.

## Renaming a source field

Both libraries fail the build when a source field named in a mapping goes away, and they differ in who updates the name.
`Customer::email` is a method reference, so `javac` checks it and your IDE's rename refactoring updates it with the
record component. The `"email"` in `@Mapping(source = "email", ...)` is a string, which MapStruct's processor checks at
compile time. The [MapStruct IDEA plugin](https://mapstruct.org/documentation/ide-support/) refactors such strings
according to its documentation. A plain rename in the editor doesn't change them.

To reproduce the MapStruct side, follow these steps:

1. Rename the `email` component of `Customer` to `emailAddress`.
2. Change both `Customer::email` references in `TelescopeMappings` to `Customer::emailAddress`. An IDE rename does both
   steps at once.
3. Leave the `@Mapping(source = "email", ...)` strings as they are, and run
   `./gradlew :examples:mapstruct-vs-telescope:compileJava`.

The telescope code compiles. MapStruct reports one error for each mapper method whose `@Mapping` names `email`, which is
three in this module (`OrderMapStructMapper`, `SilentDropMapper`, and `StrictPolicyMapper`). Each error reads like this:

```text
OrderMapStructMapper.java:38: error: No property named "email" exists in source parameter(s). Did you mean "name"?
  @Mapping(source = "email", target = "contactEmail")
                    ^
```

Without the plugin, you fix each of those strings by hand. A compile failure can't be a passing test, so the module
documents the rename as a manual step rather than testing it.

## A target field with no source

MapStruct leaves a target property with no source as `null` under its default `unmappedTargetPolicy`, which is `WARN`.
`SilentDropMapper` maps `Customer` to `CustomerContactDto`, whose `region` property has no counterpart on `Customer`.
The module compiles with this warning:

```text
SilentDropMapper.java:25: warning: Unmapped target property: "region".
  CustomerContactDto toContactDto(Customer customer);
                     ^
```

The test `mapStructSilentlyDropsUnmappedTarget` checks that `region` is `null` at run time. `StrictPolicyMapper` shows
the stricter setup. It sets `unmappedTargetPolicy = ReportingPolicy.ERROR`, which makes the same mapper fail to compile
until the drop is written out as `@Mapping(target = "region", ignore = true)`.

Telescope's `Telescope.mapper(...)` is strict by default. It throws when the mapper is built if a target field has no
source, and you give that field a value with a row such as `to(...)`, `constant(...)`, or `compute(...)`. The two
libraries reach the same safety, and the difference is the default. With `telescope-codegen` on the annotation processor
path, a mapper verifier also reports the same message as a compile error for each `Telescope.mapper(...)` call whose
types it can read. The `mapstruct-vs-telescope` module doesn't put `telescope-codegen` on its processor path, so here
the check happens when the mapper is built. The root README's
[unconvertible fields section](../../README.md#unconvertible-fields-are-refused) shows the message.

## Updating values inside an immutable graph

Telescope uses the same vocabulary to update values inside an `Order` as it uses to map one. `TelescopeMappings` holds a
path to every line item's price and uses it to multiply each price by a rate:

```java
public static final Telescope<Order, BigDecimal> LINE_PRICES = Telescope.of(Order.class)
  .each(Order::lines)
  .field(LineItem::price);

public static Order applyRate(final Order order, final BigDecimal rate) {
  return LINE_PRICES.update(order, (price) -> price.multiply(rate));
}
```

`update` returns a new `Order` with new `LineItem` records and leaves the original unchanged. The test
`deepUpdateRebuildsImmutably` checks both, with a rate of 2:

```text
before: [LineItem[sku=sku-1, quantity=2, price=10.00], LineItem[sku=sku-2, quantity=1, price=5.00]]
after:  [LineItem[sku=sku-1, quantity=2, price=20.00], LineItem[sku=sku-2, quantity=1, price=10.00]]
```

MapStruct converts one type to another and has no API for this kind of update. Its `@MappingTarget` methods write into
an existing mutable object in place, so they can't return a new record graph with the original untouched.

## Inspecting a mapper

A telescope mapper can describe its own structure with `explain()` and show the values of one conversion with
`trace(input)`. To see the same things in MapStruct, you read the generated `OrderMapStructMapperImpl` source or step
through it in a debugger.

`TelescopeMappings.CUSTOMER_MAPPER` maps `Customer` to `CustomerDto` with the same `to(...)` row, so its `explain()`
lists the renamed field as a top-level row. The test asserts that `explain().mapped()` contains the pair `email` and
`contactEmail`. The test prints this:

```text
Mapped:
  ✓ email → contactEmail
  ✓ name  → name
```

`ORDER_MAPPER.trace(order)` shows each field of one conversion with its source and target values:

```text
✓ id        "o-1"                                                                                       → id "o-1"
• customer  Customer[name=Ada, email=ada@example.com]                                                   → customer CustomerDto[name=Ada, contactEmail=ada@example.com]
• lines     [LineItem[sku=sku-1, quantity=2, price=10.00], LineItem[sku=sku-2, quantity=1, price=5.00]] → lines [LineItemDto[sku=sku-1, quantity=2, price=10.00], LineItemDto[sku=sku-2, quantity=1, price=5.00]]
```

Every mapper also logs both reports through `java.lang.System.Logger`, so you can turn them on with a log level instead
of a code change. It logs `explain()` at `DEBUG` once, when the mapper is built, and `trace(input)` at `TRACE` on every
`forward(...)`. The logger for a mapper is named `io.github.eschizoid.telescope.mapper.<Source>.<Target>`, with simple
class names. With the JDK's default backend, `System.Logger` writes to `java.util.logging`, where `TRACE` maps to
`FINER`. The handler needs a low enough level as well, as in this `logging.properties`:

```properties
handlers = java.util.logging.ConsoleHandler
java.util.logging.ConsoleHandler.level = FINER
io.github.eschizoid.telescope.mapper.Order.OrderDto.level = FINER
```

[docs/introspection.md](../../docs/introspection.md) covers the Spring Boot and Logback settings.

## When MapStruct is the right pick

MapStruct is the better fit in some cases, and the root README's
[comparison](../../README.md#when-mapstruct-is-the-right-pick) lists them:

- You need mapping bodies written in an embedded expression language, such as `@Mapping(expression = "java(...)")`, or
  qualifier dispatch. Telescope takes plain Java mappers passed to `Mapping.via(...)` instead.
- You need `@SubclassMapping` across hierarchies that are open rather than sealed. Telescope's `Match` covers sealed
  roots.
- Conversion is the whole job, with no path reuse or deep updates, and your team treats readable generated mapper source
  as a feature.
