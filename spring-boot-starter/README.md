# telescope-spring-boot-starter

## Injectable mapper and path interfaces

Keep mapping rules on the model's existing `@Bridge`. The Spring annotations generate injectable interfaces that call
that bridge or hold a reusable path:

```java
public record Address(String city) {}

public record UserDto(String name, Address address) {}

@Bridge(value = UserDto.class, defaults = @Default(field = "name", value = "(unnamed)"))
public record User(String name, Address address) {}

@TelescopeMapper(from = User.class, to = UserDto.class)
public interface UserProjection {
  UserDto map(User user);
}

@TelescopeTransform(from = User.class, to = String.class, path = "address.city")
public interface UserCity extends TelescopePath<User, String> {}

@Service
class UserService {

  private final UserProjection projection;
  private final UserCity city;

  UserService(UserProjection projection, UserCity city) {
    this.projection = projection;
    this.city = city;
  }

  UserDto project(User user) {
    return projection.map(user);
  }

  User relocate(User user) {
    return city.update(user, String::toUpperCase);
  }
}
```

`@Default` is the existing bridge rule: a null `User.name` becomes `"(unnamed)"` in the DTO. The Spring mapper
annotation only exposes the bridge as a bean. A null input maps to null.

For a deeper update, declare the path once and inject it wherever it is needed:

```java
record Workspace(Profile profile) {
  record Profile(Contact contact) {}

  record Contact(String email) {}
}

@TelescopeTransform(from = Workspace.class, to = String.class, path = "profile.contact.email")
interface WorkspaceEmail extends TelescopePath<Workspace, String> {}

// In a service with an injected WorkspaceEmail email:
Workspace changed = email.update(workspace, String::toLowerCase);

String current = email.read(changed);
```

Null behavior follows the underlying path: a null terminal email can be read as null and replaced with `set`. If an
intermediate record such as `profile` or `contact` is null, `read` throws `NoSuchElementException`; `set` and `update`
leave the source unchanged because the path has no focus. `read(null)` also throws `NoSuchElementException`. The
`update` function must handle a null terminal value if that is possible in the model.

Add the processor alongside the starter (Gradle):

```kotlin
dependencies {
    implementation(project(":spring-boot-starter"))
    annotationProcessor(project(":codegen"))
}
```

For an external application, use the same released Telescope version for both artifacts. The annotation processor is a
compile-time dependency; the starter remains the runtime dependency.

Both interfaces must be in the application's component-scan packages. The mapper implementation calls the generated
`UserBridge.forward` directly; this first version expects the source's single model-anchored `@Bridge`.
`@TelescopeTransform` checks each record field in its dotted path at compile time and creates the `Telescope` path once
per bean. This first path form supports record field hops; collection traversal and bean properties remain future work.
Generated interface beans are injected by their interface type; the existing `TelescopeMapperRegistry` continues to
index `Mapper<?, ?>` beans declared separately.

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
- **`@TelescopeMapper` / `@TelescopeTransform`** — Spring-only interface annotations for an injectable bridge-backed
  mapper or a cached typed path. They require `telescope-codegen` on the annotation-processor path.

## Install

```kotlin
// Gradle (Spring Boot 4 BOM picks up Boot's version)
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.1"))
    implementation("io.github.eschizoid:telescope-spring-boot-starter:1.1.1")
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
