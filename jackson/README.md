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

Every property reads the JSON key of its own name, or the key Jackson's `@JsonProperty` gives it (see
[Jackson annotations](#jackson-annotations)). Every record or bean outside the JDK that the target holds is read from a
nested object the same way, at any depth, a type that holds itself included. A binder is built once, is safe to share
between threads, and reads from a `String`, a `byte[]` or an `InputStream`; `readList` reads an array of objects.

The rules, per value:

| JSON                               | What the binder does                                                                                                 |
| ---------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| a missing key, or a key set `null` | the declared type's default: `0`, `false`, an empty `List`, `Set`, `Map` or `Optional`, otherwise `null`             |
| a key no property reads            | skipped, whatever it holds                                                                                           |
| a key that appears twice           | the last value wins                                                                                                  |
| a number                           | read into a numeric property only; a property refuses a number past its type's range, and an integral one a fraction |
| a string                           | read into `String`, an enum constant by its exact name, `char`, or a JDK value type such as `UUID`, `Instant`        |
| `true`, `false`                    | read into `boolean` only                                                                                             |
| an array                           | read into a `List` or a `Set`, each element by the same rules                                                        |
| an object                          | read into a nested record or bean, or a `Map` whose key type is read from text                                       |

A value is never read from another JSON kind: `"12"` does not become an `int`, and `12` does not become a `String`.
`BigDecimal` and `BigInteger` are read from a number token with every digit kept. A `double` or `float` refuses a number
past its range, such as `1E400` for a `double`, rather than read it as infinity. A document holds one value: anything
after it but whitespace is refused, as JSON that does not parse is. The binder refuses what it cannot read with an
`IllegalArgumentException` that names the property, where it is and what it got:

```text
'lines[1].quantity' got 1.5, not an int, at line 3, column 18
```

The path names each property by its Java name. Where a property reads a key other than its name, the path by keys
follows, so the message points at both the code and the payload:

```text
'partner.id' (JSON 'partner_info.partner_id') got 12, not a String, at line 1, column 31
```

JSON that does not parse throws `jackson-core`'s own `StreamReadException`, which carries the location.

### Jackson annotations

The binder reads three of Jackson's annotations, the way databind reads them, so a record annotated for databind reads
the same through the binder:

| Annotation                          | What the binder does                                                                                         |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| `@JsonProperty("partner_id")`       | the property reads `partner_id`; its own name is then a key no property reads                                |
| `@JsonProperty(access = READ_ONLY)` | the property is not read, and takes its default                                                              |
| `@JsonProperty(required = true)`    | on a record component, a document without the key is refused; on a bean property, as in databind, it is not  |
| `@JsonAlias({"partnerId", "pid"})`  | each alias fills the property too; when the key and an alias both appear, the one later in the document wins |
| `@JsonIgnore`                       | the property is not read and takes its default, as for a missing key; the key is skipped                     |

The binder looks for them where databind does. A record component's annotations reach its canonical constructor's
parameter, its field and its accessor, and an accessor declared in the record body can carry its own. A bean property's
are on its field, its getter and its setter. A key given on the field or setter wins over one given on the getter, so
`@JsonProperty("written_as")` on a getter and `@JsonProperty("read_as")` on its setter reads `read_as`. A row names its
property's key itself, over whatever the annotations say.

Whether a property is ignored is decided once for the whole property, as databind decides it:

- A member carrying `@JsonIgnore` is ignored, whatever else it carries. A member carrying `@JsonProperty` and not
  `@JsonIgnore` is explicit.
- A property with an ignored member and no explicit one is ignored. A record component carrying both annotations is
  ignored: its constructor parameter carries `@JsonProperty` alone, since `@JsonIgnore` cannot sit on a parameter, and
  is not counted as explicit.
- An explicit member keeps the property, and its ignored members drop out. A bean property is still ignored when every
  setter it has is ignored, since nothing is left to write it through.

So `@JsonIgnore` on a getter with `@JsonProperty` on the setter reads the property, and the reverse does not;
`@JsonIgnore` on a field with `@JsonProperty("g")` on the getter and a plain setter reads `g` through the setter. Only
members that are not ignored give the key.

The annotations are read once, when the binder is built, and found by class name: the binder needs no
`jackson-annotations` at run time, and without it no annotation is present.

Refused when the binder is built, with an `IllegalArgumentException` naming the properties:

- two properties of one object reading the same key, by their names, `@JsonProperty`, `@JsonAlias` or a row;
- one property given two different keys by `@JsonProperty` on its field and its setter, which databind refuses too.

The binder refuses, by name and when it is built, each Jackson annotation that changes how databind reads a type and
that the binder does not read, rather than read the type differently from databind without a word:

| Annotation                                                                              | Where it is refused                                          |
| --------------------------------------------------------------------------------------- | ------------------------------------------------------------ |
| `@JsonNaming`                                                                           | the class                                                    |
| `@JsonFormat`, `@JsonView`                                                              | the class, or any member of a property                       |
| `@JsonIgnoreProperties` naming properties                                               | the class, or any member of a property                       |
| `@JsonUnwrapped`, `@JsonSetter`                                                         | any member of a property: a component, field, getter, setter |
| `@JsonCreator`                                                                          | a constructor or a static factory                            |
| `@JsonAnySetter`                                                                        | a method or a field of the class or a superclass             |
| an annotation of your own marked `@JacksonAnnotationsInside`                            | the class, or any member of a property                       |
| `@JsonAlias`, or `@JsonProperty` naming it other than its own name, on an enum constant | the enum, read as a value or as a map key                    |

`@JsonIgnoreProperties(ignoreUnknown = true)` is accepted, since the binder skips unknown keys anyway, and so is
`@JsonProperty("RED")` on the constant `RED`. A bare `@JsonProperty` on an enum constant is refused: databind reads that
constant by the name `""`. A naming strategy set on a `JsonMapper` with `PropertyNamingStrategies` is not seen at all:
the binder has no mapper. `@JsonFormat` is the one most often met; a type that needs it, or any other databind behavior,
reads through databind, with a `TelescopeModule` where a mapper converts it.

Where the binder still reads differently from databind on an annotated type:

- An alias that is another property's key is refused; databind gives the key to the property it names.
- A required component whose key holds `null` is refused, as a missing one is; databind takes the `null` as a value.
- A property the annotations leave unread takes its default, as a missing key does: a bean is built with it written,
  where databind leaves it as the constructor left it.

### Stricter than `fromMap`

The binder accepts the same declared types as `Telescope.fromMap` and the `@FromMap` binder, and leaves the same value
for a missing key, but it reads values strictly where they read leniently. Given a map, `fromMap` narrows a number as a
Java cast does, so an integer past an `int`'s range wraps and `1.5` truncates to `1`; it reads text that is not `true`
as `false`; and it answers a value of the wrong shape for a `List`, `Set` or `Map` with an empty one. The binder refuses
each of these. Its messages use the wording of telescope's built-in conversions, naming the property and the value,
where `fromMap` lets the underlying exception through, such as a `NumberFormatException` for text that is no number.

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

The same two checks hold across modules: when a `JsonMapper` is built with two `TelescopeModule`s, a domain type both
carry, or a wire type one carries that is the other's domain type, is refused with an `IllegalStateException`.

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
