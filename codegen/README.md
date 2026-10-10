# telescope-codegen

`telescope-codegen` holds the annotation processors that generate code at compile time for the [core DSL](../README.md).
Each annotated type gets a typed navigator, a converter, or a binder, and `javac` checks every step in it. A generated
navigator gives the same results as the reflective `Telescope.of(Class)` path through the same fields. A generated
converter calls constructors, getters, builders and setters directly, with no reflection at run time.

With `@Focus` on these records, the processor writes `CompanyTelescope`, `TeamTelescope` and `UserTelescope`:

```java
@Focus
public record User(String name, String email) {}

@Focus
public record Team(String name, List<User> users) {}

@Focus
public record Company(String name, List<Team> teams) {}
```

The same update can then go through the reflective path or through the generated navigator:

```java
// Reflective path: field names are recovered from method references at run time.
Telescope.of(Company.class)
  .each(Company::teams)
  .each(Team::users)
  .field(User::email)
  .update(company, String::toLowerCase);

// Generated navigator: every step is a method on a generated class.
CompanyTelescope.of()
  .teams().each()
  .users().each()
  .email()
  .update(company, String::toLowerCase);
```

## Install

Put `telescope-core` on the compile classpath and `telescope-codegen` on the annotation processor path. The processor is
needed only at compile time.

```kotlin
// Gradle (Kotlin DSL)
dependencies {
    implementation("io.github.eschizoid:telescope-core:2.0.0")
    annotationProcessor("io.github.eschizoid:telescope-codegen:2.0.0")
}
```

```xml
<!-- Maven -->
<dependency>
  <groupId>io.github.eschizoid</groupId>
  <artifactId>telescope-core</artifactId>
  <version>2.0.0</version>
</dependency>

<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths>
      <path>
        <groupId>io.github.eschizoid</groupId>
        <artifactId>telescope-codegen</artifactId>
        <version>2.0.0</version>
      </path>
    </annotationProcessorPaths>
  </configuration>
</plugin>
```

Lombok can sit before or after `telescope-codegen` on the processor path, because the processors wait for Lombok's
members before they read a Lombok class. The JPMS setup is in [docs/codegen.md](../docs/codegen.md).

## Processors

The jar registers six processors. You do not pick them one by one. Each one acts only on its own annotation, except the
mapper verifier, which checks every class it compiles.

| Processor                  | Trigger                                               | Generates                                                                         |
| -------------------------- | ----------------------------------------------------- | --------------------------------------------------------------------------------- |
| `FocusProcessor`           | `@Focus` on a record                                  | `<X>Telescope<R>` navigator and `<X>FieldOptics` holder                           |
| `BeanFocusProcessor`       | `@BeanFocus` on a POJO                                | `<X>Telescope<R>` navigator and `<X>FieldOptics` holder                           |
| `BridgeProcessor`          | `@Bridge` (repeatable, or `@Bridges`)                 | `<Source>Bridge` converter                                                        |
| `FromMapProcessor`         | `@FromMap` on a record or POJO                        | `<X>FromMap` binder from `Map<String, Object>`                                    |
| `TelescopeMapperProcessor` | `@TelescopeMapper` or `@TelescopeTransformer`         | a Spring `@Component`, see the [Spring starter](../spring-boot-starter/README.md) |
| `MapperVerifierProcessor`  | any `Telescope.map`, `mapper` or `mapperForward` call | compile errors for mappings the runtime would refuse                              |

All annotations have source retention, so they leave nothing in the compiled classes. An annotated type must be a
top-level record or class, and the processor reports a nested one as a compile error.

## Navigators: `@Focus` and `@BeanFocus`

Put `@Focus` on a record and `@BeanFocus` on a POJO. The processor writes a `<X>Telescope<R>` class next to each one.

```java
@Focus
public record Address(String city, String zip) {}

@Focus
public record User(String name, String email, Address address) {}

@Focus
public record Team(String name, List<User> users) {}

@Focus
public record Company(String name, List<Team> teams) {}
```

