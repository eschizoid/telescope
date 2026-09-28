# telescope-spring-boot-starter

## Injectable mappers and transformations

`@TelescopeMapper` exposes a generated structural mapper as a Spring bean. `@TelescopeTransformer` exposes a reusable
transformation over a typed Telescope path. A transformation declares both the default for a null focused value and the
operation applied to a non-null value:

```java
public record Address(String city) {}

public record UserDto(String name, Address address) {}

@Bridge(value = UserDto.class, defaults = @Default(field = "name", value = "(unnamed)"))
public record User(String name, Address address) {}

@TelescopeTransformer("cityNormalizer")
public interface UserCityTransformer extends TelescopeTransformation<User, String> {
  @Override
  default Telescope<User, String> path() {
    return Telescope.of(User.class).field(User::address).field(Address::city);
  }

  @Override
  default Transformation<String> transform() {
    return new Transformation<>("unknown", (city) -> city.toLowerCase(Locale.ROOT));
  }
}

@TelescopeTransformer
public interface UserNameTransformer extends TelescopeTransformation<User, String> {
  @Override
  default Telescope<User, String> path() {
    return Telescope.of(User.class).field(User::name);
  }

  @Override
  default Transformation<String> transform() {
    return new Transformation<>("(unnamed)", String::strip);
  }
}

@TelescopeMapper(transformers = { UserCityTransformer.class, UserNameTransformer.class })
public interface UserProjection extends TelescopeProjection<User, UserDto> {}

@Service
class UserService {

  private final UserProjection projection;

  UserService(UserProjection projection) {
    this.projection = projection;
  }

  UserDto project(User user) {
    return projection.map(user);
  }
}
```

`projection.map(user)` applies the city and name transformers in declaration order, then performs the structural
mapping. A non-null city is lowercased; a null city becomes `"unknown"`. A null `address` has no city focus and stays
null. The operation runs once for each non-null focus, including empty strings. A null root bypasses the transformers
and maps to null. The source record remains unchanged. The nullable default may itself be null.

`TelescopeProjection<User, UserDto>` supplies the mapper's source and target types, so this annotation does not repeat
them. A mapper interface that declares only `map(User)` can still use
`@TelescopeMapper(from = User.class, to = UserDto.class)`; both attributes are required in that form.

### A mapper with no `TelescopeProjection`

Skip `TelescopeProjection` entirely when you only need `map(source)` — no `forward`, `backward`, or `patch`, and no
manual `addTransformer` registration. This form still accepts `transformers`; they run in declaration order before the
existing `@Bridge` is called:

```java
@TelescopeMapper(from = User.class, to = UserDto.class, transformers = { UserCityTransformer.class })
public interface UserBridgeMapper {
  UserDto map(User user);
}
```

`UserBridgeMapper` requires a generated `@Bridge` for `User -> UserDto` (the `User` record above already declares one).
Reach for this form when a plain injectable wrapper around an existing bridge is all you need, and switch to
`TelescopeProjection` once you also want `forward`/`backward`/`patch` or customizer-registered transformers.

### What a projection gives you from Telescope core

A `TelescopeProjection<S, T>` bean is the core `Mapper<S, T>` behind a Spring interface:

- `map` / `forward` run the transformers, then the structural mapping.
- `backward` and `patch` use the structural mapping directly. Transformers are one-way normalization, so they never run
  on the way back.
- `translate(MapperBuilder<S, T>)` is the full core mapping DSL, not just renames. `from(...).to(...)` pairs two
  accessors; `add(...)` takes any `MapStep` row the core `Telescope.mapper(...)` accepts, such as `constant`, `compute`,
  `nullSourceValues`, or `writeBeans`. Every row names fields through method references, so a rename in the IDE updates
  it or fails compilation.

A projection whose type pair has a `@Bridge` and no `translate` override calls the generated bridge. Any `translate`
override, including one inherited from a parent interface, builds a core `Mapper` from `translate`'s rows instead.

### Registering transformers from configuration

When the transformers depend on configuration rather than a fixed list, declare a `TelescopeCustomizer` bean typed by
the projection interface. No bean names, no `@Qualifier`, no `@Primary`, and no second bean of the projection type:

