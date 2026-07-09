# Migrating from MapStruct to telescope

A working recipe for moving an existing MapStruct codebase to telescope **one mapper at a time** — no big-bang rewrite.
For the feature-by-feature reference (every MapStruct capability, the telescope idiom, and the honest limitations), see
the [parity matrix](mapstruct-parity.md). For the argument about _why_, run the head-to-head:
`./gradlew :examples:mapstruct-vs-telescope:test`.

## The shape of the change

A MapStruct mapper is an annotated interface whose implementation is generated at compile time. A telescope mapper is a
**value** you build and hold:

```java
// MapStruct — an annotated interface, implementation generated at compile time
@Mapper
public interface CustomerMapper {
  @Mapping(source = "email", target = "contactEmail")
  CustomerDto toDto(Customer customer);

  Customer fromDto(CustomerDto dto); // the reverse direction is a second method
}
```

```java
// telescope — one value, both directions
import static io.github.eschizoid.telescope.mapping.Mapping.to;

Mapper<Customer, CustomerDto> MAPPER = Telescope.mapper(
  Customer.class,
  CustomerDto.class,
  to(Customer::email, CustomerDto::getContactEmail)
);
// MAPPER.forward(customer) and MAPPER.backward(dto) — no second method
```

Strings become typed method references (the compiler checks them, the IDE refactors them), the reverse direction is
free, and the mapper can [explain and trace itself](../README.md) — which is also how you _verify_ each migration step
below.

## Coexistence: migrate one mapper at a time

MapStruct and telescope run side by side indefinitely — they don't know about each other:

1. Keep `mapstruct` + its processor on the build; add `telescope-core`.
2. Pick one MapStruct mapper. Translate it (table below). Expose the telescope `Mapper<A, B>` wherever the old bean was
   injected — in Spring / Quarkus, declare it as a `@Bean` / `@Produces` and the starter's `TelescopeMapperRegistry`
   indexes it by `(sourceClass, targetClass)` automatically.
3. Delete the MapStruct interface once its call sites are moved. Repeat.

Nothing forces order: leave the long tail on MapStruct as long as you like.

## Translation table

| MapStruct                                              | telescope                                                                                                                                                                                                                |
| ------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| implicit same-name mapping                             | implicit too — `Telescope.mapper(A.class, B.class)` with zero rows deep-recurses by name                                                                                                                                 |
| `@Mapping(source = "a", target = "b")`                 | `to(A::a, B::getB)`                                                                                                                                                                                                      |
| `@Mapping(source = "x.y.z", target = "flat")`          | `to(Telescope.of(A.class).field(A::x).field(X::y).field(Y::z), B::getFlat)`                                                                                                                                              |
| `@Mapping(target = "t", ignore = true)`                | `drop(A::t)` for a source-side field; a target-only field takes `constant(B::getT, …)` or the lenient `mapperForward(...)`                                                                                               |
| `@Mapping(expression = "java(...)")`                   | `to(A::src, B::getT, a -> compute(a), b -> invert(b))` — a typed function, not a string of Java                                                                                                                          |
| `@Mapping(constant = "fixed")` / `defaultValue`        | `constant(B::getT, "fixed")` / `toOrElse(A::x, B::getX, fallback)`                                                                                                                                                       |
| type conversion (source `String` → target `LocalDate`) | `to(A::birthDate, B::getBirthDate, LocalDate::parse, LocalDate::toString)` — forward parses, backward formats                                                                                                            |
| nested type reuse / `@Mapper(uses = …)`                | `via(A::address, B::getAddress, addressMapper)`                                                                                                                                                                          |
| `@AfterMapping` / `@BeforeMapping`                     | `mapper.afterForward((src, dst) -> …)` / `mapper.beforeForward(…)` — returns a new immutable mapper                                                                                                                      |
| `void update(@MappingTarget E e, D d)`                 | `mapper.into(entity, dto)` (silent-skip on missing setters, same semantics) / `mapper.patch(…)`                                                                                                                          |
| `CustomerDto toDto(Customer c, Address a)`             | `Telescope.merge(CustomerDto.class, auto(Customer.class), from(Address::city, CustomerDto::getCity))`                                                                                                                    |
| `unmappedTargetPolicy = ERROR`                         | free — strict `mapper(...)` refuses unmapped fields at construction (and at **compile time** with `telescope-codegen` on the processor path)                                                                             |
| `unmappedTargetPolicy = WARN` (the default)            | `Telescope.mapperForward(A.class, B.class)` — lenient one-way like MapStruct's default at runtime (silently, i.e. IGNORE; for a build-time warning instead, keep strict `mapper(...)` and set `-Atelescope.verify=warn`) |
| `componentModel = "spring"/"cdi"`                      | plain `@Bean` / `@Produces Mapper<A, B>` + the starter's `TelescopeMapperRegistry`                                                                                                                                       |
| generated `…MapperImpl` you read to debug              | `mapper.explain()` / `mapper.trace(input)` / flip a log level — [introspection](../README.md)                                                                                                                            |

Every mapping row above is expanded — with evidence and limitations — in the [parity matrix](mapstruct-parity.md).

## Verify each step (don't trust the diff, assert it)

telescope's introspection makes each migrated mapper self-checking:

```java
import io.github.eschizoid.telescope.introspection.OpticNode.Mapped;

// the migrated mapper maps everything the old one did — nothing silently dropped
assertThat(mapper.explain().skipped()).isEmpty();

// the rename you carried over is real, enumerable data
assertThat(mapper.explain().mapped()).contains(new Mapped("email", "contactEmail"));

// and for one golden input, old and new agree
assertThat(mapper.forward(sample)).isEqualTo(oldMapStructMapper.toDto(sample));
```

That last equality assertion — run once per migrated mapper against the still-present MapStruct implementation — is the
cheapest possible safety net, and you delete it together with the old interface.

## Gotchas worth knowing up front

- **Strict vs lenient is a choice, not a default fight.** `Telescope.mapper(...)` is strict (bidirectional, refuses
  unmapped fields); `Telescope.mapperForward(...)` is lenient one-way and matches MapStruct's default. Migrating a
  mapper often _surfaces_ fields MapStruct was silently nulling — that's the point, not a regression.
- **Multiple source parameters** go through `Telescope.merge(...)` with a `Sources` bag — the source-class check happens
  at `forward(...)` time, not at the method signature (see the matrix row for details).
- **Row-group reuse** (`MapperBuilder.inherit`) currently applies only across mappers of the **same** (source, target)
  pair — a group inherited into a different pair is silently dropped
  ([#223](https://github.com/eschizoid/telescope/issues/223)).
- **Multi-constructor immutables** (MapStruct's `@Default`) aren't disambiguated — records and single-constructor
  classes are the supported shapes.
- **Hot loops:** the runtime path is reflective (fast enough outside hot paths); annotate the pair with
  `@Focus`/`@Bridge` and the codegen path is reflection-free.