The generated class has these members:

- `static UserTelescope<User> of()` starts a path at `User`, and `get()` returns the `Telescope<R, User>` built so far.
- A method per component. A plain component such as `email()` returns a `Telescope<R, String>`. A component whose type
  is annotated, such as `address()`, returns that type's navigator, `AddressTelescope<R>`, so navigation continues.
- A container component returns a step class named `<X><Component>Step<R>`, such as `TeamUsersStep<R>`. Its `.each()`
  (for `List`, `Set` and `Iterable`), `.eachValue()` (for `Map` values) or `.whenPresent()` (for `Optional`) returns the
  element's navigator when the element type is annotated, and a `Telescope<R, E>` otherwise.
- Forwarders for the `Telescope` operations, so you can call `read`, `find`, `toList`, `count`, `exists`, `set`,
  `update`, `updateIndexed`, the four effectful `update*` methods, `then`, `observe`, `explain` and `trace` at any hop.

```java
Company lowered = CompanyTelescope.of().teams().each().users().each().email().update(company, String::toLowerCase);

User shouted = UserTelescope.of().address().city().update(user, String::toUpperCase);

List<String> names = CompanyTelescope.of().teams().each().users().each().name().toList(company);
```

A navigator path takes part in multi-edit fusion like a hand-written path. Each component lens is built with
`Telescope.componentLens`, which records the same hop that `field(...)` records. `Telescope.all` can therefore fold
these two edits into one pass over the shared `teams` prefix:

```java
Telescope<Company, Company> normalize = Telescope.all(
  over(CompanyTelescope.of().teams().each().users().each().email(), String::toLowerCase),
  over(CompanyTelescope.of().teams().each().name(), String::trim)
);

Company cleaned = normalize.apply(company);
```

A navigator path and a hand-written path through the same components also fuse with each other. When any path goes
through a bridge hop, `Telescope.all` applies the edits one after another instead.

`@BeanFocus` rebuilds a POJO through the first of these it offers: a static `builder()`, a public constructor whose
parameter names match the properties, or a no-arg constructor with setters. The runtime `Telescope.ofBean` takes the
same order. A POJO with none of them is a compile error.

The `<X>FieldOptics` class holds a `Telescope` constant per field. When it is on the classpath, the runtime
`Telescope.of(X.class).field(X::name)` uses that constant and skips building the lens itself.

## Converters: `@Bridge`

`@Bridge(Target.class)` on a source type generates a `<Source>Bridge` class that converts in both directions. Either
side may be a record or a POJO. Fields are matched by name, and by default the two sides must have the same set of
names.

```java
@Focus
@Bridge(UserDto.class)
public record UserEntity(String id, String email, List<String> tags) {}

@Focus
public record UserDto(String id, String email, List<String> tags) {}
```

`UserEntityBridge` has these members:

| Member                                   | What it is                                                                                               |
| ---------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| `static UserDto forward(UserEntity s)`   | converts source to target, and returns `null` for `null`                                                 |
| `static UserEntity backward(UserDto t)`  | converts target to source, and returns `null` for `null`                                                 |
| `static UserEntity patch(base, partial)` | rebuilds `base` with the non-null reference fields and all primitive fields of `partial` written over it |
| `BRIDGE_FN`                              | a `BridgeFn<UserEntity, UserDto>` that calls `forward` and `backward`                                    |
| `BRIDGE`                                 | a `Telescope<UserEntity, UserDto>`, so `BRIDGE.read(entity)` converts                                    |

```java
UserDto dto = UserEntityBridge.forward(entity);

UserEntity back = UserEntityBridge.backward(dto);

UserDto same = UserEntityBridge.BRIDGE.read(entity);

UserEntity patched = UserEntityBridge.patch(entity, new UserDto(null, "new@x.io", null));
```

When a type carries both `@Focus` (or `@BeanFocus`) and `@Bridge`, its navigator gains an `as<Target>()` method that
steps through the bridge. Here the email is changed on the DTO side, and the result is converted back to a new
`UserEntity`:

