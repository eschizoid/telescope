# Compile-time codegen

The `@Focus` / `@BeanFocus` / `@Bridge` processors: what they generate, how to install them, processor ordering with
Lombok, and the JPMS story. [← back to README](../README.md)

## Compile-time, reflection-free navigation (`@Focus` / `@BeanFocus`)

The reflection-based `Telescope.of(User.class).field(User::name)` path resolves the field name at runtime — fast enough
for ordinary use (~100 ns), but a typo or a rename surfaces at runtime instead of compile time. Annotate the types you
navigate with `@Focus` (records) or `@BeanFocus` (POJOs) and add the processor to your build; for each annotated type
the processor emits a sibling **fluent typed path navigator** that reads like the runtime DSL but is fully
compile-checked: every read/rebuild is a direct method-ref + constructor call; the only reflection left is a one-time,
cached method-reference decode when the path object is first built.

**Same path, two ways.** The two surfaces produce the same terminal `Telescope<Company, String>` and the same `update`
result — they only differ in _when_ the path is resolved (runtime vs `javac`) and _how_ it's dispatched (reflection vs
direct method-ref + constructor calls). On the [benchmarks](../benchmarks/README.md), the reflective deep-field path
runs ~5.8x slower than the codegen lens path it desugars to ([current numbers](../benchmarks/README.md)).

```java
// Reflective — runtime resolution, ~100 ns per field hop
Telescope.of(Company.class)
  .each(Company::departments).each(Department::teams)
  .each(Team::users).field(User::email)
  .update(company, String::toLowerCase);

// Compile-time — same Telescope, generator-built, direct method-ref + constructor calls
CompanyTelescope.of()
  .departments().each().teams().each()
  .users().each().email()
  .update(company, String::toLowerCase);
```

```java
import io.github.eschizoid.telescope.annotations.Focus;

@Focus record Address(String city, String zip) {}
@Focus record User(String name, int age, Address address) {}
@Focus record Team(String name, List<User> users) {}
@Focus record Company(String name, List<Team> teams) {}

// Generated: <X>Telescope<R> per annotated type plus a step class per collection-shaped component.
// Usage reads like the reflective DSL — but every hop is type-checked by javac and every read /
// rebuild is a direct method-ref + constructor call:
final Telescope<Company, String> userNames = CompanyTelescope.of()
  .teams().each()        // step over List<Team> → TeamTelescope<Company>
  .users().each()        // step over List<User> → UserTelescope<Company>
  .name();               // terminal Telescope<Company, String>

final Company shouted = userNames.update(company, String::toUpperCase);

// Single fields are just as direct:
UserTelescope.of().address().city().update(alice, String::toUpperCase);
```

Each scalar component yields a terminal `Telescope<R, T>`; each sub-record component (also `@Focus`-annotated) yields a
`<Sub>Telescope<R>` navigator to keep navigating; each container component yields a small step class whose `.each()`
(List/Set/Iterable), `.eachValue()` (Map values, keys preserved), or `.whenPresent()` (Optional) returns the element's
navigator when the element is itself annotated, or a terminal `Telescope` otherwise. At any hop, `.get()` returns the
current `Telescope` — so a step or navigator _is_ a navigator, but every leaf is the same `Telescope<R, X>` value the
reflective DSL gives you.

**Ops at every hop, effects included.** Every generated navigator and `Step` also forwards the full `Telescope`
operation surface — `read` / `find` / `toList` / `count` / `exists` / `set` / `update` / `updateIndexed` /
`toListIndexed` / `then` plus the four effect methods `updateAsync` (with or without `Executor`) / `updateOptional` /
`updateEither` / `updateValidated`. You don't need to terminate with `.get()` first; the navigator stands in for the
wrapped Telescope at any intermediate hop. So
`CompanyTelescope.of().teams().each().users().each().updateAsync(company, svc::lookup, pool)` returns a
`CompletableFuture<Company>` directly, with the effect threaded through the generated chain.

