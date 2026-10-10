# telescope-spring-boot-starter

The Spring Boot starter for [telescope](../README.md) does two things. It collects every `Mapper<A, B>` bean in the
application context into a `TelescopeMapperRegistry`, so a service can look a mapper up by its source and target class.
It also lets the `telescope-codegen` annotation processor turn an interface annotated with `@TelescopeMapper` or
`@TelescopeTransformer` into a Spring bean that you inject like any other.

The starter is compiled and tested against Spring Boot 4.1.1 and needs Java 21 or later. It registers itself through
Spring Boot's autoconfiguration, so there is no `@Enable...` annotation to add.

## Install

Add the starter as a dependency. Add `telescope-codegen` as an annotation processor when you use `@TelescopeMapper`,
`@TelescopeTransformer` or `@Bridge`. The registry on its own needs only the starter. Use the same telescope version for
both artifacts.

```kotlin
// Gradle
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation("io.github.eschizoid:telescope-spring-boot-starter:2.0.0")
    annotationProcessor("io.github.eschizoid:telescope-codegen:2.0.0")
}
```

The `platform(...)` line imports Spring Boot's dependency versions, and you can leave it out when your build already
applies the Spring Boot plugin or imports the same BOM.

```xml
<!-- Maven -->
<dependency>
  <groupId>io.github.eschizoid</groupId>
  <artifactId>telescope-spring-boot-starter</artifactId>
  <version>2.0.0</version>
</dependency>
```

In Maven, put the processor on the compiler plugin's processor path:

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
            <version>2.0.0</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

The starter brings in `telescope-core` and `spring-boot-autoconfigure` as `api` dependencies, so you do not declare them
yourself.

## The mapper registry

The autoconfiguration creates one `TelescopeMapperRegistry` bean and fills it with every `Mapper` bean the context
holds. The registry indexes each mapper by its `sourceClass()` and `targetClass()`. A generic service can then convert a
value whose class it learns at run time, without a `switch` over every known pair:

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

  <A, B> B convert(A source, Class<B> targetClass) {
    @SuppressWarnings("unchecked")
    Mapper<A, B> mapper = (Mapper<A, B>) registry.get(source.getClass(), targetClass);
    return mapper.forward(source);
  }
}
```

When a service only ever needs one pair, inject `Mapper<Order, OrderEntity>` directly, because Spring already resolves
generic beans by type. The registry is for lookups where the pair is not known until run time.

### Registry methods

| Method                      | Returns                                                                                                |
| --------------------------- | ------------------------------------------------------------------------------------------------------ |
| `get(sourceClass, target)`  | The mapper. A missing pair throws `IllegalArgumentException`, or returns `null` when fail-fast is off. |
| `find(sourceClass, target)` | An `Optional` holding the mapper, empty for a missing pair whatever the fail-fast setting.             |
| `contains(source, target)`  | Whether a mapper is registered for the pair. It never throws.                                          |
| `size()`                    | The number of mappers in the registry.                                                                 |

The message for a missing pair names both classes and tells you to define a `@Bean Mapper<Source, Target>`.

### Two mappers for the same pair

The pair must identify one mapper. When two `Mapper` beans share a source and target class, building the registry throws
`IllegalStateException`, so the application context fails to start. The message names the pair. If you need two
different mappers for the same pair, give them `@Qualifier` names and inject the one you want by name.

### Replacing the registry

The registry bean is `@ConditionalOnMissingBean`. When you declare your own `@Bean TelescopeMapperRegistry`, for example
one that wraps the default with logging or metrics, the starter does not create its own. The autoconfiguration is also
`@ConditionalOnClass(Telescope.class)`, which always holds because the starter depends on `telescope-core`.

## Generated mapper and transformer beans

`@TelescopeMapper` and `@TelescopeTransformer` are declared in `telescope-core`, and so are the interfaces your beans
extend. None of them depends on Spring. The `TelescopeMapperProcessor` in `telescope-codegen` writes a class named
`<Interface>Impl` next to each annotated interface and marks it `@Component`, so the interface needs to sit in a package
that your application's component scan covers. If Spring is not on the classpath of the module that declares the
interface, the processor stops with this error on the interface:

```
@TelescopeMapper generates a Spring component; add telescope-spring-boot-starter to the classpath of the module that declares this interface
```

The examples below use these imports:

```java
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.TelescopeMapper;
import io.github.eschizoid.telescope.annotations.TelescopeTransformer;
import io.github.eschizoid.telescope.conversion.MapperBuilder;
import io.github.eschizoid.telescope.inject.TelescopeCustomizer;
import io.github.eschizoid.telescope.inject.TelescopeProjection;
import io.github.eschizoid.telescope.inject.TelescopeTransformation;
import io.github.eschizoid.telescope.inject.Transformation;
```

### Step by step

Each step adds one annotation or override to the step before it. `QuickstartExamplesTest` in this module compiles and
runs the same code.

**1. Describe the conversion with `@Bridge`, and inject it with `@TelescopeMapper`.** `@Bridge` makes the processor
write a `CustomerBridge` class at compile time. `@TelescopeMapper` on an interface with one `map` method makes the
processor write a Spring bean whose `map` calls that bridge:

```java
@Bridge(CustomerDto.class)
public record Customer(String name, Contact contact) {}

