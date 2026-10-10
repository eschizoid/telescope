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
implementation("io.github.eschizoid:telescope-core:2.0.0")
annotationProcessor("io.github.eschizoid:telescope-codegen:2.0.0")
```

`@Focus` and `@BeanFocus` are source-retention and inert without the processor, so annotating costs nothing if you don't
wire up codegen. Only top-level records / classes are supported (the generated top-level navigator can't reference a
nested type's constructor).

**`@BeanFocus` — the POJO analog.** Same surface as `@Focus`, applied to a POJO with a static `builder()`, a public
constructor whose parameters are named after its properties, or a no-arg constructor + `setX` setters. The navigator
rebuilds through the first of those the POJO offers, in that order, which is the order runtime `Telescope.ofBean` takes;
the runtime can match a constructor's parameters by name only when the class is compiled with `-parameters`. A POJO that
exposes none of them is a compile error. Neither path writes a private field. The runtime `ofBean` 3-level path runs an
order of magnitude slower than a generated `@Bridge` conversion in the benchmark — the navigator gets you the same
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
    implementation("io.github.eschizoid:telescope-core:2.0.0")
    annotationProcessor("io.github.eschizoid:telescope-codegen:2.0.0")
}
```

Maven:

```xml
<dependency>
  <groupId>io.github.eschizoid</groupId>
  <artifactId>telescope-core</artifactId>
  <version>2.0.0</version>
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
            <version>2.0.0</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

### Annotation processor ordering with Lombok

Lombok and the telescope processors can sit in any order on the processor path. `telescope-lombok` generates a navigator
once Lombok has added the class's members, and `BridgeProcessor`, `FromMapProcessor` and `BeanFocusProcessor` wait until
the final round for a Lombok class. A class counts as a Lombok class when it, or one of its fields, carries a Lombok
annotation that adds members, e.g. `@Data`, `@Getter` or `@AllArgsConstructor`. The full set is
`LOMBOK_SYNTHESIZING_ANNOTATIONS` in `AbstractTelescopeProcessor`.

```xml
<annotationProcessorPaths>
  <path>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <version>1.18.48</version>
  </path>
  <path>
    <groupId>io.github.eschizoid</groupId>
    <artifactId>telescope-lombok</artifactId>
    <version>2.0.0</version>
  </path>
</annotationProcessorPaths>
```

```kotlin
dependencies {
  annotationProcessor("org.projectlombok:lombok:1.18.48")
  annotationProcessor("io.github.eschizoid:telescope-lombok:2.0.0")
}
```

`telescope-lombok` brings `telescope-codegen` onto the processor path with it, so the Gradle snippet doesn't list
`telescope-codegen` separately.

## JPMS / modular consumers

If your project has a `module-info.java`, add the `requires` and, for the runtime navigation path, an `opens` for the
package containing your records / beans / POJOs:

```java
module com.acme.app {
  requires io.github.eschizoid.telescope;

  // Needed by typed paths and runtime mappers (Telescope.of, .ofBean, .map, .mapper, .fromMap, .merge) and by the
  // navigators @Focus / @BeanFocus generate. Generated @Bridge converters and @FromMap binders need none.
  opens com.acme.model to io.github.eschizoid.telescope.internal;
}
```

The `opens` target is **your** package, and the module it opens to is `io.github.eschizoid.telescope.internal`, which
builds every accessor that typed paths and runtime mappers use; an unqualified `opens com.acme.model;` works too.
Telescope's modules do not require yours, so telescope adds the read edge to your module itself the first time it
touches one of your classes; you declare nothing for that. Without the `opens`, the private lookup is refused and the
message names the directive:

> `Cannot access <YourClass> to build its accessors. Add 'opens <pkg> to io.github.eschizoid.telescope.internal;' (or an unqualified 'opens <pkg>;') to the module-info.java of module <your module>, which telescope's accessors are built in.`

On the module path, typed paths and runtime mappers call your accessors through method handles rather than through
classes it spins with `LambdaMetafactory`: a lookup into another named module keeps private access but not the full
privilege that spinning a class needs. A native image uses the same dispatch for the same reason, and the conversions
are the same.

`telescope-internal` comes in transitively via `telescope-core`'s module declaration, but its packages are
qualified-exported to `telescope-core` only, so you cannot accidentally reference internal lattice types from your own
code. `telescope-codegen` is compile-time-only and isn't on the runtime module path.

**What codegen needs.** A generated `@Bridge` converter and a generated `@FromMap` binder read components and call
constructors, builders and setters directly, with no `privateLookupIn`, no `LambdaMetafactory` and no `opens`
requirement. If adding the `opens` is awkward (e.g., a downstream module you don't own), those two sidestep the JPMS
constraint entirely. A navigator generated by `@Focus` or `@BeanFocus` does not: it builds its lenses from method
references when its class initializes, and reading a method reference back needs the package open to
`io.github.eschizoid.telescope.internal`, like a runtime mapper. Without it, the navigator's first use fails with the
same `opens` message. See
[Compile-time, reflection-free navigation](#compile-time-reflection-free-navigation-focus--beanfocus).

**Classpath users (no `module-info.java`).** No `opens` needed — the JVM grants unnamed-module access automatically. The
`opens` directive applies only to JPMS modules.

## Registering `@FromMap` binders

`Telescope.fromMap(...)` uses a generated `@FromMap` binder only when the binder is registered. A class of the right
name is not enough. Each binder nests a `Provider` implementing `FromMapProvider`, and the processor lists it in
`META-INF/services`. The class path and a native image need nothing more.

A named module ignores `META-INF/services`. On the module path, its `module-info` must declare
`provides io.github.eschizoid.telescope.conversion.FromMapProvider with <pkg>.<Name>FromMap.Provider` for each binder.
The processor warns with the exact line when the module it compiles lacks it.

A fat jar has to merge the service files of the jars it combines. Use the shade plugin's `ServicesResourceTransformer`
in Maven or `mergeServiceFiles()` in the Gradle Shadow plugin. Without that, the jar loses the registrations of all but
one of them.

## What runs reflectively, and when

The precise ledger — "reflection-free" claims are scoped to these rows:

| Path                      | Setup (one-time, cached)                                       | Steady-state dispatch                 | Generated Java | Native-image                                                           |
| ------------------------- | -------------------------------------------------------------- | ------------------------------------- | -------------- | ---------------------------------------------------------------------- |
| Runtime record navigation | `getRecordComponents` + method-ref decode                      | `LambdaMetafactory`-built lambdas     | none           | `MethodHandle` closures; app registers call-site class (serialization) |
| Runtime POJO navigation   | getter/setter scans + method-ref decode                        | LMF getters/setters/builders          | none           | same gate                                                              |
| Runtime mapper            | reflective pair discovery, cached per type pair                | composed MethodHandle / LMF leaves    | none           | verified by the CI native binary                                       |
| `@Focus` / `@BeanFocus`   | one cached method-ref decode when a path object is first built | direct method-ref + constructor calls | yes            | generated navigator class goes in `serialization-config` (CI-verified) |
| `@Bridge`                 | none (wraps a concrete generated function)                     | direct calls                          | yes            | zero-config, CI-verified                                               |
