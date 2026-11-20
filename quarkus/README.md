# telescope-quarkus

`telescope-quarkus` is a Quarkus 3 extension for the [telescope](../README.md) mappers an application defines. It adds a
registry, `TelescopeMapperRegistry`, that finds a `Mapper<A, B>` by its source class and target class, and it maps
request and response bodies on Quarkus REST: a resource method returns its domain type and `@TelescopeResponse` names
the type the client receives; see [Mapped request and response bodies](#mapped-request-and-response-bodies). Both are
the Quarkus counterparts of the [Spring Boot starter](../spring-boot-starter/README.md)'s. With `telescope-codegen` on
the annotation processor path, `@TelescopeMapper` and `@TelescopeTransformer` interfaces also become CDI beans; see
[Generated mapper and transformer beans](#generated-mapper-and-transformer-beans).

The extension's build steps ship in `telescope-quarkus-deployment`, which Quarkus resolves on its own from the runtime
jar's extension descriptor. An application adds only `telescope-quarkus`, and needs no annotation to switch it on and no
entry in `application.properties`.

## Install

The module is built against Quarkus 3.40.1 and needs Java 21 or later.

```kotlin
// Gradle
dependencies {
    implementation("io.github.eschizoid:telescope-quarkus:2.2.0")
}
```

```xml
<!-- Maven -->
<dependency>
  <groupId>io.github.eschizoid</groupId>
  <artifactId>telescope-quarkus</artifactId>
  <version>2.2.0</version>
</dependency>
```

The artifact brings in `telescope-core` and `io.quarkus:quarkus-arc`, and it imports the Quarkus 3.40.1 platform BOM to
set the `quarkus-arc` version. Your application's own Quarkus BOM normally sets the Quarkus versions you get. The mapped
bodies need `quarkus-rest` with a JSON provider such as `quarkus-rest-jackson`, which a REST application has already,
and validate as read when `quarkus-hibernate-validator` is present.

## Example

You register a mapper with a CDI producer method or producer field that supplies it. `Mapper` is a final class with no
public constructor, so a class of your own can't be a `Mapper` bean. The example uses `@Singleton`, which gives one
shared instance with no proxy object in front of it.

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

## Mapped request and response bodies

A resource method returns the type its service gives it, and `@TelescopeResponse` beside `@GET` names the type the
client receives. Quarkus REST writes the mapped value; a Java caller of the method still receives what it returned.

```java
@GET
@Path("/{number}")
@TelescopeResponse(to = OrderV2Dto.class)
public Order get(@PathParam("number") String number) {
  return desk.get(number);
}
```

A body parameter annotated `@TelescopeRequestBody(from = ...)` is read as the type the client sends, validated as read
when it carries `@Valid`, and mapped to the parameter's type before the method runs. A violation is the usual `400`
naming the wire type's fields. A `TelescopeRequest<S, T>` parameter carries the body as read and as mapped, and takes
both types from its type arguments.

```java
@POST
@TelescopeResponse(to = OrderV2Dto.class)
public RestResponse<Order> place(@TelescopeRequestBody(from = PlaceOrderV2.class) @Valid PlaceOrder command) {
  final var order = desk.place(command);
  return RestResponse.ResponseBuilder.<Order>created(URI.create("/v2/orders/" + order.getNumber()))
    .entity(order)
    .build();
}
```

| Body     | Shapes                                                                                                                                                            |
| -------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| response | `S`, `List<S>`, `Collection<S>`, `Set<S>`, `Optional<S>`, a `RestResponse` of one of those, and a `Uni`, `CompletionStage` or `CompletableFuture` of any of these |
| request  | `T`, `List<T>`, `Collection<T>`, `Set<T>`, or `TelescopeRequest<S, T>`                                                                                            |

The mapper for each pair is found the way the Spring starter finds it: the projection `using` names, else the one
`TelescopeProjection` bean for the pair, else the `Mapper` bean the registry holds for it, else
`Telescope.mapperForward`, which takes a generated `@Bridge` or the same-name forward mapping. Two candidates are
refused unless `using` picks one.

The build reads the annotations from the Jandex index and refuses a misconfigured method with the method named, for
example a bare `Response`, which names no entity type (return `RestResponse<S>`), or a `Multi`, whose elements are
written as they arrive (return `Uni<List<S>>`). The mappers are resolved once, at startup, and a missing or ambiguous
one fails the application with the method named. In dev and test mode the build runs when the application boots, so
every refusal is a boot failure there.

The build registers each type a body is read or written as for reflection, and the generated bridge of each pair, so a
native image writes an `OrderV2Dto` that no resource signature names with no reflection configuration of the
application's own. In any application, mapped bodies or not, it also registers telescope's service providers, which a
Quarkus native image finds only when registered, and the method references a `@Bridge` field's rows and the generated
`@TelescopeMapper` and `@TelescopeTransformer` beans decode, so every `@Bridge` field runs its generated bridge there. A
pair with no projection or mapper bean maps through `Telescope.mapperForward`, as on the Spring side, and at startup
each such body logs at DEBUG, under `io.github.eschizoid.telescope.quarkus.rest`, whether a generated bridge's provider
is registered for its pair. Quarkus walks a registered type's nested records through its Jandex index, so DTOs in a
module that carries no index need it indexed, for example with `quarkus.index-dependency`, as any registration does. The
mapped bodies are not documented by SmallRye OpenAPI yet: annotate such a method with MicroProfile OpenAPI's
`@APIResponse` and `@RequestBody` to document the wire type.

## What the module contains

The module adds these public types:

- `TelescopeMapperRegistry` holds the `Mapper<?, ?>` beans in the application, keyed by its source class and target
  class.
- `TelescopeProducer` is an `@ApplicationScoped` bean with a producer method that builds the registry as an
  `@ApplicationScoped` bean. The method receives the `Mapper` beans through ArC's `@All List<Mapper<?, ?>>` injection.
- `TelescopeConfig` is a `@ConfigMapping(prefix = "telescope")` interface that holds the `telescope.registry.fail-fast`
  setting.
- `TelescopeResponse`, `TelescopeRequestBody` and `TelescopeRequest`, in `io.github.eschizoid.telescope.quarkus.rest`,
  declare the mapped bodies.

The jar includes a pre-built `META-INF/jandex.idx`. Quarkus reads that index to find the producer, so the jar needs no
`beans.xml` and Quarkus does not warn that the archive has no Jandex index.

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
`IllegalStateException` with a message that starts `Duplicate Mapper for type pair`. Qualifiers don't help, because
`@All` collects `Mapper` beans whatever their qualifiers. Keep one `Mapper` bean per pair, and either declare the other
with `@DefaultBean` and inject it by its qualifier, wrap it in your own type, or build it where it's used.

`@All` resolves the whole set of `Mapper` beans at once, across every pair. A `@DefaultBean` mapper is dropped whenever
any other `Mapper` bean exists, so a `@DefaultBean` mapper that is alone on its pair is missing from the registry too.
`@Alternative` doesn't help either: once an alternative is present, every `Mapper` bean that is not one is dropped.

## Replacing the registry

`TelescopeProducer` is not a default bean, so a second plain producer of `TelescopeMapperRegistry` makes the injection
ambiguous and Quarkus fails at build time. To supply your own registry, annotate your producer method with
`@Alternative` and `@Priority`, which makes CDI choose it over the one in this module.

## Generated mapper and transformer beans

`@TelescopeMapper` and `@TelescopeTransformer` turn an interface into an injectable bean, the same way they do in the
[Spring Boot starter](../spring-boot-starter/README.md#generated-mapper-and-transformer-beans), which describes the
annotations, `TelescopeProjection`, `translate` and transformers in full. Add `telescope-codegen` as an annotation
processor:

```kotlin
// Gradle
dependencies {
    implementation("io.github.eschizoid:telescope-quarkus:2.2.0")
    annotationProcessor("io.github.eschizoid:telescope-codegen:2.2.0")
}
```

With CDI on the classpath and Spring absent, the processor writes `<Interface>Impl` next to each annotated interface as
an `@ApplicationScoped` bean. You inject the interface:

```java
@TelescopeMapper("customers")
public interface CustomerProjection extends TelescopeProjection<Customer, CustomerDto> {}

@ApplicationScoped
public class CustomerResource {

  @Inject
  CustomerProjection customers;
}
```

The beans behave as the Spring ones do:

- A mapper maps through the source's generated `@Bridge` when there is one and `translate` is not overridden, and
  otherwise builds a core `Mapper` once, when the bean is created.
- A transformer builds its `path()` and `transform()` once per bean, and never for the client proxy in front of it.
- The annotation's value becomes a `@Named` qualifier. The bean still injects by its type alone.
- A projection collects every `TelescopeCustomizer<P>` bean for its own interface through ArC's `@All`, after the
  transformers declared on `@TelescopeMapper`. ArC sorts that list by priority, highest first, so `@Priority` orders the
  customizers. Once the bean is created its transformers are fixed, and `addTransformer` throws.

```java
@Singleton
public class CustomerCustomizers {

  @Produces
  @Priority(10)
  TelescopeCustomizer<CustomerProjection> trimFirst(CustomerNameTransformer trim) {
    return (projection) -> projection.addTransformer(trim);
  }
}
```

The generated beans are not `Mapper` beans, so `TelescopeMapperRegistry` does not hold them. Quarkus finds them through
their `@ApplicationScoped` annotation when it indexes your application. The pre-built index in this module's jar covers
only the module's own classes. An interface declared in a separate library jar needs that jar indexed, as any bean there
does, for example with the Jandex Gradle or Maven plugin or `quarkus.index-dependency`.

The processor picks the container by the telescope starter on the classpath, so with this module present it writes a CDI
bean even when the Spring annotations are on the classpath as well, for example through Quarkus's Spring compatibility
extensions. A module with both telescope starters is refused until you choose the bean model with
`-Atelescope.beans=cdi` or `-Atelescope.beans=spring`, and that option overrides the starter in any build.

## What the module does not include

The module has no `telescope.default-write-strategy` setting.

It does not install [`telescope-jackson`](../jackson/README.md)'s `TelescopeModule`: Quarkus 3 ships Jackson 2
(`com.fasterxml.jackson`), and the module is a Jackson 3 module. The streaming `JsonBinder` needs only Jackson 3's
`jackson-core`, whose packages do not overlap Jackson 2's, so it runs in a Quarkus application; the order-service demo
reads its partner import through it.

The tests in this module are unit tests of `TelescopeMapperRegistry`, which is plain Java with no CDI dependency, and
`@QuarkusComponentTest` classes that run `TelescopeProducer` and the generated beans in an ArC container. The mapped
bodies are tested in [`tests`](tests/), where each test class boots a Quarkus REST application with `QuarkusUnitTest`:
one for the return shapes, one for the request bodies and their validation, one for the places a mapper comes from, and
one for each refusal. The [order-service demo](../examples/demo/quarkus/README.md) checks the wiring end to end: its
`@QuarkusTest` starts the app and calls its mapped endpoints, and the native-image workflow makes the same calls on the
app built as a native executable.
