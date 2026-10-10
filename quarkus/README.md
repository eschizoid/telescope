# telescope-quarkus

`telescope-quarkus` adds one CDI bean to a Quarkus 3 application, a registry of the [telescope](../README.md) mappers
the application defines. The registry, `TelescopeMapperRegistry`, finds a `Mapper<A, B>` by its source class and target
class. It is the Quarkus counterpart of the registry in the [Spring Boot starter](../spring-boot-starter/README.md).

The module is a small runtime library. It has no `deployment` module and no build steps, and it does not need an
annotation to switch it on or any entry in `application.properties`.

## Install

The module is built against Quarkus 3.40.1 and needs Java 21 or later.

```kotlin
// Gradle
dependencies {
    implementation("io.github.eschizoid:telescope-quarkus:2.0.0")
}
```

```xml
<!-- Maven -->
<dependency>
  <groupId>io.github.eschizoid</groupId>
  <artifactId>telescope-quarkus</artifactId>
  <version>2.0.0</version>
</dependency>
```

The artifact brings in `telescope-core` and `io.quarkus:quarkus-arc`, and it imports the Quarkus 3.40.1 platform BOM to
set the `quarkus-arc` version. Your application's own Quarkus BOM normally sets the Quarkus versions you get.

## What the module contains

The module adds these public types:

- `TelescopeMapperRegistry` holds every `Mapper<?, ?>` bean in the application, keyed by its source class and target
  class.
- `TelescopeProducer` is an `@ApplicationScoped` bean with a producer method that builds the registry as an
  `@ApplicationScoped` bean. The method receives every `Mapper` bean through ArC's `@All List<Mapper<?, ?>>` injection.
- `TelescopeConfig` is a `@ConfigMapping(prefix = "telescope")` interface that holds the `telescope.registry.fail-fast`
  setting.

The jar includes a pre-built `META-INF/jandex.idx`. Quarkus reads that index to find the producer, so the jar needs no
`beans.xml` and Quarkus does not warn that the archive has no Jandex index.

## Example

You register a mapper by writing a CDI producer method that returns it. `Mapper` is a final class with no public
constructor, so a class of your own cannot be a `Mapper` bean, and a producer method is the way to add one. The example
uses `@Singleton` because a pseudo-scope needs no client proxy.

```java
@ApplicationScoped
public class MapperProducers {

  @Produces
  @Singleton
  Mapper<Order, OrderEntity> orderEntityMapper() {
    return Telescope.mapper(Order.class, OrderEntity.class);
  }

  @Produces
  @Singleton
  Mapper<Order, OrderDto> orderDtoMapper() {
    return Telescope.mapper(Order.class, OrderDto.class);
  }
}
```

When the target type is known where you write the code, inject the mapper directly, for example
`@Inject Mapper<Order, OrderDto> orderDtoMapper`. The registry is for code that only learns the source class at run
time. The converter below takes any source object and looks up the mapper for that object's class and the requested
target class:

```java
@ApplicationScoped
public class Converter {

  @Inject
  TelescopeMapperRegistry registry;

  public <B> B convert(Object source, Class<B> targetClass) {
    @SuppressWarnings("unchecked")
    Mapper<Object, B> mapper = (Mapper<Object, B>) registry.get(source.getClass(), targetClass);
    return mapper.forward(source);
  }
}
```

The lookup uses the exact class. A subclass of `Order`, or a proxy class that wraps one, does not find the
`Mapper<Order, OrderDto>`.

## Registry methods

| Method                     | Result                                                                                         |
| -------------------------- | ---------------------------------------------------------------------------------------------- |
| `get(source, target)`      | The mapper for the pair. On a missing pair it throws or returns `null`, as set by `fail-fast`. |
| `find(source, target)`     | An `Optional` holding the mapper, empty on a missing pair, whatever `fail-fast` says.          |
| `contains(source, target)` | `true` when a mapper is registered for the pair.                                               |
| `size()`                   | The number of registered mappers.                                                              |

`get` and `find` throw `NullPointerException` when either class is `null`. The registry is built when the bean is first
used, and its contents do not change after that.

## Configuration

The registry reads its setting from Quarkus configuration:

| Property                       | Default | Effect                                                                                                              |
| ------------------------------ | ------- | ------------------------------------------------------------------------------------------------------------------- |
| `telescope.registry.fail-fast` | `true`  | When `true`, `get` throws `IllegalArgumentException` for a pair with no mapper. When `false`, `get` returns `null`. |

To make `get` return `null`, add the line below to `application.properties`:

```properties
telescope.registry.fail-fast=false
```

The setting changes only what `get` does for a missing pair. Duplicate pairs fail either way, and `find` and `contains`
behave the same either way.

## Two mappers for the same pair

Each source and target pair can have only one mapper. If two `Mapper` beans share a pair, building the registry throws
`IllegalStateException` with a message that starts `Duplicate Mapper for type pair`. When you need two different mappers
for the same pair, give each a CDI qualifier such as `@Named` and inject the one you want directly.

## Replacing the registry

`TelescopeProducer` is not a default bean, so a second plain producer of `TelescopeMapperRegistry` makes the injection
ambiguous and Quarkus fails at build time. To supply your own registry, annotate your producer method with
`@Alternative` and `@Priority`, which makes CDI choose it over the one in this module.

## What the module does not include

The module has no counterpart to the Spring starter's `@TelescopeMapper` and `@TelescopeTransformer` generated beans,
which are Spring only. It also has no `telescope.default-write-strategy` setting.

The tests in this module are unit tests of `TelescopeMapperRegistry`, which is plain Java with no CDI dependency. No
`@QuarkusTest` in this repository starts Quarkus with the producer, so add one to your own application if you want to
check the wiring end to end.