```java
UserEntity lowered = UserEntityTelescope.of().asUserDto().email().update(entity, String::toLowerCase);
```

`as<Target>()` returns the target's navigator when the target is annotated, and a `Telescope<R, Target>` otherwise.
There is no hop in the other direction. Annotate the target with its own `@Bridge` for that.

### What the generated converter does with nested values

- A nested record or POJO with no `@Bridge` of its own gets a sub-bridge named `<Source>To<Target>Bridge`.
- A container whose source and target declare the same container type, such as `List<String>` on both sides, is copied
  into a new container. The copy is shallow, so its elements are the source's own.
- An object graph that refers back to an object still being converted maps that reference to `null` instead of looping.
  The runtime mapper does the same, and `CrossPathCorpusTest` holds the two paths to the same result.
- Two enum types convert by constant name with no extra attribute. A strict bridge needs every constant on each side to
  have a same-named constant on the other. A lenient bridge needs only every source constant to exist in the target, and
  its backward direction turns an unmatched constant into `null`. Any other enum pair is a compile error that names the
  missing constants.

`patch` writes every non-null reference field of `partial` over `base`, and every primitive field of `partial`, even one
that holds 0 or `false`. It always returns a new object, even when `partial` is `null` or changes nothing. It rebuilds
only the root object. A field that `partial` leaves `null` keeps the object `base` holds, so a nested object or list is
shared with `base`.

### Attributes of `@Bridge`

| Attribute          | Use                                                                                                 |
| ------------------ | --------------------------------------------------------------------------------------------------- |
| `value`            | the target type, when the annotation is on the source                                               |
| `source`, `target` | the two types, when the annotation is on a separate carrier class                                   |
| `drops`            | source fields the target lacks, which backward fills with `null`, zero or `false`                   |
| `renames`          | `@Rename(source = "...", target = "...")` pairs for fields whose names differ                       |
| `transforms`       | `@Transform(field = "...", using = X.class)`, where `X` is a `BridgeFn` that converts one field     |
| `constants`        | `@Constant(field = "...", value = "...")`, a literal written to a target field on forward           |
| `computes`         | `@Compute(field = "...", using = X.class)`, where `X` is a `Supplier` called on forward             |
| `defaults`         | `@Default(field = "...", value = "...")`, a literal used when the source field is `null`            |
| `viaMappers`       | `@ViaMapper(field = "...", using = X.class)`, where `X` has static `forward` and `backward` methods |
| `writeStrategy`    | `WriteStrategy.AUTO` (the default), `CONSTRUCTOR`, `BUILDER` or `SETTERS`, for a POJO target        |
| `lenient`          | `true` skips the check that every field has a counterpart, and unmatched fields get defaults        |

`@Rename` and `@Transform` also take `forwardOnly`, and `@Transform` takes `method` to call a named static method
instead of a `BridgeFn`. The field names in these attributes are strings, and a name that does not exist is a compile
error.

```java
public final class Cents implements BridgeFn<BigDecimal, Long> {

  public Long forward(BigDecimal x) {
    return x.movePointRight(2).longValueExact();
  }

  public BigDecimal backward(Long c) {
    return BigDecimal.valueOf(c).movePointLeft(2);
  }
}

@Bridge(
  value = OrderDto.class,
  renames = @Rename(source = "orderNumber", target = "reference"),
  transforms = @Transform(field = "total", using = Cents.class),
  defaults = @Default(field = "region", value = "EMEA"),
  constants = @Constant(field = "channel", value = "API"),
  drops = "internalNote"
)
public record Order(String orderNumber, BigDecimal total, String region, String internalNote) {}

public record OrderDto(String reference, long total, String region, String channel) {}
```

With these, `OrderBridge.forward(new Order("N-1", new BigDecimal("12.34"), null, "secret"))` returns
`OrderDto[reference=N-1, total=1234, region=EMEA, channel=API]`.

A `@Bridge` with `lenient = true` loses data on the way back. Every source field with no target counterpart comes back
as `null`, zero or `false`, whatever the original held.

