# telescope-quarkus

Drop-in Quarkus 3 CDI extension for [telescope](../README.md). Adds one CDI bean — a typed `Mapper<A, B>` registry — and
nothing else. Mirrors the [`spring-boot-starter`](../spring-boot-starter/README.md) surface for Quarkus apps.

```kotlin
dependencies {
    implementation("io.github.eschizoid:telescope-quarkus:0.4.1")
}
```

No `@EnableTelescope` annotation. No `application.properties` you have to touch. Declare any `@Produces Mapper<A, B>`
method (or `@ApplicationScoped` class) and it shows up in the registry; the registry resolves them by
`(sourceClass, targetClass)` pair.

## What you get

- **`TelescopeMapperRegistry`** — auto-built `@ApplicationScoped` bean, indexes every `Mapper<?, ?>` ArC can resolve
  into the application. Polymorphic dispatch: generic services receive `Object` and convert via
  `registry.get(src.getClass(), Target.class).forward(src)` without enumerating type pairs.
- **`TelescopeProducer`** — CDI producer that uses ArC's `@All List<Mapper<?, ?>>` collector to inject every mapper
  bean.
- **`TelescopeConfig`** — `@ConfigMapping(prefix = "telescope")` interface for the `telescope.registry.fail-fast`
  toggle.

The jar ships a pre-built `META-INF/jandex.idx` so Quarkus skips the startup bytecode scan and avoids the "Application
archive ... is being scanned without a Jandex index" warning.

## Install

```kotlin
// Gradle (Quarkus 3 BOM picks up Quarkus's version)
dependencies {
    implementation(platform("io.quarkus.platform:quarkus-bom:3.20.0"))
    implementation("io.github.eschizoid:telescope-quarkus:0.4.1")
}
```

```xml
<!-- Maven -->
<dependency>
  <groupId>io.github.eschizoid</groupId>
  <artifactId>telescope-quarkus</artifactId>
  <version>0.4.1</version>
</dependency>
```

The `telescope` core library comes along transitively (`api` scope).

## Minimum viable example

```java
@ApplicationScoped
public class MapperProducers {

  @Produces
  @ApplicationScoped
  Mapper<Order, OrderEntity> orderMapper() {
    return Telescope.mapper(Order.class, OrderEntity.class);
  }

  @Produces
  @ApplicationScoped
  Mapper<Order, OrderDto> orderDtoMapper() {
    return Telescope.mapper(Order.class, OrderDto.class);
  }
}

@ApplicationScoped
public class OrderConverter {

  @Inject
  TelescopeMapperRegistry registry;

  // Polymorphic conversion — no switch over known type pairs.
  public <A, B> B convert(A src, Class<B> targetClass) {
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

`application.properties`:

```properties
telescope.registry.fail-fast=false
```

## Overrides

To suppress the default registry (e.g. to provide your own), declare your own `@Produces TelescopeMapperRegistry` bean.
CDI's alternatives / `@Specializes` picks yours over ours.

## Duplicate-pair behaviour

Defining two beans for the same `(srcClass, tgtClass)` pair fails at registry construction with `IllegalStateException`
— the pair must uniquely identify a mapper. If you genuinely have two semantically-different mappers for the same pair,
qualify them with `@Named` / `@Qualifier` and `@Inject` the specific bean instead of going through the registry.

## What's NOT here

This is a runtime-only extension — no separate `deployment` module, no `@BuildStep` recorders. Beans are discovered via
the pre-built `META-INF/jandex.idx` shipped in the jar (no `beans.xml` needed in modern Quarkus when an index is
present); the registry's index is built once at application bootstrap and then immutable. For most cases this is fine;
if you need build-time optimization for an enormous mapper graph, a follow-up split into `deployment` + `runtime` would
be the path.

`@QuarkusTest` integration tests aren't shipped — the registry is pure Java with no CDI dependency, so the existing unit
tests cover the behaviour. Add a `@QuarkusTest` in your own application if you want to validate the producer wiring
end-to-end.

## Spring Boot equivalent

`telescope-spring-boot-starter` ships the same registry shape via Spring's auto-config — see
[`spring-boot-starter`](../spring-boot-starter/README.md) for the Spring 4 equivalent.
