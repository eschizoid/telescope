# telescope-codegen

Annotation processors that emit a fluent typed `<X>Path<R>` navigator at compile time, eliminating the runtime
reflection cost of the [core DSL](../README.md). Same end-state values as the reflective
`Telescope.of(Class).field(...)` path; different price tag.

```
Telescope.of(Company.class)             // runtime path (~262 ns/op for a 3-level update)
    .each(Company::departments)
    .each(Department::teams)
    .each(Team::users)
    .field(User::email)
    .update(company, String::toLowerCase);

CompanyPath.of()                      // codegen path (~45 ns/op — 5.8× faster)
    .departments().each()
    .teams().each()
    .users().each()
    .email().update(company, String::toLowerCase);
```

## When to use

- **Hot paths.** The codegen navigator emits direct method-reference + canonical-constructor bytecode at every hop. Zero
  `SerializedLambda` decode, zero `Records.fieldLens(String)` lookup, zero `Beans.lens(...)` reflection.
- **Compile-time path validation.** A typo on `CompanyPath.of().teams()` is a `javac` error. The reflective
  `.field(Company::teamz)` blows up at construction time.
- **Cross-paradigm bridges.** `@Bridge(UserDto.class)` on a `UserEntity` POJO generates
  `UserEntityPath.of().asUserDto().email()` — one fluent chain hops from bean to record.

Skip codegen for prototype / glue code that doesn't sit in a tight loop. The reflective path is sub-microsecond and
still build-time-validated for method references.

## Annotations

| Annotation              | Target                                        | Emits                                                                              |
| ----------------------- | --------------------------------------------- | ---------------------------------------------------------------------------------- |
| `@Focus`                | records                                       | `<R>Path<R>` navigator with one method per component                               |
| `@BeanFocus`            | POJOs with public getters / setters / builder | same shape as `@Focus`                                                             |
| `@Bridge(Target.class)` | record or POJO                                | `<S>Bridge.BRIDGE : Iso<S, Target>` constant + `as<Target>()` hop on the navigator |

When a type carries both `@Focus`/`@BeanFocus` **and** `@Bridge`, the navigator gains an `as<TargetSimpleName>()` method
that chains the generated bridge constant.

## Install

```kotlin
// Gradle
dependencies {
    implementation("io.github.eschizoid:telescope:0.4.1")
    annotationProcessor("io.github.eschizoid:telescope-codegen:0.4.1")
}
```

```xml
<!-- Maven -->
<dependency>
  <groupId>io.github.eschizoid</groupId>
  <artifactId>telescope</artifactId>
  <version>0.4.1</version>
</dependency>
<!-- annotationProcessorPaths on maven-compiler-plugin -->
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths>
      <path>
        <groupId>io.github.eschizoid</groupId>
        <artifactId>telescope-codegen</artifactId>
        <version>0.4.1</version>
      </path>
    </annotationProcessorPaths>
  </configuration>
</plugin>
```

## What gets generated

For a record `User`:

```java
@Focus
public record User(String id, String email, Address address) {}
```

The processor emits `UserPath<R>` next to it:

```java
public final class UserPath<R> {
  public static UserPath<User> start() { /* identity */ }

  public Telescope<R, User>    get()     { /* current chain */ }
  public Telescope<R, String>  id()      { /* lens to id */ }
  public Telescope<R, String>  email()   { /* lens to email */ }
  public AddressPath<R>        address() { /* sub-record → continue navigation */ }

  // forwarders so any hop can read/update/etc without .get()
  public User read(R source)                                              { ... }
  public R    update(R source, Function<User, User> fn)                   { ... }
  public R    set(R source, User value)                                   { ... }
  // … plus updateAsync / updateOptional / updateEither / updateValidated
}
```

Container components yield a `<X><Cap>Step<R>` whose `.each()` / `.eachValue()` / `.whenPresent()` returns the element's
`Path<R>` when the element type is itself annotated, or a terminal `Telescope<R, T>` when it is not.

## Lombok bean classes

Lombok-annotated POJOs (`@Data` / `@Value` / `@Builder`) need the companion [`telescope-lombok`](../lombok/README.md)
processor — Lombok's lazy AST patching breaks `:codegen`'s in-memory harness, so the Lombok flavour ships as a separate
processor with round-deferred emission.

## Benchmarks

`bridgeForwardRead` (codegen `@Bridge`) at ~15 ns/op vs runtime `mapBeanForwardRead` at ~142 ns/op is the closest
apples-to-apples comparison. Full table in the [root README](../README.md#performance).

## Performance honesty

We haven't published an apples-to-apples vs MapStruct benchmark yet — both bind at compile time, so the comparison would
be tight. See PLAN Tier 1 item 1; benchmark lands before 1.0.