### Carrier form and naming

When the source and target live in modules that cannot see each other, put the annotation on a third class that names
both. The bridge is written next to the carrier and named after it:

```java
@Bridge(source = Customer.class, target = CustomerView.class)
final class CustomerMapping {}
// generates CustomerMappingBridge in the carrier's package
```

A carrier bridge has no `as<Target>()` hop. The runtime `Telescope.mapperForward` finds it through a generated
`BridgeProvider`.

A source that declares more than one target, with repeated `@Bridge` or `@Bridges`, gets one `<Source>To<Target>Bridge`
per target, because the short name can belong to only one pair. A sealed source and target are bridged case by case, and
the bridge for the sealed root is named by the same rules.

## Binders: `@FromMap`

`@FromMap` on a record or POJO generates a `<X>FromMap` class that builds the type from a `Map<String, Object>` without
reflection. Values that arrive as strings are parsed into the component's type, for example `"42"` into an `int` and
`"2000-01-02"` into a `LocalDate`.

```java
@FromMap(required = "id")
public record Signup(String id, int age, LocalDate born) {}

Signup s = SignupFromMap.fromMap(Map.of("id", "s1", "age", "42", "born", "2000-01-02"));
```

A key listed in `required` that is absent from the map, or holds `null`, throws `IllegalArgumentException`. Any other
missing key leaves the component at its default. The class also holds a `FROM_MAP` constant, a `ForwardMapper`.
`Telescope.fromMap(...)` uses the binder once it is registered. Registration on the module path is covered in
[docs/codegen.md](../docs/codegen.md).

## Compile-time mapper verification

The verifier checks every `Telescope.map(...)`, `Telescope.mapper(...)` and `Telescope.mapperForward(...)` call whose
classes are given as class literals. It applies the same pairing rules the runtime applies when it builds the mapper,
and reports the runtime's own message as a compile error on the call:

```java
record Person(String name, int age) {}
record PersonDto(String name, int years) {}

// error: Deep map Person → PersonDto: target field 'years' has no same-name source field. ...
Telescope.mapper(Person.class, PersonDto.class);

// compiles
Telescope.mapper(Person.class, PersonDto.class, to(Person::age, PersonDto::years));
```

It never rejects a call that the runtime would build:

- A class argument that is not a literal skips the call, and the runtime check still applies when the mapper is built.
- A row built by a helper method skips the completeness check. The rows the verifier can see are still checked.
- A `constant` or `compute` row turns off the completeness check, because the runtime also skips the completeness check
  for such a mapper.
- `mapperForward` checks the rows it is given and does not require every field to be covered.

The verifier is on whenever the processor is on the path, and it takes the options below:

- `-Atelescope.verify=error` is the default. `warn` reports warnings instead of errors, and `off` turns the check off.
- `-Atelescope.verify.verbose` prints a note for each call it skips.
- `@UncheckedMapping("reason")` on a class, method, constructor or field skips the calls inside it.

The verifier needs javac's tree API. On another compiler it prints one note and does nothing.

## Lombok

A class annotated with Lombok's `@Data`, `@Value` or `@Builder` gets its navigator from
[`telescope-lombok`](../lombok/README.md), with no `@BeanFocus` needed. telescope-lombok writes the navigator as soon as
Lombok has added the class's members, so code in the same module can use it. Without `telescope-lombok`, `@BeanFocus` on
a Lombok class also works, but its navigator is written in the last processing round and cannot be named from main code
in the same module.

## Performance

Measured figures for the generated converter against MapStruct, per tier and call shape (`BRIDGE_FN`, `BRIDGE.read` and
`BRIDGE.set`), are in the root README's [Measured performance](../README.md#measured-performance) section. The static
`forward` rows are in [benchmarks/README.md](../benchmarks/README.md#mapstruct-comparison). The method and the per-fork
numbers are in [docs/perf-mapstruct-comparison.md](../docs/perf-mapstruct-comparison.md).
