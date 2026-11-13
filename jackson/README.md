# telescope-jackson

```java
JsonBinder<Order> orders = TelescopeJson.fromJson(Order.class);

Order order = orders.read(body);
```

`telescope-jackson` puts JSON at the edge of a telescope mapping, on Jackson 3. It has two parts:

- `TelescopeJson.fromJson` builds a `JsonBinder`, which reads JSON straight into a record or a bean over
  `jackson-core`'s streaming parser. There is no tree and no `Map` in between, and no databind.
- `TelescopeModule` is a Jackson databind module. For each `Mapper<Wire, Domain>` you give it, Jackson reads a `Domain`
  by reading a `Wire` and calling `forward`, and writes one by calling `backward` and writing the `Wire`.

```kotlin
dependencies {
    implementation("io.github.eschizoid:telescope-jackson:<version>")
}
```

The binder needs `jackson-core` alone, which the module brings. `TelescopeModule` needs `jackson-databind` 3, which an
application that registers a Jackson module already has.

## Reading JSON with `JsonBinder`

Every property reads the JSON key of its own name. Every record or bean outside the JDK that the target holds is read
from a nested object the same way, at any depth, a type that holds itself included. A binder is built once, is safe to
share between threads, and reads from a `String`, a `byte[]` or an `InputStream`; `readList` reads an array of objects.

The rules, per value:

| JSON                               | What the binder does                                                                                          |
| ---------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| a missing key, or a key set `null` | the declared type's default: `0`, `false`, an empty `List`, `Set`, `Map` or `Optional`, otherwise `null`      |
| a key no property reads            | skipped, whatever it holds                                                                                    |
| a key that appears twice           | the last value wins                                                                                           |
| a number                           | read into a numeric property only; an integral property refuses a fraction and an integer past its range      |
| a string                           | read into `String`, an enum constant by its exact name, `char`, or a JDK value type such as `UUID`, `Instant` |
| `true`, `false`                    | read into `boolean` only                                                                                      |
| an array                           | read into a `List` or a `Set`, each element by the same rules                                                 |
| an object                          | read into a nested record or bean, or a `Map` whose key type is read from text                                |

A value is never read from another JSON kind: `"12"` does not become an `int`, and `12` does not become a `String`.
`BigDecimal` and `BigInteger` are read from a number token with every digit kept. The binder refuses what it cannot read
with an `IllegalArgumentException` that names the property, where it is and what it got:

```text
'lines[1].quantity' got 1.5, not an int, at line 3, column 18
```

JSON that does not parse throws `jackson-core`'s own `StreamReadException`, which carries the location.

### Rows

A row overrides one property, of the target or of any type it holds. They are the rows `Telescope.fromMap` takes:

```java
JsonBinder<Booking> bookings = TelescopeJson.fromJson(
  Booking.class,
  extract("booking_type", Booking::bookingType, Object::toString),
  extract("postal_code", Address::zip, Object::toString),
  required("id", Booking::id, Object::toString)
);
```

`extract` reads another key or converts with a function of its own; the function receives the value as an untyped map
would hold it, with a fraction as a `BigDecimal`, and is not called for a missing or `null` value. `required` refuses a
document without the key. A target whose generated `@FromMap` binder declares keys required is held to the same keys.

A type the binder has no reading for, such as a type variable, a collection subtype like `ArrayList`, or an interface,
is refused when the binder is built, naming the property and the row that would read it. `explain()` returns the binding
plan: for each property, the key it reads and whether a missing key defaults or refuses.

`parse(JsonParser)` and `parseList(JsonParser)` read from a parser you own, such as the one a custom deserializer is
handed: a fresh parser is advanced to its first token, and one already on a value reads from there.

## Mappers as Jackson converters with `TelescopeModule`

```java
JsonMapper json = JsonMapper.builder().addModule(TelescopeModule.of(ContactMappers.WIRE, TeamMappers.WIRE)).build();
```

The module carries the mappers it is given and no others. Each direction is Jackson's own `StdConvertingDeserializer` or
`StdConvertingSerializer`, so the wire type is read and written with everything Jackson knows about it. A domain type
maps at every depth, inside a list, a map or another wire type, and a value whose class extends a registered domain
type, such as a persistence proxy, is written through that registration. Jackson never hands a JSON `null` to a
deserializer, so a mapper never sees one. Each `TelescopeModule` registers on its own, so two of them on one builder are
both installed.

`TelescopeModule.of` refuses, with an `IllegalStateException` naming the mapper, each shape Jackson would get wrong
without a word:

- a lifted mapper (`liftList()` and its siblings), which would take over every `List` the JSON mapper meets;
- a mapper between a type and itself, whose reading would never return;
- a wire type that is, or extends, a registered domain type, which would chain two mappers;
- two mappers with the same domain type;
- an enum or primitive domain type, which Jackson reads through a lookup the module takes no part in;
- a domain type carrying `@JsonDeserialize`, `@JsonSerialize`, `@JsonFormat`, `@JsonCreator` or `@JsonValue`, which
  Jackson would settle against the module one way or the other without saying so.

Not supported: a domain type as a `Map` key, and `@JsonUnwrapped` on a domain-typed property.
`convertValue(value, Domain.class)` goes through the module too, so it expects the wire shape.

## Spring Boot

With `telescope-jackson` on the classpath, the Spring Boot starter installs a `TelescopeModule` in Spring Boot's
`JsonMapper`. The module carries only the mappers a `TelescopeModuleCustomizer` bean adds; a `Mapper` bean is not a JSON
converter on its own, because a mapper between an entity and a DTO would otherwise change how every such entity is
written.

```java
@Bean
TelescopeModuleCustomizer wireShapes() {
  return (module) -> module.add(ContactMappers.WIRE);
}
```

A `TelescopeModule` bean of your own replaces the starter's. The mapped request and response bodies,
`@TelescopeGetMapping` and `@TelescopeRequestBody`, work as before: they map between the types they name, and the module
maps only the domain types it carries.

## Quarkus

Quarkus 3 ships Jackson 2 (`com.fasterxml.jackson`), and `TelescopeModule` is a Jackson 3 module, so the Quarkus
extension does not install it. `JsonBinder` needs only Jackson 3's `jackson-core`, whose packages do not overlap Jackson
2's, so it runs in a Quarkus application as it does anywhere; the order-service demo reads its partner import through it
on Quarkus.

## Native image

The runtime binder builds records and beans through the same reflection the runtime `fromMap` uses, so in a native image
the types it builds are registered as yours, and a row's method reference needs serialization configuration. See
[docs/native-image.md](../docs/native-image.md). `jackson-core` itself needs no configuration. A binder reaches no
Jackson object, so one kept in a static field is stored in the image heap safely; a `TelescopeModule` is a Jackson type,
so create it at run time, as a bean is.

The design is recorded in [ADR-0016](../docs/adr/0016-jackson-integration.md).