```java
@TelescopeMapper
interface ManualUserProjection extends TelescopeProjection<User, UserDto> {}

@Configuration
class ProjectionConfig {

  @Bean
  TelescopeCustomizer<ManualUserProjection> manualUserTransformers(UserCityTransformer city, UserNameTransformer name) {
    return (projection) -> projection.addTransformer(city).addTransformer(name);
  }
}
```

Customizers run while Spring constructs the projection, after any transformers declared on `@TelescopeMapper`. Several
customizers for the same projection run in `@Order` order. A customizer typed for one projection never touches another.
Once construction ends the transformer list is fixed: `addTransformer` then throws `IllegalStateException`, so the
mapping cannot change while requests are being served.

A path can traverse several values. `apply` transforms every focus, in traversal order:

```java
record Directory(List<User> users) {}

@TelescopeTransformer
interface NormalizeNamesTransformer extends TelescopeTransformation<Directory, String> {
  default Telescope<Directory, String> path() {
    return Telescope.of(Directory.class).each(Directory::users).field(User::name);
  }

  default Transformation<String> transform() {
    return new Transformation<>("(unnamed)", (name) -> name.toLowerCase(Locale.ROOT));
  }
}
```

An empty `users` list has no focus and remains empty. A null name in an existing user becomes `"(unnamed)"`; a null user
has no name focus. To transform a whole collection, focus on the collection itself with a type such as
`TelescopeTransformation<Directory, List<User>>`. An empty collection is a non-null value, so its operation runs.

`TelescopeTransformation<S, A>` inherits `path()`, `read`, `find`, `toList`, `set`, and `update` from
`TelescopePath<S, A>`. `apply(source)` uses the declared transformation. `update(source, fn)` accepts a caller supplied
function for an ad hoc edit. You can use the complete Telescope API through `path()`.

Both default factories run once when Spring constructs the bean. They must return non-null values and must not depend on
field injection. The generated component uses Spring's default singleton scope. The default is one shared instance:
every null focus receives that same instance, so the results share it even on a single thread. Use an immutable default,
and keep the operation thread safe because the singleton shares it too.

Add the processor alongside the starter (Gradle):

```kotlin
dependencies {
    implementation(project(":spring-boot-starter"))
    annotationProcessor(project(":codegen"))
}
```

For an external application, use the same released Telescope version for both artifacts. The annotation processor is a
compile-time dependency; the starter remains the runtime dependency. Put the interfaces in the application's component
scan packages. Their generated implementation classes are Spring components. Use the annotation's optional `value` to
name a bean and `@Qualifier` to inject it by name.

When the mapper calls a generated `<Source>Bridge`, bridge pairing, defaults, and conversions stay on that bridge. A
null input maps to null. Mapper methods can be inherited from a generic interface. Transform declarations can inherit
typed default factories through a specialized base interface. Blueprints must be top-level, non-generic, non-sealed
interfaces. Transform interfaces conventionally end in `Transformer`; the processor does not require it.

`TelescopeMapperRegistry` indexes only `Mapper<?, ?>` beans, and `@TelescopeMapper` beans are deliberately not in it. A
projection is not a `Mapper`: registered as one, `registry.get(...).forward(...)` would either skip the projection's
transformers or change what the registry returns. An application that also declares a `Mapper` bean for the same pair
would fail at startup with a duplicate-pair error. Inject the projection by its interface type instead; use the registry
for polymorphic dispatch over plain `Mapper` beans.