**Bridge hops — conversion as a navigator step.** If a type carries both `@Focus`/`@BeanFocus` (so it has a `*Telescope`
navigator) and `@Bridge(Target.class)` (so it has a `*Bridge.BRIDGE`), the navigator gains a fluent **`as<Target>()`**
method that chains the bridge in. The navigator becomes a single compile-checked surface for _both_ navigation _and_
conversion, crossing paradigms naturally (record↔record, record↔POJO, POJO↔POJO):

```java
@Focus
@Bridge(UserDto.class)
record UserEntity(String id, String email) {}

@Focus
record UserDto(String id, String email) {}

// Navigate through the bridge into a target field, then update. The conversion round-trips, so the
// result is a new UserEntity:
final UserEntity lowered = UserEntityTelescope.of()
  .asUserDto() // → UserDtoTelescope<UserEntity>
  .email() // → Telescope<UserEntity, String>
  .update(entity, String::toLowerCase);
```

The return type degrades to a terminal `Telescope<R, Target>` when the target isn't itself annotated (so there's no
`<Target>Telescope` navigator to chain into). The reverse direction has no navigator-level hop yet — build it from the
bridge function:
`Telescope.from(Target.class).to(Source.class).using(SourceBridge.BRIDGE_FN::backward, SourceBridge.BRIDGE_FN::forward)`,
or annotate the target with its own `@Bridge`.

Gradle wiring:

```kotlin
implementation("io.github.eschizoid:telescope-core:1.4.0")
annotationProcessor("io.github.eschizoid:telescope-codegen:1.4.0")
```

`@Focus` and `@BeanFocus` are source-retention and inert without the processor, so annotating costs nothing if you don't
wire up codegen. Only top-level records / classes are supported (the generated top-level navigator can't reference a
nested type's constructor).

**`@BeanFocus` — the POJO analog.** Same surface as `@Focus`, applied to a POJO with either a static `builder()` or a
no-arg constructor + `setX` setters. Field injection isn't available to generated code, so a POJO that exposes neither
is a compile error; reach for runtime `Telescope.ofBean` in that case. The runtime `ofBean` 3-level path runs an order
of magnitude slower than a generated `@Bridge` conversion in the benchmark — the navigator gets you the same
reflection-free win for navigation.

```java
import io.github.eschizoid.telescope.annotations.BeanFocus;

@BeanFocus public class UserBean { /* getId/getEmail + setters, or a static builder() */ }

// Generated alongside: UserBeanTelescope<R> with the same fluent surface as a record navigator.
UserBeanTelescope.of().email().update(user, String::toLowerCase);   // direct getter + setter dispatch
```

## Installing the processor

Add the processor only if you use the `@Focus` path. It's inert otherwise — the annotation is source-retention.

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation("io.github.eschizoid:telescope-core:1.4.0")
    annotationProcessor("io.github.eschizoid:telescope-codegen:1.4.0")
}
```

Maven:

```xml
<dependency>
  <groupId>io.github.eschizoid</groupId>
  <artifactId>telescope-core</artifactId>
  <version>1.4.0</version>
</dependency>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <configuration>
        <annotationProcessorPaths>
          <path>
            <groupId>io.github.eschizoid</groupId>
            <artifactId>telescope-codegen</artifactId>
            <version>1.4.0</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

### Annotation processor ordering with Lombok

When both Lombok and `telescope-lombok` / `telescope-codegen` sit on the annotation processor path, list **Lombok
first**. Maven passes the declaration order of `<annotationProcessorPaths>` to javac; Gradle passes the order of
`annotationProcessor(...)` calls (processor discovery order is toolchain behavior, not a spec guarantee — which is why
the processors are order-tolerant regardless):

```xml
<annotationProcessorPaths>
  <path>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <version>1.18.46</version>
  </path>
  <path>
    <groupId>io.github.eschizoid</groupId>
    <artifactId>telescope-lombok</artifactId>
    <version>1.4.0</version>
  </path>
</annotationProcessorPaths>
```

```kotlin
dependencies {
  annotationProcessor("org.projectlombok:lombok:1.18.46")
  annotationProcessor("io.github.eschizoid:telescope-lombok:1.4.0")
  annotationProcessor("io.github.eschizoid:telescope-codegen:1.4.0")
}
```

