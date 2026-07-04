# telescope-codegen

Annotation processors that emit a fluent typed `<X>Telescope<R>` navigator at compile time, eliminating the runtime
reflection cost of the [core DSL](../README.md). Same end-state values as the reflective
`Telescope.of(Class).field(...)` path; different price tag.

```
Telescope.of(Company.class)             // runtime path (~262 ns/op for a 3-level update)
    .each(Company::departments)
    .each(Department::teams)
    .each(Team::users)
    .field(User::email)
    .update(company, String::toLowerCase);

CompanyTelescope.of()                 // codegen path (~45 ns/op — 5.8× faster)
    .departments().each()
    .teams().each()
    .users().each()
    .email().update(company, String::toLowerCase);
```

## When to use

- **Hot paths.** The codegen navigator emits direct method-reference + canonical-constructor bytecode at every hop. Zero
  `SerializedLambda` decode, zero `Records.fieldLens(String)` lookup, zero `Beans.lens(...)` reflection.
- **Compile-time path validation.** A typo on `CompanyTelescope.of().teams()` is a `javac` error. The reflective
  `.field(Company::teamz)` blows up at construction time.
- **Cross-paradigm bridges.** `@Bridge(UserDto.class)` on a `UserEntity` POJO generates
  `UserEntityTelescope.of().asUserDto().email()` — one fluent chain hops from bean to record.

Skip codegen for prototype / glue code that doesn't sit in a tight loop. The reflective path is sub-microsecond and
still build-time-validated for method references.

## Annotations

| Annotation              | Target                                        | Emits                                                                              |
| ----------------------- | --------------------------------------------- | ---------------------------------------------------------------------------------- |
| `@Focus`                | records                                       | `<R>Telescope<R>` navigator with one method per component                          |
| `@BeanFocus`            | POJOs with public getters / setters / builder | same shape as `@Focus`                                                             |
| `@Bridge(Target.class)` | record or POJO                                | `<S>Bridge.BRIDGE : Iso<S, Target>` constant + `as<Target>()` hop on the navigator |

When a type carries both `@Focus`/`@BeanFocus` **and** `@Bridge`, the navigator gains an `as<TargetSimpleName>()` method
that chains the generated bridge constant.

## Compile-time mapper verification

Having `telescope-codegen` on the annotation-processor path does one more thing, with **zero ceremony**: every
statically-visible `Telescope.map(...)` / `Telescope.mapper(...)` / `Telescope.mapperForward(...)` call site in the
module is verified at compile time. The verifier replays the exact pairing decisions the runtime makes at mapper
construction — completeness of the row set, shape compatibility of each row — and reports violations as compile errors
anchored on the offending expression, with the identical diagnostic text the runtime would throw. For a fully
statically-visible strict site (no `constant` / `compute` rows — those switch the runtime itself into permissive mode):
if it compiles, the mapping is complete. (`mapperForward` is lenient by contract — its rows are shape-checked,
completeness is not required.)

- **On by default, no annotation required.** Adding the processor is the opt-in.
- **It only rejects what construction would reject.** A non-literal class argument defers the whole site to the
  construction-time check, which remains the always-on backstop. When only some rows are opaque — a row built by a
  helper method, a spread array — completeness is skipped but every statically-visible row is still checked exactly
  where the runtime checks it (rows carrying a user-supplied conversion are accepted as-is, in both worlds).
- **Knobs.** `-Atelescope.verify=warn` downgrades errors to warnings; `-Atelescope.verify=off` disables the pass;
  `@UncheckedMapping(reason)` exempts the annotated element's sites (field, method, constructor, or whole class).
  `-Atelescope.verify.verbose` reports skipped sites as NOTEs.
- **javac-only depth.** On a non-javac compiler (ECJ/Eclipse) the processor prints one NOTE and no-ops — you keep the
  construction-time checking you have today.

## Install

```kotlin
// Gradle
dependencies {
    implementation("io.github.eschizoid:telescope-core:1.0.5")
    annotationProcessor("io.github.eschizoid:telescope-codegen:1.0.5")
}
```

```xml
<!-- Maven -->
<dependency>
  <groupId>io.github.eschizoid</groupId>
  <artifactId>telescope-core</artifactId>
  <version>1.0.5</version>
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
        <version>1.0.5</version>
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

The processor emits `UserTelescope<R>` next to it:

```java
public final class UserTelescope<R> {
  public static UserTelescope<User> of() { /* identity */ }

  public Telescope<R, User>    get()     { /* current chain */ }
  public Telescope<R, String>  id()      { /* lens to id */ }
  public Telescope<R, String>  email()   { /* lens to email */ }
  public AddressTelescope<R>   address() { /* sub-record → continue navigation */ }

  // forwarders so any hop can read/update/etc without .get()
  public User read(R source)                                              { ... }
  public R    update(R source, Function<User, User> fn)                   { ... }
  public R    set(R source, User value)                                   { ... }
  // … plus updateAsync / updateOptional / updateEither / updateValidated
}
```

Container components yield a `<X><Cap>Step<R>` whose `.each()` / `.eachValue()` / `.whenPresent()` returns the element's
`<Element>Telescope<R>` navigator when the element type is itself annotated, or a terminal `Telescope<R, T>` when it is
not.

## Lombok bean classes

Lombok-annotated POJOs (`@Data` / `@Value` / `@Builder`) need the companion [`telescope-lombok`](../lombok/README.md)
processor — Lombok's lazy AST patching breaks `:codegen`'s in-memory harness, so the Lombok flavour ships as a separate
processor with round-deferred emission.

## Benchmarks

`bridgeForwardRead` (codegen `@Bridge`) at ~7 ns/op vs runtime `mapBeanForwardRead` at ~285 ns/op is the closest
apples-to-apples comparison. Full table in [`benchmarks/README.md`](../benchmarks/README.md).

## Performance honesty

The apples-to-apples vs MapStruct benchmark is published and CI-reproducible: at the codegen level the two are the same
performance class — a tie at real-service depth. The numbers, full matrix, methodology, and dispatch-overhead
decomposition live in [`benchmarks/README.md`](../benchmarks/README.md#mapstruct-comparison-apples-to-apples).
