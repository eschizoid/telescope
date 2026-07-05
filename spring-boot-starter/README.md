# telescope-spring-boot-starter

Drop-in Spring Boot 4 auto-config for [telescope](../README.md). Adds one bean to your application context — a typed
`Mapper<A, B>` registry — and nothing else.

```kotlin
dependencies {
    implementation("io.github.eschizoid:telescope-spring-boot-starter:1.1.1")
}
```

No `@EnableTelescope` annotation. No config class to author. The starter declares `@Mapper<Order, OrderEntity>` beans in
your `@Configuration` and they show up in the registry; the registry resolves them by `(sourceClass, targetClass)` pair.

## What you get

- **`TelescopeMapperRegistry`** — auto-built `@Bean`, indexes every `Mapper<?, ?>` Spring can resolve into the context.
  Polymorphic dispatch: generic services receive `Object` and convert via
  `registry.get(src.getClass(), Target.class).forward(src)` without enumerating type pairs.
- **`TelescopeProperties`** — `@ConfigurationProperties("telescope")` for the `telescope.registry.fail-fast` toggle.

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