Both `BridgeProcessor` and `LombokFocusProcessor` round-defer emission to `processingOver()` when they detect that the
host class (or its `@Bridge` target) carries a Lombok-synthesizing annotation, so the build is order-tolerant — but
explicit ordering avoids relying on round-deferral and is the recommended posture. The Lombok-synthesizing trigger set
includes `@Data`, `@Value`, `@Builder`, `@Getter`, `@Setter`, the three `*ArgsConstructor` variants, `@SuperBuilder`,
and `@experimental.Accessors`.

Symptoms of mis-ordering without round-deferral (now harmless thanks to the deferral fix, but worth recognizing on older
versions): an emitted `<X>Bridge` whose `forward`/`backward` are no-ops, or a `@Data` class for which no `<X>Telescope`
lands. Both mean the telescope processor ran before Lombok patched the host class.

## JPMS / modular consumers

If your project has a `module-info.java`, add the `requires` and, for the runtime navigation path, an `opens` for the
package containing your records / beans / POJOs:

```java
module com.acme.app {
  requires io.github.eschizoid.telescope;

  // Only needed if you use the RUNTIME path (Telescope.of, .ofBean, .map, .mapper).
  // The codegen path (@Focus / @BeanFocus / @Bridge) needs no opens.
  opens com.acme.model to io.github.eschizoid.telescope;
}
```

The `opens` target is **your** package, the one telescope needs to reach into — not telescope's. Runtime navigation
binds accessors via `MethodHandles.privateLookupIn(yourClass, MethodHandles.lookup())` and feeds the handles to
`LambdaMetafactory` for hot-path dispatch. Without an `opens`, the lookup fails with `IllegalAccessException`, surfaced
as:

> `Cannot access <YourClass> ... to build LambdaMetafactory <kind>. Add 'opens <pkg> to io.github.eschizoid.telescope;' to that module's module-info.java.`

Copy the package from the error message into the `opens` directive.

`telescope-internal` comes in transitively via `telescope-core`'s module declaration, but its packages are
qualified-exported to `telescope-core` only, so you cannot accidentally reference internal lattice types from your own
code. `telescope-codegen` is compile-time-only and isn't on the runtime module path.

**Codegen escape hatch.** The `@Focus` / `@BeanFocus` / `@Bridge` processors emit compile-time navigators that read
components and call constructors / builders / setters directly — no `privateLookupIn`, no `LambdaMetafactory`, no
`opens` requirement. If adding the `opens` is awkward (e.g., a downstream module you don't own), the codegen path
sidesteps the JPMS constraint entirely. See
[Compile-time, reflection-free navigation](#compile-time-reflection-free-navigation-focus--beanfocus).

**Classpath users (no `module-info.java`).** No `opens` needed — the JVM grants unnamed-module access automatically.
This section is JPMS-only.

## What runs reflectively, and when

The precise ledger — "reflection-free" claims are scoped to these rows:

| Path                      | Setup (one-time, cached)                                       | Steady-state dispatch                                        | Generated Java | Native-image                                                           |
| ------------------------- | -------------------------------------------------------------- | ------------------------------------------------------------ | -------------- | ---------------------------------------------------------------------- |
| Runtime record navigation | `getRecordComponents` + method-ref decode                      | `LambdaMetafactory`-built lambdas                            | none           | `MethodHandle` closures; app registers call-site class (serialization) |
| Runtime POJO navigation   | getter/setter scans + method-ref decode                        | LMF getters/setters (`FIELDS` strategy uses `setAccessible`) | none           | same gate                                                              |
| Runtime mapper            | reflective pair discovery, cached per type pair                | composed MethodHandle / LMF leaves                           | none           | verified by the CI native binary                                       |
| `@Focus` / `@BeanFocus`   | one cached method-ref decode when a path object is first built | direct method-ref + constructor calls                        | yes            | generated navigator class goes in `serialization-config` (CI-verified) |
| `@Bridge`                 | none (wraps a concrete generated function)                     | direct calls                                                 | yes            | zero-config, CI-verified                                               |