public record Contact(String email) {}

public record CustomerDto(String name, Contact contact) {}

@TelescopeMapper(from = Customer.class, to = CustomerDto.class)
public interface CustomerMapper {
  CustomerDto map(Customer customer);
}

@Service
class CustomerController {

  private final CustomerMapper mapper;

  CustomerController(CustomerMapper mapper) {
    this.mapper = mapper;
  }

  CustomerDto show(Customer customer) {
    return mapper.map(customer);
  }
}
```

**2. Map both ways with `TelescopeProjection`.** An interface that extends `TelescopeProjection<S, T>` gets `map`,
`forward`, `backward` and `patch`, and the annotation no longer needs `from` and `to`:

```java
@TelescopeMapper
public interface CustomerProjection extends TelescopeProjection<Customer, CustomerDto> {}

CustomerDto dto = projection.map(customer);

Customer back = projection.backward(dto); // equal to customer
```

A projection that maps through a bridge throws `UnsupportedOperationException` from `patch`. A bridge rebuilds the whole
source from the partial, so it cannot keep the base fields that the partial leaves null. Step 4 shows a projection that
patches.

**3. Clean up the source before mapping with `@TelescopeTransformer`.** A transformer is a typed path into the source
plus what to do at that path. The `Transformation` holds a default for a null value and an operation for a non-null
value. List the transformer on the mapper, and Spring injects it into the mapper bean:

```java
@TelescopeTransformer
public interface CustomerEmailTransformer extends TelescopeTransformation<Customer, String> {
  @Override
  default Telescope<Customer, String> path() {
    return Telescope.of(Customer.class).field(Customer::contact).field(Contact::email);
  }

  @Override
  default Transformation<String> transform() {
    return new Transformation<>("unknown@example.com", (email) -> email.strip().toLowerCase(Locale.ROOT));
  }
}

@TelescopeMapper(transformers = CustomerEmailTransformer.class)
public interface NormalizedCustomerProjection extends TelescopeProjection<Customer, CustomerDto> {}
```

The projection maps an email of `" ADA@EXAMPLE.COM "` to `"ada@example.com"` and a null email to
`"unknown@example.com"`. A null `contact` stays null, because there is no email to change. The customer passed in is
never modified. The transformer is also a bean of its own, so `email.apply(customer)` returns a cleaned copy and
`email.path().read(customer)` reads the raw value.

**4. Rename fields and add values with typed rows in `translate`.** When the target's field names differ from the
source's, or the target needs values the source does not hold, override `translate`. Each row names its fields with
method references, so an IDE rename updates the row and a type mismatch fails the build:

```java
public record CustomerSummary(String displayName, String source, Instant generatedAt) {}