Bridge-backed, the generated implementation is a typed call to `<Source>Bridge` and needs no native-image config of its
own. A `TelescopeProjection` with no matching `@Bridge` builds a runtime `Mapper` at construction instead — AOT-capable,
but your source/target types then need the same `reflect-config` (or Spring's `@RegisterReflectionForBinding`) any
runtime-mapped DTO needs. See [`docs/native-image.md`](../docs/native-image.md) for the full contract.

---

Drop-in Spring Boot 4 auto-config for [telescope](../README.md). Adds a typed `Mapper<A, B>` registry and supports
injectable interfaces generated from `@TelescopeMapper` blueprints.

```kotlin
dependencies {
    implementation("io.github.eschizoid:telescope-spring-boot-starter:1.1.1")
}
```

No `@EnableTelescope` annotation is needed. If you declare `Mapper<Order, OrderEntity>` beans in a `@Configuration`,
they show up in the registry, which resolves them by `(sourceClass, targetClass)` pair.

## What you get

- **`TelescopeMapperRegistry`** — auto-built `@Bean`, indexes every `Mapper<?, ?>` Spring can resolve into the context.
  Polymorphic dispatch: generic services receive `Object` and convert via
  `registry.get(src.getClass(), Target.class).forward(src)` without enumerating type pairs.
- **`TelescopeProperties`** — `@ConfigurationProperties("telescope")` for the `telescope.registry.fail-fast` toggle.
- **`@TelescopeMapper` / `@TelescopeTransformer`** — Spring interface annotations for a bridge-backed mapper or a cached
  transformation. They require `telescope-codegen` on the annotation-processor path.

## Install

```kotlin
// Gradle (Spring Boot 4 BOM picks up Boot's version)
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.1"))
    implementation("io.github.eschizoid:telescope-spring-boot-starter:1.1.1")
    annotationProcessor("io.github.eschizoid:telescope-codegen:1.1.1")
}
```

```xml
<!-- Maven -->
<dependency>
  <groupId>io.github.eschizoid</groupId>
  <artifactId>telescope-spring-boot-starter</artifactId>
  <version>1.1.1</version>
</dependency>
```

Configure the codegen processor in Maven as well:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <version>3.14.0</version>
      <configuration>
        <annotationProcessorPaths>
          <path>
            <groupId>io.github.eschizoid</groupId>
            <artifactId>telescope-codegen</artifactId>
            <version>1.1.1</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

The `telescope` core library comes along transitively (`api` scope).

## Minimum viable example

```java
@Configuration
class MapperConfig {

  @Bean
  Mapper<Order, OrderEntity> orderMapper() {
    return Telescope.mapper(Order.class, OrderEntity.class);
  }

  @Bean
  Mapper<Order, OrderDto> orderDtoMapper() {
    return Telescope.mapper(Order.class, OrderDto.class);
  }
}

@Service
class OrderConverter {

  private final TelescopeMapperRegistry registry;

  OrderConverter(TelescopeMapperRegistry registry) {
    this.registry = registry;
  }

  // Polymorphic conversion — no switch over known type pairs.
  <A, B> B convert(A src, Class<B> targetClass) {
    @SuppressWarnings("unchecked")
    Mapper<A, B> mapper = (Mapper<A, B>) registry.get(src.getClass(), targetClass);
    return mapper.forward(src);
  }
}
```

## Configuration

| Property                       | Default | Effect                                                                                                                                                                                      |
| ------------------------------ | ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `telescope.registry.fail-fast` | `true`  | When `true`, `registry.get(srcCls, tgtCls)` throws `IllegalArgumentException` on a missing type pair. When `false`, returns `null`. Either way, `registry.find(...)` returns an `Optional`. |

`application.yaml`:

```yaml
telescope:
  registry:
    fail-fast: false # return null instead of throwing on missing pair
```

## Overrides

The auto-config bean is `@ConditionalOnMissingBean` — declare your own `@Bean TelescopeMapperRegistry` to suppress the
default. Useful when you want to wrap the registry with logging, metrics, or a multi-tenant variant.

## Duplicate-pair behaviour

Defining two beans for the same `(srcClass, tgtClass)` pair fails at registry construction with `IllegalStateException`
— the pair must uniquely identify a mapper. If you genuinely have two semantically-different mappers for the same pair,
qualify them with Spring `@Qualifier`s and `@Autowired` the specific bean instead of going through the registry.

## Demo

See [`examples/springboot/order-jpa`](../examples/springboot/order-jpa) for the full integration flow: REST controller →
registry lookup → Telescope mapper → Hibernate persist. The same project covers JPA cycles, Hibernate LAZY proxy unwrap,
sparse-PATCH composition, and the sealed-narrow paradigm hop.

## Quarkus equivalent

`telescope-quarkus` ships the same registry shape via CDI producers — see [`quarkus`](../quarkus/README.md) for the
Quarkus 3 equivalent.