@TelescopeMapper
public interface CustomerSummaryProjection extends TelescopeProjection<Customer, CustomerSummary> {
  @Override
  default void translate(MapperBuilder<Customer, CustomerSummary> mapping) {
    mapping
      .from(Customer::name)
      .to(CustomerSummary::displayName)
      .add(constant(CustomerSummary::source, "crm"), compute(CustomerSummary::generatedAt, Instant::now));
  }
}
```

`constant` and `compute` are static methods on `io.github.eschizoid.telescope.mapping.Mapping`. A projection that
overrides `translate` maps through a core `Mapper` instead of the bridge, so it also supports `patch`. `@Bridge` has
annotation equivalents (`@Rename`, `@Constant`, `@Compute`), but they name fields with strings, and an IDE rename does
not update a string.

### Bridge or core mapper

The processor picks what the generated bean maps through when it compiles the interface:

- A projection whose pair has a generated `@Bridge` and no `translate` override calls the bridge. The bridge's defaults,
  renames and conversions apply.
- A projection that overrides `translate`, directly or through a parent interface, builds a core `Mapper` once, when
  Spring constructs the bean. The `translate` rows are layered over same-name matching, and the bridge is not used even
  when one exists.
- A projection with no bridge and no override also builds a core `Mapper`, from same-name matching alone.
- An interface that declares only `map` always calls the bridge, and compilation fails with
  `@TelescopeMapper requires a generated @Bridge for <Source> -> <Target>` when there is none.

The processor finds a bridge declared on the source class, a bridge declared on a separate carrier class in the same
compilation, or a bridge class compiled into a dependency.

### What a projection does

A `TelescopeProjection<S, T>` bean offers the operations of a core `Mapper<S, T>`:

- `map` and `forward` run the transformers in order and then map. A null source maps to null.
- `backward` maps the target back to the source. Transformers do not run in that direction.
- `patch(base, partial)` copies `base` and overwrites each field that `partial` sets to a non-null value. Transformers
  do not run. It works only on a projection that maps through a core `Mapper`, as described under [Patching](#patching).
- `translate(MapperBuilder<S, T>)` accepts the same rows as `Telescope.mapperBuilder(...)`. `from(...).to(...)` pairs
  two accessors, and `add(...)` takes any `MapStep`, such as `constant` and `compute` from `Mapping`, `nullSourceValues`
  from `NullHint`, or `writeBeans` from `WriteHint`.

### Patching

`patch` on a projection backed by a core `Mapper` returns a new object every time, even when the partial is null or sets
no field. Only a null `base` returns null. The copy is shallow. A field the partial leaves null keeps the object that
`base` holds, so changing a nested mutable object or list in the result also changes it in `base`. A source type that no
write strategy can build, such as an enum or a sealed interface, makes `patch` throw `UnsupportedOperationException`. A
projection that maps through a bridge always throws `UnsupportedOperationException` from `patch`, and the message tells
you to override `translate`.

```java
@TelescopeMapper
public interface PatchableCustomerProjection extends TelescopeProjection<Customer, CustomerDto> {
  @Override
  default void translate(MapperBuilder<Customer, CustomerDto> mapping) {}
}

Customer base = new Customer("Ada", new Contact("ada@example.com"));

Customer patched = projection.patch(base, new CustomerDto("Bea", null));
// patched is Customer("Bea", Contact("ada@example.com")), and patched.contact() is base.contact()
```

An empty `translate` override is enough to move off the bridge.

### A mapper with only `map`

An interface that does not extend `TelescopeProjection` declares `map` and nothing else, and it names the pair with
`from` and `to`, which must be given together. It has no `forward`, `backward` or `patch`, and customizers cannot add
transformers to it. It still accepts `transformers`, which run in order before the bridge:

```java
@TelescopeMapper(from = Customer.class, to = CustomerDto.class, transformers = CustomerEmailTransformer.class)
public interface NormalizedCustomerMapper {
  CustomerDto map(Customer customer);
}
```

Use this form when you only need an injectable wrapper around an existing bridge.

### Registering transformers from configuration

When the transformers depend on configuration, declare a `TelescopeCustomizer` bean whose type argument is the
projection interface. Spring matches the customizer to the projection by that type, so you need no bean names,
`@Qualifier` or second projection bean:

```java
@TelescopeMapper
public interface ConfiguredCustomerProjection extends TelescopeProjection<Customer, CustomerDto> {}

@Configuration
class ProjectionConfig {

  @Bean
  TelescopeCustomizer<ConfiguredCustomerProjection> customerTransformers(CustomerEmailTransformer email) {
    return (projection) -> projection.addTransformer(email);
  }
}
```

Spring runs the customizers while it constructs the projection, after the transformers listed on `@TelescopeMapper`.
When several customizers target the same projection, they run in `@Order` order. A customizer only ever receives the
projection named in its type argument. Once construction ends, the transformer list is fixed, and a later call to
`addTransformer` throws `IllegalStateException`. Calling `map` from inside a customizer also throws
`IllegalStateException`, because the list is not fixed yet.

### How a transformation behaves

A path can reach several values, and `apply` transforms every one of them in traversal order:

```java
public record Directory(List<Customer> customers) {}

@TelescopeTransformer
public interface DirectoryNamesTransformer extends TelescopeTransformation<Directory, String> {
  @Override
  default Telescope<Directory, String> path() {
    return Telescope.of(Directory.class).each(Directory::customers).field(Customer::name);
  }

  @Override
  default Transformation<String> transform() {
    return new Transformation<>("(unnamed)", (name) -> name.toLowerCase(Locale.ROOT));
  }
}
```

The default and the operation follow these rules:

- The operation runs once for each non-null value, including an empty string. A null value is replaced by the default,
  and the default may itself be null.
- A path through a null intermediate object, such as a null `contact`, or through an empty list, reaches no value, so
  nothing is created or changed. A null customer in the list has no name to change.
- To change a whole collection, point the path at the collection, for example
  `TelescopeTransformation<Directory, List<Customer>>`. An empty list is a non-null value, so the operation runs on it.
- Every call with a null value writes the same default instance into its result. Use an immutable default, and keep the
  operation thread safe, because the singleton bean shares both.

`TelescopeTransformation<S, A>` also inherits `read`, `find`, `toList`, `set` and `update` from `TelescopePath<S, A>`.
`apply(source)` uses the declared transformation, while `update(source, fn)` takes a function you pass for a one-off
edit, and `path()` gives you the whole Telescope API.

The generated bean calls `path()` and `transform()` once, when Spring constructs it, and fails bean creation if either
returns null. Neither method may depend on field injection.

### Bean names, scope and interface rules

The generated class is a singleton `@Component`. Without a name, Spring names it after the class, for example
`customerProjectionImpl`. Pass a name as the annotation's `value`, as in `@TelescopeMapper("customers")` or
`@TelescopeTransformer("emailCleaner")`, and inject that bean with `@Qualifier("customers")`.

The processor checks the interface at compile time and reports an error when a rule is broken:

- The interface must be top level, non-generic and non-sealed. The source and target types must not be generic either.
- A mapper interface needs exactly one abstract method, named `map`, that takes the source and returns the target. The
  method can be inherited from a generic parent interface.
- Each class in `transformers` must be a `TelescopeTransformation` whose source type is the mapper's source type.
- A transformer interface needs default `path()` and `transform()` methods, which it can inherit from a parent
  interface, and no other abstract method. A name ending in `Transformer` is a convention that the processor does not
  check.

### Generated beans and the registry

Generated beans are not added to `TelescopeMapperRegistry`. `TelescopeProjection` does not extend `Mapper`, and the
registry indexes only `Mapper` beans. Inject a projection by its interface type, and use the registry for lookups over
plain `Mapper` beans.

### Native image

A generated mapper that calls a bridge makes a plain method call to the generated `<Source>Bridge` and needs no
native-image configuration for the mapping. A projection that builds a core `Mapper` uses the runtime path, so its
source and target types need the same reflection registration as any runtime-mapped type, for example through Spring's
`@RegisterReflectionForBinding`. A transformer path built with `.field(Type::accessor)` is a runtime path too. See
[`docs/native-image.md`](../docs/native-image.md) for what each path needs.

## Configuration

| Property                           | Default | Effect                                                                                                                  |
| ---------------------------------- | ------- | ----------------------------------------------------------------------------------------------------------------------- |
| `telescope.registry.fail-fast`     | `true`  | When `true`, `registry.get(...)` throws `IllegalArgumentException` for a missing pair. When `false`, it returns `null`. |
| `telescope.default-write-strategy` | none    | Bound by `TelescopeProperties`, but nothing reads it yet, so setting it has no effect.                                  |

`fail-fast` changes only what `get` does. `find` and `contains` behave the same either way, and a duplicate pair fails
at startup either way.

```yaml
telescope:
  registry:
    fail-fast: false # get returns null for a missing pair instead of throwing
```

## Example application

[`examples/springboot/product-starter`](../examples/springboot/product-starter) is a small Spring Boot application built
on this starter. It declares three `Mapper<Product, ?>` beans, and its controller picks one through
`registry.get(Product.class, ...)` according to a query parameter.

## Quarkus

[`telescope-quarkus`](../quarkus/README.md) offers the same registry through CDI. The generated `@TelescopeMapper` and
`@TelescopeTransformer` beans exist only for Spring.
