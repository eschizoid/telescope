# telescope ↔ MapStruct — feature parity matrix

An evidence-grounded audit of MapStruct's feature surface against telescope. Every row was produced by reading
telescope's actual source and tests (citations below), then adversarially re-verified by a second pass that re-opened
every citation — two verdicts were downgraded and two snippets corrected in that pass. Audited against MapStruct 1.6.3
semantics and telescope `main`.

**Legend:** ✅ full — the use case is covered, possibly via a different idiom that is no worse · ⚠️ partial — the core
use case works, with the real limitation stated in the row's notes · ❌ missing.

## Summary

| Area                      | Feature                                                             | Status |
| ------------------------- | ------------------------------------------------------------------- | :----: |
| Core mapping              | [Implicit same-name mapping](#implicit-same-name-mapping)           |   ✅   |
| Core mapping              | [Explicit rename](#explicit-rename)                                 |   ✅   |
| Core mapping              | [Nested source path](#nested-source-path)                           |   ✅   |
| Core mapping              | [Multiple source parameters](#multiple-source-parameters)           |   ⚠️   |
| Conversions               | [Implicit type conversions](#implicit-type-conversions)             |   ⚠️   |
| Conversions               | [Format strings](#format-strings)                                   |   ⚠️   |
| Conversions               | [Expressions](#expressions)                                         |   ✅   |
| Conversions               | [Constants and defaults](#constants-and-defaults)                   |   ✅   |
| Collections & containers  | [Collection element mapping](#collection-element-mapping)           |   ✅   |
| Collections & containers  | [Map mapping](#map-mapping)                                         |   ⚠️   |
| Collections & containers  | [Collection target strategies](#collection-target-strategies)       |   ⚠️   |
| Collections & containers  | [Stream support](#stream-support)                                   |   ⚠️   |
| Lifecycle & customization | [Before/after hooks](#beforeafter-hooks)                            |   ✅   |
| Lifecycle & customization | [Context parameters](#context-parameters)                           |   ⚠️   |
| Lifecycle & customization | [Qualifiers](#qualifiers)                                           |   ✅   |
| Lifecycle & customization | [Mapper composition](#mapper-composition)                           |   ✅   |
| Object creation & update  | [In-place update methods](#in-place-update-methods)                 |   ✅   |
| Object creation & update  | [Object factories](#object-factories)                               |   ⚠️   |
| Object creation & update  | [Builder support](#builder-support)                                 |   ⚠️   |
| Object creation & update  | [Records and constructor mapping](#records-and-constructor-mapping) |   ⚠️   |
| Policies & null handling  | [Unmapped-target policy](#unmapped-target-policy)                   |   ✅   |
| Policies & null handling  | [Null value strategies](#null-value-strategies)                     |   ⚠️   |
| Policies & null handling  | [Null check strategy](#null-check-strategy)                         |   ⚠️   |
| Policies & null handling  | [Conditional mapping](#conditional-mapping)                         |   ⚠️   |
| Advanced                  | [Config inheritance](#config-inheritance)                           |   ⚠️   |
| Advanced                  | [Decorators](#decorators)                                           |   ✅   |
| Advanced                  | [DI component models](#di-component-models)                         |   ✅   |
| Advanced                  | [Enum mapping](#enum-mapping)                                       |   ⚠️   |
| Advanced                  | [Subclass mapping](#subclass-mapping)                               |   ⚠️   |

**Tally: 13 ✅ · 16 ⚠️ · 0 ❌** — no feature area is unreachable; every ⚠️ row has a working idiom for its core use
case, with the residual gaps named in its notes.

## Core mapping

### Implicit same-name mapping

**Status: ✅ full** · **MapStruct:** Unannotated properties map by name automatically

**telescope:**

```java
// No rows = pure deep auto-recursion: same-name components identity-map,
// nested records/POJOs recurse, List/Set/Map/Optional lift automatically
Mapper<UserEntity, UserDto> mapper = Telescope.mapper(UserEntity.class, UserDto.class);

UserDto dto = mapper.forward(entity);

// MapStruct-style lenient one-way (unmatched target fields take JLS defaults):
ForwardMapper<UserEntity, UserDto> projector = Telescope.mapperForward(UserEntity.class, UserDto.class);
```

Matching is exact name + type (ADR-0002 explicitly rejects fuzzy heuristics — same posture as MapStruct). One semantic
difference: bidirectional Telescope.mapper() is strict (unmatched fields need drop()/rows) because it guards backward();
the MapStruct-equivalent lenient behavior is Telescope.mapperForward(), which silently ignores unmatched source fields
and JLS-defaults unmatched targets. Deep recursion is arguably stronger than MapStruct's: containers lift automatically
at any depth and cycles terminate.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/Telescope.java:497-563 (deep recursion + 'Same-name
1-liner' javadoc at 519-523), 573-576 (mapper), 660-666+682-717 (mapperForward lenient-by-default, 'matches MapStruct's
generated-mapper default'); core/src/test/java/io/github/eschizoid/telescope/DeepMappingTest.java:94-116 ('Pure
same-name deep copy — no overrides needed': Telescope.map(SameAddrEntity.class, SameAddrDto.class) with zero rows);
docs/adr/0002-no-fuzzy-auto-mapping.md</sub>

### Explicit rename

**Status: ✅ full** · **MapStruct:** @Mapping(source = "email", target = "contactEmail")

**telescope:**

```java
import static io.github.eschizoid.telescope.mapping.Mapping.to;

Mapper<Customer, CustomerDto> mapper = Telescope.mapper(
  Customer.class,
  CustomerDto.class,
  to(Customer::email, CustomerDto::contactEmail)
); // typed method refs, no strings
```

Stronger than MapStruct's string attributes on refactor safety: source/target are compile-checked Serializable method
references, so IDE rename refactors follow and typos fail at javac, not at annotation-processing. One rename row is
keyed by (sourceClass, targetClass) and applies at every depth where the pair recurses — less repetition than a @Mapping
per mapper method, at the cost of broader scope when two usages of the same pair need different renames.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java:97-100 (to(src, tgt) same-typed
rename) and 111-118 (to(src, tgt, fwd, bwd) typed transform for renames that also change type);
core/src/test/java/io/github/eschizoid/telescope/DeepMappingTest.java:122-128 (to(CompanyEntity::founded,
CompanyDto::since) rename in 5-level nesting); core/src/main/java/io/github/eschizoid/telescope/Telescope.java:509-517
(javadoc: a rename row applies wherever recursion lands on that type pair)</sub>

### Nested source path

**Status: ✅ full** · **MapStruct:** @Mapping(source = "customer.address.city", target = "city") — dotted path into
nested source

**telescope:**

```java
import static io.github.eschizoid.telescope.mapping.Mapping.to;

// Nested source -> flat target: source side is a real Telescope path, each hop typed
Mapper<Order, OrderDto> mapper = Telescope.mapper(
    Order.class, OrderDto.class,
    to(Telescope.of(Order.class).field(Order::customer).field(Customer::address).field(Address::city),
       OrderDto::city));

// Or with a @Focus-generated navigator (zero runtime reflection on the path):
to(OrderTelescope.of().customer().address().city(), OrderDto::city)
```

All three shapes covered: nested source -> flat target, flat source -> nested target (with automatic intermediate
allocation for record intermediates, Mapping.java:349-355), and nested -> nested. Paths are typed Telescope values
instead of dotted strings, so javac checks every hop. Rows using Telescope paths apply at the outer (source, target)
pair only — same scope as MapStruct's per-method @Mapping. Known edge: flat-source -> nested-target intermediate
allocation needs a via(...) workaround for bean intermediates lacking a no-arg ctor/builder.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java:361-379 (to(`Telescope<A,X>`,
`Accessor<B,X>`) — javadoc: 'Closes MapStruct's @Mapping(source = "a.b.c", target = "flat")'), 381-402 (both-nested
to(Telescope, Telescope)), 320-359 (nested-target mirror);
core/src/test/java/io/github/eschizoid/telescope/TelescopeMappingTest.java:181-193 (forward reads nested source path,
writes flat target), 200-214 (both-nested), 158-174 (codegen navigators on both sides)</sub>

### Multiple source parameters

**Status: ⚠️ partial** · **MapStruct:** CustomerDto toDto(Customer c, Address a) — one target built from several sources

**telescope:**

```java
import static io.github.eschizoid.telescope.mapping.MergeStep.auto;
import static io.github.eschizoid.telescope.mapping.MergeStep.from;

Mapper<Sources, Profile> mapper = Telescope.merge(
  Profile.class,
  auto(Customer.class), // backfill same-name fields from Customer
  from(Audit::createdBy, Profile::createdBy), // source slot inferred from declaring class
  from(Audit::createdAt, Profile::createdAt)
);

Profile p = mapper.forward(Sources.of(customer, audit)); // any arity, no per-arity overloads
```

The use case is covered by Telescope.merge + a Sources bag, including per-source auto() same-name backfill and arbitrary
arity. Genuine limitations vs MapStruct's typed method signature: (1) the call boundary is
mapper.forward(Sources.of(...)) — a missing/wrong source class is caught at forward time (IllegalStateException), not at
compile time like toDto(Customer, Address); (2) each source must have a distinct runtime class — two same-typed sources
(MapStruct disambiguates by parameter name) require pre-aggregating into a holder record; (3) forward-only:
backward()/patch() throw (MapStruct multi-source is also inherently one-way, so this is minor).

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/Telescope.java:803-859 (merge javadoc + factory, incl.
'Distinct runtime classes' at 832-835 and 'Backward is unsupported' at 836-840);
core/src/main/java/io/github/eschizoid/telescope/mapping/MergeStep.java:26-81 (from(src, tgt) class-inferred rows,
auto(Class) same-name backfill); core/src/test/java/io/github/eschizoid/telescope/MergeTest.java:52-60 (2-source
forward), 97-104 (arity 3), 121-130 (arity 5, single factory)</sub>

## Conversions

### Implicit type conversions

**Status: ⚠️ partial** · **MapStruct:** Automatic conversions between source/target types: primitive<->wrapper
autoboxing, `String<->`number, enum<->String, date/time<->String, widening numeric conversions — all silent, no
declaration needed.

**telescope:**

```java
// Auto (no row needed): primitive<->wrapper, collection copies, Optional<->nullable
final var mapper = Telescope.mapper(Source.class, Target.class); // boolean<->Boolean, Integer<->int just work, null-safe

// Everything else is an explicit typed row (by design — ADR-0002):
Telescope.mapper(Src.class, Dst.class,
    to(Src::getAttempts, Dst::getAttemptsText,
       i -> i == null ? null : String.valueOf(i),
       s -> s == null ? null : Integer.parseInt(s)),          // String<->number
    to(Src::status, Dst::statusName, Enum::name, s -> Status.valueOf(s)), // enum<->String
    enumTo(Src::status, Dst::status, SrcStatus.class, DstStatus.class));  // enum<->enum by name, exhaustiveness-checked
```

`Primitive<->`wrapper is genuinely automatic and matches MapStruct's null->0/false behavior (tested). But
`String<->`number, enum<->String, and date/time conversions are deliberately NOT implicit (ADR-0002 'no fuzzy
auto-mapping'): a same-name field with mismatched types throws at mapper-build time with a message naming the fix. The
workaround is one explicit to(src, tgt, fwd, bwd) row per pair — compile-typed, but a MapStruct migrator must write rows
MapStruct generated silently. enum<->enum by name gets first-class sugar (enumTo, with build-time exhaustiveness
MapStruct lacks); enum<->String does not.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/DeepMap.java:1016-1035 (Identity / PrimitiveWrapper
autobox with JLS-default null guard / CollectionCopy branches), DeepMap.java:1049-1069 (`Optional<->`nullable lift);
core/src/test/java/io/github/eschizoid/telescope/MigrationRegressionTest.java:472-500 (primitive<->wrapper auto-boxed,
null boxed -> JLS default), MigrationRegressionTest.java:2815-2845 (same-name Integer vs String pair fails fast pointing
at 'to(src, tgt, forward, backward)'; 4-arg transform is the documented fix);
core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java:175-188 (enumTo);
docs/adr/0002-no-fuzzy-auto-mapping.md (exact name+type only, explicit rows otherwise)</sub>

### Format strings

**Status: ⚠️ partial** · **MapStruct:** @Mapping(numberFormat = "$#.00") / @Mapping(dateFormat = "dd.MM.yyyy") —
annotation attribute, MapStruct generates DecimalFormat/SimpleDateFormat code for both directions.

**telescope:**

```java
private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd.MM.yyyy");

Telescope.mapper(Order.class, OrderDto.class,
    to(Order::shipDate, OrderDto::shipDateText, D::format, s -> LocalDate.parse(s, D)), // bidirectional
    toOneWay(Order::createdAt, OrderDto::createdAtIso, Instant::toString));             // forward-only

// Codegen (@Bridge) form — static helper via @Transform(method = ...):
@Bridge(value = UserDto.class, transforms = {
  @Transform(field = "expiresAt", using = DateHelpers.class, method = "expiry") })
public record UserEntity(String id, Instant expiresAt) {}
```

No format-string attribute exists anywhere — no numberFormat/dateFormat analog, and a repo-wide grep finds no
DecimalFormat/DateTimeFormatter machinery in main code. The use case is fully achievable with a typed transform row
holding your own formatter (compile-checked, refactor-safe, arguably safer than a stringly pattern), but the migrator
hand-writes the formatter and, for bidirectional number formats, the parse leg including checked-exception handling that
MapStruct generates. Codegen path needs a whole @Transform + helper class per formatted field — more ceremony than one
annotation attribute.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java:111-148 (4-arg to typed transform
with Instant::toString/Instant::parse example; toOneWay forward-only);
core/src/main/java/io/github/eschizoid/telescope/annotations/Transform.java:50-100 (per-field BridgeFn / static-method
qualifier dispatch, DateTimeFormatter example at lines 83-94); README.md:981-991 (parity table maps to(src,tgt,fwd,bwd)
to @Mapping qualifiedBy); core/src/test/java/io/github/eschizoid/telescope/MigrationRegressionTest.java:2826-2845
(number<->String transform round-trip)</sub>

### Expressions

**Status: ✅ full** · **MapStruct:** @Mapping(expression = "java(...)") for computed target values and defaultExpression
= "java(...)" for lazily-computed null fallbacks; string-templated Java inside an annotation.

**telescope:**

```java
Telescope.mapper(Order.class, OrderDto.class,
    to(Order::id, OrderDto::id),
    compute(OrderDto::createdAt, Instant::now),          // expression: fresh per forward call
    compute(OrderDto::traceId,   UUID::randomUUID),
    compute(OrderDtoTelescope.of().audit().createdAt(), Instant::now), // nested target
    toOrElseGet(Order::createdAt, OrderDto::createdAt, Instant::now))  // defaultExpression
  // source-referencing expression (MapStruct: expression = "java(s.first() + \" \" + s.last())"):
  .afterForward((src, dto) -> dto.withDisplayName(src.firstName() + " " + src.lastName()));
// or per-field: toOneWay(Order::total, OrderDto::totalText, t -> "$" + t)
```

A better idiom than MapStruct's stringly expression: plain typed Java, javac-checked, refactor-safe, no expression
parser. compute() takes a Supplier (no source arg) — source-dependent expressions route through toOneWay(src, tgt, fn)
for one source field or the typed afterForward((src, dto) -> ...) hook for multi-field derivation. compute/constant rows
are forward-only by design (backward drops the slot). Codegen @Compute requires a top-level Supplier class (no lambdas
in annotations) — slightly heavier than runtime form.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java:527-553 (compute(tgt, Supplier),
javadoc names the expression=java(Instant.now()) gap), Mapping.java:582-597 (nested-target compute),
Mapping.java:276-318 (toOrElseGet closes defaultExpression, plus predicate-gated overload), Mapping.java:142-148
(toOneWay per-field function); core/src/main/java/io/github/eschizoid/telescope/conversion/Mapper.java:497-522
(afterForward(`BiFunction<A,B,B>`) source-aware hook, typed @AfterMapping analog);
core/src/test/java/io/github/eschizoid/telescope/MappingConstantComputeTest.java:104-148 (fresh-per-call, forward-only
semantics pinned); core/src/main/java/io/github/eschizoid/telescope/annotations/Compute.java:37-51 (codegen
@Compute(using = Supplier.class) for @Bridge); README.md:991</sub>

### Constants and defaults

**Status: ✅ full** · **MapStruct:** @Mapping(target = "x", constant = "fixed") stamps a literal; @Mapping(target = "x",
defaultValue = "fallback") substitutes when source is null.

**telescope:**

```java
Telescope.mapper(Order.class, OrderDto.class,
    to(Order::id, OrderDto::id),
    constant(OrderDto::tenant,     "production"),       // @Mapping(constant = ...), typed literal
    constant(OrderDto::apiVersion, 7),
    constant(OrderDtoTelescope.of().shipping().country(), "US"),        // nested target
    toOrElse(Order::region, OrderDto::region, "EMEA"),                  // @Mapping(defaultValue = ...)
    toOrElse(Order::displayName, OrderDto::displayName, "(unnamed)", String::isBlank), // predicate-gated
    toOrElseGet(Order::traceId, OrderDto::traceId, UUID::randomUUID));  // lazy default

// Codegen (@Bridge) form:
@Bridge(value = UserEntity.class,
  constants = { @Constant(field = "source", value = "API") },
  defaults  = { @Default(field = "region", value = "EMEA") })
public record User(String id, String region) {}
```

Complete coverage in both runtime and codegen paths, statically typed (constant(OrderDto::apiVersion, 7) is an int, not
a string literal to parse). Exceeds MapStruct: predicate-gated toOrElse/toOrElseGet handle empty-string/empty-collection
'missing' semantics MapStruct's strictly-null defaultValue cannot, and nested-target constants (@Mapping(target="a.b.c",
constant=...)) work via Telescope paths. Known asymmetries, documented and tested: constant is forward-only (backward
drops the slot), and a substituted default round-trips backward as itself rather than the original null. Codegen
@Default is strict-null only (annotation attributes cannot hold predicates).

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java:500-525 (constant(tgt, value),
javadoc names the @Mapping constant gap), Mapping.java:555-580 (nested-target constant), Mapping.java:227-274 (toOrElse
strict-null + predicate-gated), Mapping.java:276-318 (toOrElseGet lazy + predicate-gated);
core/src/test/java/io/github/eschizoid/telescope/MappingConstantComputeTest.java:46-101 (constant stamps literal;
backward drops to type default), core/src/test/java/io/github/eschizoid/telescope/MappingOrElseTest.java:27-155
(null->default, pass-through, lazy supplier, empty-string/empty-collection predicates);
core/src/main/java/io/github/eschizoid/telescope/annotations/Constant.java:41-50 and
core/src/main/java/io/github/eschizoid/telescope/annotations/Default.java:48-61 (codegen literals, parsed at emit time
against field type); README.md:986-990</sub>

## Collections & containers

### Collection element mapping

**Status: ✅ full** · **MapStruct:** `List<CarDto>` map(`List<Car>` cars) — generated loop reuses the element mapping
method automatically

**telescope:**

```java
// auto: element pair resolved and lifted through the container, at any depth
final Mapper<TeamEntity, TeamDto> teamMapper = Telescope.mapper(
  TeamEntity.class,
  TeamDto.class,
  to(UserEntity::name, UserDto::fullName)
); // rename fires on every List element

// or reuse a pre-built element mapper explicitly:
final Mapper<TeamEntity, TeamDto> teamMapper2 = Telescope.mapper(
  TeamEntity.class,
  TeamDto.class,
  via(TeamEntity::members, TeamDto::members, userMapper)
); // Mapper<UserEntity, UserDto> auto-lifts through List
```

Element mapping is reused automatically (same-name recursion) or explicitly (via(...) with a pre-built element Mapper),
including nested containers like `List<Optional<X>>` and `Map<K,List<X>>`. Bidirectional round-trip comes free. One
honesty caveat: auto-lift requires SAME-kind containers on both sides (PairingRules.java:79 — srcView.kind() ==
tgtView.kind()); MapStruct's List -> Set cross-kind copy needs an explicit to(src, tgt, fwd, bwd) row in telescope.
Target concrete class (ArrayList/LinkedList/CopyOnWriteArrayList/...) is honored via listAllocatorFor
(DeepMap.java:1441-1461).

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/DeepMap.java:65-73 (engine javadoc:
List/Set/Map-values/Optional lift the element Iso, containers nest to any depth), DeepMap.java:1093-1124 (LiftContainer
dispatch recursing on element type), DeepMap.java:1213-1268 (liftViaIfNeeded: element-level Mapper auto-lifted through
the accessor's container); core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java:427-449 (via javadoc +
factory: 'List pair, auto-lifts'); core/src/test/java/io/github/eschizoid/telescope/DeepMappingTest.java:125-178
(5-level nesting, element rename fires inside List of List of List), 264-289 (`List<Optional<X>>` and `Map<K,List<X>>`
auto-lift), 342-372 (via(...) lifts `Mapper<UserEntity,UserDto>` through a List accessor pair, round-trips)</sub>

### Map mapping

**Status: ⚠️ partial** · **MapStruct:** `Map<String, StringDto>` map(`Map<String, Entity>` m);
@MapMapping(keyDateFormat/valueDateFormat/keyQualifiedBy/valueQualifiedBy)

**telescope:**

```java
// Map VALUES map automatically when the value types are a mappable pair; keys pass through verbatim
final Mapper<CompanyEntity, CompanyDto> m = Telescope.mapper(CompanyEntity.class, CompanyDto.class);
// Map<String, AddressEntity> -> Map<String, AddressDto>, keys preserved

// explicit value mapper for one Map field:
via(CompanyEntity::officesByRegion, CompanyDto::officesByRegion, addressMapper)

// value formatting (@MapMapping(valueDateFormat) analog) — whole-field typed transform:
to(AuditEntity::timestamps, AuditDto::timestamps,
    ts -> ts.entrySet().stream().collect(toMap(Map.Entry::getKey, e -> FMT.format(e.getValue()))),
    ts -> ts.entrySet().stream().collect(toMap(Map.Entry::getKey, e -> LocalDate.parse(e.getValue(), FMT))))
```

VALUE mapping is fully covered: auto-lifted through Iso.liftMapValues at any depth, or explicit via(...) with a
value-level Mapper. KEY mapping is deliberately not supported — key types must match exactly and keys are copied
verbatim; a key conversion (e.g. String keys -> enum keys) requires a manual whole-field to(src, tgt, fwd, bwd)
transform over the entire Map. No per-key/per-value format sugar like @MapMapping(valueDateFormat) — the equivalent is a
hand-written stream/collect transform row. Target Map concrete type is honored; EnumMap targets are rejected with a
precise error (needs an explicit row).

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/DeepMap.java:1116-1120 (MAP_VALUES lift), 1405-1434
(liftMapIntoTargetRaw: 'Preserves source keys verbatim'), 1238-1246 (via(...) throws when Map key types differ: 'Key
types must match exactly; auto-lifting preserves the source keys'), 1487-1508 (mapAllocatorFor:
HashMap/LinkedHashMap/TreeMap/ConcurrentHashMap/...; EnumMap rejected at plan time);
internal/src/main/java/io/github/eschizoid/telescope/internal/pairing/PairingRules.java:80-91 (mismatched key types ->
Incompatible), 119-122 (non-class key type arg means the Map is not treated as liftable);
core/src/test/java/io/github/eschizoid/telescope/DeepMappingTest.java:172-174 (Map values recursed, keys preserved),
280-289 (`Map<K, List<Record>>` auto-lifts);
core/src/test/java/io/github/eschizoid/telescope/DeepMapCoverageTest.java:77,152 (key-type mismatch rejection
asserted)</sub>

### Collection target strategies

**Status: ⚠️ partial** · **MapStruct:** CollectionMappingStrategy = ACCESSOR_ONLY / SETTER_PREFERRED / ADDER_PREFERRED /
TARGET_IMMUTABLE; @MappingTarget updates an existing target's collections

**telescope:**

```java
// fresh-allocation path: target collection's DECLARED concrete class is honored
// (ArrayList/LinkedList/Vector/CopyOnWriteArrayList/TreeSet/ConcurrentHashMap/... + user subclasses)
final Mapper<Order, OrderEntity> mapper = Telescope.mapper(
    Order.class, OrderEntity.class,
    writeBeans(SETTERS),                          // pin bean write strategy tree-wide
    writeBean(CashRegisterEntity.class, FIELDS)); // per-class override

// @MappingTarget analog — mutate an existing managed entity in place:
final OrderEntity managed = repository.findById(id).orElseThrow();
mapper.into(managed, dto);   // collection fields REPLACED via setItems(...), not merged
repository.save(managed);
```

What exists: (1) target collection concrete type preserved on fresh allocation, with plan-time errors instead of silent
ArrayList substitution; (2) Mapper.into(target, source) is the @MappingTarget analog — setter-based in-place update,
roughly ACCESSOR_ONLY/SETTER_PREFERRED semantics; (3) writeBean/writeBeans pins the bean construction strategy per
class. What's genuinely missing: ADDER_PREFERRED (no orderEntity.addLineItem(x) adder dispatch — the JPA bidirectional
back-reference wiring use case has no telescope equivalent), and true collection MERGE on an existing target (into()
replaces the collection reference via the setter; it never getItems().clear()/addAll() into the existing instance —
matters for Hibernate PersistentCollection orphan-removal). into() also requires public setters and rejects record
targets.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/conversion/Mapper.java:353-423 (into(): javadoc says
'Closes MapStruct's @MappingTarget for the bean path'; setter-based, records rejected, two-phase staged writes);
core/src/test/java/io/github/eschizoid/telescope/MapperIntoTest.java:127-160 (in-place mutation preserves target
identity, repeatable); core/src/main/java/io/github/eschizoid/telescope/DeepMap.java:1344-1374 + 1441-1461
(liftListIntoTargetRaw + listAllocatorFor: result runtime class matches declared target raw class, unknown java.base
subtypes throw at plan time), 1463-1508 (set/map allocators);
core/src/main/java/io/github/eschizoid/telescope/mapping/WriteHint.java:45-98 (writeBean/writeBeans with
BUILDER/SETTERS/FIELDS/CONSTRUCTOR — no ADDER); grep for 'adder' across core/ and codegen/ returned only WriteStrategy
ladder docs (no adder support anywhere)</sub>

### Stream support

**Status: ⚠️ partial** · **MapStruct:** `List<CarDto>` map(`Stream<Car>` cars) — generated terminal collect;
Stream-typed sources/targets in mapper signatures

**telescope:**

```java
// no Stream-shaped mapper factory — map at the call site with the element Mapper:
final Mapper<Car, CarDto> carMapper = Telescope.mapper(Car.class, CarDto.class);

final List<CarDto> dtos = cars.map(carMapper::forward).toList(); // Stream<Car> -> List<CarDto>
```

No Stream support in the mapping engine: Stream is not a recognized container kind, so a `Stream<X>`-typed record
component or bean property cannot be auto-mapped (the pair is rejected as incompatible shapes; workaround is a manual
to(src, tgt, fwd, bwd) row that collects/re-streams). The dominant real-world case — converting a Stream at a call site
— is a one-liner with mapper::forward that is arguably no worse than declaring a Stream method on a MapStruct interface,
which is why this is partial rather than missing. But there is zero sugar: no Stream-shaped factory, no Stream field
lift, and forward-only (no backward through a consumed Stream).

<sub>Evidence: internal/src/main/java/io/github/eschizoid/telescope/internal/pairing/ContainerView.java:14-19 (auto-lift
container kinds are exactly LIST, SET, MAP_VALUES, OPTIONAL — no STREAM);
internal/src/main/java/io/github/eschizoid/telescope/internal/pairing/PairingRules.java:106-125 (containerViewOf
recognizes only Optional/List/Set/Map — a Stream-typed component falls through to Incompatible); grep for 'Stream<'
across core/src/main, core/src/test, and examples/ found no Stream mapping API (only an unrelated record named Stream in
README.md:805)</sub>

## Lifecycle & customization

### Before/after hooks

**Status: ✅ full** · **MapStruct:** @BeforeMapping / @AfterMapping callback methods invoked around the generated
mapping

**telescope:**

```java
Mapper<Entity, Dto> mapper = Telescope.mapper(Entity.class, Dto.class, to(Entity::id, Dto::id))
  .beforeForward((e) -> e.normalised()) // pre-hook on source
  .afterForward((src, dto) -> dto.withDisplayName(src.firstName() + " " + src.lastName())) // source-aware post-hook
  .beforeBackward((dto) -> dto.trimmedIds())
  .afterBackward((dto, entity) -> entity.withUpdatedAtMillis(dto.updatedAtMillis()));
```

Four symmetric hooks per direction, each returning a new immutable Mapper; BiFunction overloads give the hook both
source and structural result (MapStruct's @AfterMapping-with-source pattern). Hooks are `Function<X,X>` not Consumer, so
they work for immutable records too — arguably richer than MapStruct, where mutable-target hooks are the norm. Hooks
survive .asTelescope() and chain left-to-right.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/conversion/Mapper.java:455 (beforeForward), :493
(afterForward Function), :509 (afterForward BiFunction — javadoc at :500 explicitly cites MapStruct), :539
(beforeBackward), :568 and :583 (afterBackward Function/BiFunction);
core/src/test/java/io/github/eschizoid/telescope/MapperPostHooksTest.java:26-269 (all four hooks, chaining order,
direction-laziness, asTelescope() propagation);
core/src/test/java/io/github/eschizoid/telescope/MigrationRegressionTest.java:1239, 1268, 1885; README.md:372
(parity-table row)</sub>

### Context parameters

**Status: ⚠️ partial** · **MapStruct:** @Context param threaded through nested mappings without being mapped itself
(e.g. Locale, cycle-tracking)

**telescope:**

```java
// No @Context analog — capture the context in row functions at mapper-build time:
static Mapper<Order, OrderDto> orderMapper(final Locale locale) {
  return Telescope.mapper(
    Order.class,
    OrderDto.class,
    to(
      Order::total,
      OrderDto::totalText,
      (amount) -> NumberFormat.getCurrencyInstance(locale).format(amount),
      (text) -> parseAmount(text, locale)
    )
  );
}

// The cycle-tracking @Context use case needs zero code — cycles are handled automatically:
final var mapper = Telescope.mapper(Node.class, NodeDto.class); // self/mutual recursion just works
```

The single most common @Context use case — CycleAvoidingMappingContext — is unnecessary: DeepMap's cycle cache +
value-level seen-set handles self/mutual recursion automatically. What's genuinely missing: a per-invocation context
parameter threaded through nested via(...) mappers. forward(a) takes only the source; row functions and hooks capture
context at build time, so per-request context (Locale, tenant) means building a mapper per context value (build cost is
non-trivial) or closing over a ScopedValue/holder manually. No sugar exists for that.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/conversion/Mapper.java:330 (forward(A a) — no context
overload), :426 (backward(B b)); core/src/main/java/io/github/eschizoid/telescope/DeepMap.java:1689-1719 (built-in
ThreadLocal IdentityHashMap value-level cycle guard);
core/src/test/java/io/github/eschizoid/telescope/CycleHandlingTest.java:31-86 (Optional/List self-reference and A-B
mutual recursion map without StackOverflow, zero user code);
core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java:111 (to(src, tgt, fwd, bwd) typed-transform row
where a closure captures context)</sub>

### Qualifiers

**Status: ✅ full** · **MapStruct:** @Named("formatter") / custom @Qualifier to pick between multiple candidate mapping
methods for the same type pair

**telescope:**

```java
// Runtime: mappers and converters are VALUES — name the exact one per row, no string qualifier:
Telescope.mapper(Customer.class, CustomerDto.class,
    via(Customer::homeAddress, CustomerDto::homeAddress, homeAddressMapper),
    via(Customer::workAddress, CustomerDto::workAddress, workAddressMapper));
// Codegen: @Named analog — several conversion methods on one helper class, picked per field:
@Bridge(value = UserDto.class, transforms = {
  @Transform(field = "expiresAt", using = DateHelpers.class, method = "expiry"),
  @Transform(field = "createdAt", using = DateHelpers.class, method = "createdAt")})
public record UserEntity(String id, Instant expiresAt, Instant createdAt) {}
```

The ambiguity qualifiers solve doesn't arise at runtime: each row names its converter/mapper by reference, resolved by
javac — safer than @Named string matching. Codegen side has the literal @Named-equivalent via @Transform(using, method)
(forward-only, same asymmetry as a single MapStruct qualified method). One corner: the DI TelescopeMapperRegistry allows
one mapper per (src,tgt) pair — two semantically-different mappers for the same pair must be injected directly with the
framework's own @Qualifier, bypassing the registry.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/annotations/Transform.java:59-100 (method() attribute —
javadoc explicitly says it "unlocks MapStruct's @Named qualifier-dispatch pattern", emits direct
UsingClass.methodName(value) static call); core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java:111
(to(src, tgt, fwd, bwd) — exact function per row) and :443 (via with an exact Mapper instance);
spring-boot-starter/src/main/java/io/github/eschizoid/telescope/spring/TelescopeMapperRegistry.java:44-66 (duplicate
(src,tgt) pairs throw with a message directing to Spring @Qualifier + direct injection)</sub>

### Mapper composition

**Status: ✅ full** · **MapStruct:** @Mapper(uses = OtherMapper.class) — delegating nested type conversions to other
mappers or static helper methods

**telescope:**

```java
// Runtime: delegate a nested field to a pre-built Mapper; container shapes auto-lift:
Mapper<Address, AddressDto> addressMapper = Telescope.mapper(Address.class, AddressDto.class);

Mapper<UserEntity, UserDto> userMapper = Telescope.mapper(
  UserEntity.class,
  UserDto.class,
  via(UserEntity::address, UserDto::address, addressMapper), // scalar pair
  via(UserEntity::teams, UserDto::teams, teamMapper)
); // List pair, auto-lifts

// (nested record pairs also auto-recurse with NO via row — Telescope.mapper(A.class, B.class) builds sub-mappers itself)
// Codegen: point a field at an existing bridge or static helper:
@Bridge(value = OrderEntity.class, viaMappers = { @ViaMapper(field = "address", using = AddressBridge.class) })
public record Order(String id, Address address) {}
```

Two mechanisms and both directions of MapStruct's uses= are covered: via(src, tgt, mapper) delegates one field to a
pre-built Mapper (with automatic List/Set/Optional/Map element-lifting — MapStruct requires an Iterable method for
that), and auto-recursion means the common uses= case (nested type pairs) needs no declaration at all. Codegen mirrors
it with @ViaMapper (delegate to a bridge class) and @Transform(using = Helper.class, method = ...) for static helper
methods. Difference in idiom: telescope binds the delegate per field explicitly, MapStruct auto-selects by type from the
uses list — telescope's form is more verbose when many fields share one nested pair but unambiguous.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java:427-449 (via(srcAcc, tgtAcc,
mapper) factory, element-level auto-lift through List/Set/Optional/Map documented at :432-441);
core/src/main/java/io/github/eschizoid/telescope/mapping/Via.java:19 (row record);
core/src/main/java/io/github/eschizoid/telescope/annotations/ViaMapper.java:16-63 (codegen per-field bridge delegation,
static forward/backward shape); core/src/main/java/io/github/eschizoid/telescope/annotations/Bridge.java:207-222
(viaMappers attribute); core/src/test/java/io/github/eschizoid/telescope/DeepMappingTest.java:356, 377-391 (via
precedence over auto-recursion); core/src/test/java/io/github/eschizoid/telescope/CycleHandlingTest.java:31-51
(auto-recursion builds nested sub-mappers with no delegation rows at all)</sub>

## Object creation & update

### In-place update methods

**Status: ✅ full** · **MapStruct:** void update(@MappingTarget Entity target, Dto source) — mutate an existing bean
instead of constructing a fresh one

**telescope:**

```java
final Mapper<OrderDto, OrderEntity> mapper = Telescope.mapper(OrderDto.class, OrderEntity.class, to(OrderDto::sku, OrderEntity::getSku));
final OrderEntity managed = repository.findById(id).orElseThrow();
mapper.into(managed, dto);        // writes dto's mapped fields onto `managed` via setters, same reference back
repository.save(managed);
// sparse sibling: only non-null fields of a partial target overlay the base
final OrderEntity updated = mapper.patch(entity, dtoPatch);
```

Deliberate parity feature — the javadoc names @MappingTarget explicitly and the test class pins JPA load-mutate-save
semantics (identity preserved, only mapped fields written, atomic staging). Bean targets only: records throw
UnsupportedOperationException (MapStruct can't mutate records in place either). Properties without a public setter are
silently skipped — matching MapStruct's @MappingTarget semantics. Bonus over MapStruct: patch() gives null-skipping
sparse update without NullValuePropertyMappingStrategy.IGNORE config.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/conversion/Mapper.java:353-423 (into(): javadoc says
'Closes MapStruct's @MappingTarget for the bean path', two-phase staged writes, returns same reference, records rejected
with UnsupportedOperationException at :394-400); Mapper.java:290-323 (patch() sparse overlay);
core/src/test/java/io/github/eschizoid/telescope/MapperIntoTest.java:16 ('Pins Mapper.into(target, source) — the
@MappingTarget equivalent'), :136-138 (assertSame identity preservation), :150-153 (repeatable mutation)</sub>

### Object factories

**Status: ⚠️ partial** · **MapStruct:** @ObjectFactory — a user method that instantiates the target (custom constructor
args, EntityManager lookup, DI-provided instance) which the generated mapper then populates

**telescope:**

```java
// 1. Pin HOW the engine constructs a target class (strategy, not arbitrary code):
Telescope.mapper(OrderRecord.class, OrderPojo.class,
    writeBean(OrderPojo.class, WriteStrategy.CONSTRUCTOR),  // or BUILDER / SETTERS / FIELDS
    to(OrderRecord::sku, OrderPojo::getSku));
// 2. Fabricate/load the instance yourself, then let the mapper populate it in place:
mapper.into(entityManager.find(OrderEntity.class, dto.id()), dto);
// 3. Or take over the whole conversion with hand-written functions:
Telescope.from(OrderDto.class).to(OrderEntity.class)
    .using(d -> OrderEntityFactory.create(d), e -> new OrderDto(e.getSku()));
```

No hook to inject an arbitrary user function as the instantiator inside the deep-mapping engine — writeBean() selects
among four reflection strategies (BUILDER/SETTERS/FIELDS/CONSTRUCTOR), it cannot call your code. The two @ObjectFactory
motivations are each covered by a different idiom: 'construct differently' → writeBean strategy hint; 'populate an
instance I obtained elsewhere (JPA/DI)' → mapper.into(existing, source). A truly custom factory (e.g. constructor
needing values not on the source) forces you out to from/to/using where you hand-write the entire forward function,
losing auto-mapping for the remaining fields.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/mapping/WriteHint.java:45-98 (writeBean/writeBeans
accept only the 4-value WriteStrategy enum at :58-63 — no Supplier/factory function overload);
core/src/main/java/io/github/eschizoid/telescope/conversion/MapperBuilder.java:91-160 (create/inherit/add/build — no
factory hook); core/src/main/java/io/github/eschizoid/telescope/Telescope.java:463-495 (from/to/using escape hatch);
core/src/main/java/io/github/eschizoid/telescope/conversion/Mapper.java:391-423 (into() covers the
load-existing-instance use case); no ObjectFactory counterpart exists anywhere in the main source sets</sub>

### Builder support

**Status: ⚠️ partial** · **MapStruct:** Auto-detected builder(): Immutables, Lombok @Builder, protobuf builders;
customizable via BuilderProvider SPI and @Builder(builderMethod=...)

**telescope:**

```java
// Auto-detected: any target with a static builder() whose builder has build() —
// probed automatically, Lombok @Builder just works:
final Mapper<UserDto, UserPojo> m = Telescope.mapper(UserDto.class, UserPojo.class,
    to(UserDto::email, UserPojo::getEmail));
// Pin it explicitly when the class also has setters (SETTERS wins by default):
Telescope.mapper(UserDto.class, UserPojo.class,
    writeBean(UserPojo.class, WriteStrategy.BUILDER));
// Codegen navigators for Lombok @Builder classes ship in telescope-lombok:
// @Builder class BuilderUser → generated BuilderUserTelescope with builder() rebuild
```

Covers Lombok @Builder (dedicated telescope-lombok module with integration tests) and any conventional static
builder()/build() pair, including Immutables if you map to the generated ImmutableFoo class directly (its static
builder() matches). Gaps vs MapStruct: (1) the factory method name is hard-wired to 'builder' — protobuf's newBuilder()
is NOT detected and there is no @Builder(builderMethod=...) equivalent or BuilderProvider SPI to teach it; (2) no
per-mapper toggle to prefer builder over setters other than the writeBean hint. Builder setter matching (exact / setX /
withX) covers Lombok, Immutables-fluent, and JavaBean-style builders.

<sub>Evidence: internal/src/main/java/io/github/eschizoid/telescope/internal/Beans.java:742-768 (autoWriter probe order:
setters → static builder() at :767 → fields → all-args ctor), :720-726 (builderWriter factory), :1229-1232 + 1259-1329
(BuilderWriter: requires static method named exactly 'builder()' returning a type with 'build()' at :1272-1289; setter
matching by exact name / setX / withX; LMF-de-reflected dispatch);
core/src/main/java/io/github/eschizoid/telescope/mapping/WriteHint.java:50,59 (BUILDER strategy, 'requires a static
builder() method'); lombok/src/test/java/io/github/eschizoid/telescope/codegen/lombok/fixtures/BuilderUser.java:10-12
(@Builder fixture) and LombokFocusProcessorTest.java:53-68 (@Builder and @Value+@Builder navigators verified
end-to-end); core/src/test/java/io/github/eschizoid/telescope/DeepMappingTest.java:576-712 (writeBean
BUILDER/FIELDS/CONSTRUCTOR hint tests)</sub>

### Records and constructor mapping

**Status: ⚠️ partial** · **MapStruct:** Constructor-based target population for records and immutable classes
(parameter-name matching, @Default constructor selection)

**telescope:**

```java
record UserEntity(String name, String email, AddressEntity address) {}
record UserDto(String fullName, String email, AddressDto address) {}
// records are rebuilt via the canonical constructor, nested records recursed automatically:
final Mapper<UserEntity, UserDto> m = Telescope.mapper(
    UserEntity.class, UserDto.class,
    to(UserEntity::name, UserDto::fullName));   // same-name fields auto-mapped
// immutable all-args-only POJO target (compile with -parameters for name matching):
Telescope.mapper(OrderRecord.class, ImmutablePojo.class,
    writeBean(ImmutablePojo.class, WriteStrategy.CONSTRUCTOR));
```

Records are the native happy path — canonical-constructor rebuild is the default everywhere (deep mapping, patch, @Focus
codegen), no configuration needed. Immutable non-record classes work via the CONSTRUCTOR write strategy: auto-detected
when there is exactly one public all-args constructor compiled with -parameters and parameter names align with
getter-derived properties; otherwise pin with writeBean(X.class, CONSTRUCTOR). Same -parameters dependency MapStruct has
for name-based constructor matching. No @Default-style disambiguation among multiple constructors — ambiguous
constructor sets are refused (Beans.java:801-810) rather than selectable, but the writeBean hint plus the single-ctor
rule covers the practical cases. Verified limitation: no counterpart to MapStruct's @Default — a target with multiple
same-arity constructors cannot be disambiguated (records: full; single-ctor immutables: full with -parameters;
multi-constructor immutables: unsupported).

<sub>Evidence: internal/src/main/java/io/github/eschizoid/telescope/internal/Records.java:273-307 (cached
canonical-constructor invoker per record class, components in canonical order, MethodHandle asSpreader);
core/src/main/java/io/github/eschizoid/telescope/conversion/Mapper.java:301 ('Records: rebuilds via the canonical
constructor'); internal/src/main/java/io/github/eschizoid/telescope/internal/Beans.java:710-718 (ConstructorWriter:
name-matched with -parameters, positional fallback) and :769-790 (auto-detected as autoWriter's 4th rung with
-parameters + name-alignment safety check, loud error otherwise);
core/src/main/java/io/github/eschizoid/telescope/mapping/WriteHint.java:54-55 (CONSTRUCTOR strategy docs);
core/src/test/java/io/github/eschizoid/telescope/DeepMappingTest.java:42-59 (record↔record fixtures), :209 (nested
record mapper), :671-688 ('CONSTRUCTOR hint constructs an immutable all-args-only POJO' + auto-detection test)</sub>

## Policies & null handling

### Unmapped-target policy

**Status: ✅ full** · **MapStruct:** unmappedTargetPolicy = ERROR/WARN/IGNORE at @Mapper or @MapperConfig level

**telescope:**

```java
// ERROR analog — Telescope.mapper is strict by default: unmatched fields on EITHER side throw at construction
final Mapper<Order, PartnerShippingLabel> m = Telescope.mapper(
  Order.class,
  PartnerShippingLabel.class,
  to(Order::orderNumber, PartnerShippingLabel::getTrackingReference),
  drop(Order::metadata)
); // declare a field intentionally NOT mapped (@Mapping ignore analog)

// IGNORE analog — lenient forward-only factory: unmatched targets get JLS defaults, unmatched sources are skipped
final ForwardMapper<Order, OrderDto> f = Telescope.mapperForward(Order.class, OrderDto.class);

// Compile-time policy knob (javac): -Atelescope.verify=error|warn|off   (default: error)
// Per-site exemption: @UncheckedMapping("reason") on the enclosing field/method/class
```

Telescope checks completeness twice: at mapper-construction time (always, fail-fast IllegalStateException) and at
compile time via MapperVerifierProcessor with a literal error|warn|off policy — the same three levels as MapStruct's
ERROR/WARN/IGNORE. Differences in idiom, not power: strict-vs-lenient is chosen per call site by factory (mapper =
strict bidirectional, mapperForward = lenient) rather than per-mapper attribute, and the compile-time severity is a
global javac -A flag plus per-site @UncheckedMapping, not per-mapper. drop(...) is the analog of @Mapping(target=...,
ignore=true).

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/DeepMap.java:124-132 (strict bijection — 'unmatched
fields on EITHER side throw at construction'); DeepMap.java:134-143 (mapperForward lenient, 'Matches MapStruct's default
behaviour'); core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java:451-498 (drop(src) and drop(src,
Target.class) — 'declare it intentionally NOT mapped');
codegen/src/main/java/io/github/eschizoid/telescope/codegen/MapperVerifierProcessor.java:61-63
('-Atelescope.verify=error|warn|off (default error)', '@UncheckedMapping("reason")'), 108-118 (mode ->
Diagnostic.Kind.ERROR/WARNING/off), 325-328 (unmatchedTargets/unmatchedSources reported);
docs/adr/0012-compile-time-mapper-verification.md:1-40</sub>

### Null value strategies

**Status: ⚠️ partial** · **MapStruct:** nullValueMappingStrategy / nullValuePropertyMappingStrategy (RETURN_NULL vs
RETURN_DEFAULT, SET_TO_NULL vs IGNORE on update)

**telescope:**

```java
import static io.github.eschizoid.telescope.mapping.Mapping.toOrElse;
import static io.github.eschizoid.telescope.mapping.NullHint.NullStrategy.DEFAULT;
import static io.github.eschizoid.telescope.mapping.NullHint.nullSourceValues;

// SET_TO_DEFAULT analog — per-mapper hint: null source fields become "" / 0 / List.of() / Optional.empty()
final Mapper<UserEntity, UserDto> m = Telescope.mapper(
  UserEntity.class,
  UserDto.class,
  nullSourceValues(DEFAULT),
  toOrElse(UserEntity::displayName, UserDto::displayName, "(unnamed)")
); // per-field default (wins over hint)

// SET_TO_NULL analog is the default (PROPAGATE) — no hint needed
// RETURN_NULL analog: mapper.forward(null) returns null
// IGNORE-on-update analog: patch overlays only non-null fields of the partial target
final UserEntity updated = m.patch(entity, dtoPatch);
```

Covered and tested: SET_TO_NULL (PROPAGATE, the default), SET_TO_DEFAULT (nullSourceValues(DEFAULT) with a
MapStruct-matching default table), RETURN_NULL (forward(null) -> null), IGNORE-on-update (patch skips null fields —
telescope's only patch mode), plus per-field defaultValue/defaultExpression analogs (toOrElse/toOrElseGet). Genuine
gaps: (1) no RETURN_DEFAULT for a whole-null source argument — forward(null) is always null; workaround is a caller-side
`src == null ? emptyDto() : m.forward(src)`; (2) no SET_TO_NULL toggle on update — patch always ignores nulls, so
'explicit null clears the field' PATCH semantics is not expressible; (3) DEFAULT substitutes only table leaf types —
record/bean/enum/custom-typed fields stay null unless a per-row toOrElse supplies a value; (4) DEFAULT is forward-only
(documented; backward preserves nulls).

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/mapping/NullHint.java:60-88 (nullSourceValues,
PROPAGATE='SET_TO_NULL', DEFAULT='SET_TO_DEFAULT', javadoc lines 8-10 names the MapStruct attributes);
internal/src/main/java/io/github/eschizoid/telescope/internal/NullDefaults.java:12-53 (per-type default table,
explicitly 'covers the surface MapStruct's RETURN_DEFAULT populates');
core/src/main/java/io/github/eschizoid/telescope/DeepMap.java:990-995 + 1138-1144 (DEFAULT wraps every per-component Iso
via coalesceForward), 1573-1586 (assembleIso: forward(null) returns null = RETURN_NULL);
core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java:243-318 (toOrElse/toOrElseGet per-field defaults);
core/src/main/java/io/github/eschizoid/telescope/conversion/Mapper.java:290-323 (patch: 'overlay the non-null fields of
partial ... leaving the rest of base untouched');
core/src/test/java/io/github/eschizoid/telescope/NullStrategyTest.java:25-100 and tail (PROPAGATE default, DEFAULT
substitution for String/wrappers/BigDecimal/collections, bean targets, backward-unchanged)</sub>

### Null check strategy

**Status: ⚠️ partial** · **MapStruct:** nullValueCheckStrategy = ALWAYS — guard every property read before
conversion/setter

**telescope:**

```java
// Engine-owned paths are ALWAYS null-guarded — no flag exists or is needed:
// auto-mapped nested pairs, containers, and telescope rows all pass null through safely.
// For custom conversion functions, guard per row:
Telescope.mapper(Src.class, Dst.class,
    toOrElse(Src::code, Dst::code, "N/A", String::isBlank),      // null-short-circuits BEFORE the predicate
    to(Src::amountCents, Dst::amount,
       a -> a == null ? null : BigDecimal.valueOf(a, 2),          // manual guard inside custom fwd fn
       b -> b == null ? null : b.movePointRight(2).longValue()),
    nullSourceValues(DEFAULT));  // additionally skips wrapped fns on null for table-defaultable leaf types
```

Telescope's default posture is effectively ALWAYS for everything the engine owns: null sources, null nested objects, and
null containers propagate as null instead of NPE-ing, with no configuration. The gap is custom transform rows — the
user-supplied fwd/bwd functions in to(src, tgt, fwd, bwd) / toOneWay / via ARE invoked with null under the default
PROPAGATE strategy, and under DEFAULT the coalesce wrap is skipped for target types outside the NullDefaults table
(records/beans/enums/custom). There is no per-mapper 'never call my conversion with null' switch; the recipe is toOrElse
for the common cases or a null check inside the function.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/DeepMap.java:1573-1586 (assembleIso:
`if (s == null) return null` on both directions — every auto record/bean pair guarded), 1306-1318 + 1360-1372
(collection/map/list-lift isos: `if (src == null) return null`), 868-872 (telescope-to-telescope rows write
`srcT.find(s).orElse(null)` leniently on null intermediates);
internal/src/main/java/io/github/eschizoid/telescope/internal/optics/Iso.java:140-142 (coalesceForward:
`x == null ? defaultValue : inner.to(x)` — inner fn skipped on null), 149-163 (liftList null pass-through);
core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java:269-273 + 314-317 (toOrElse/toOrElseGet
null-short-circuit before user predicates fire)</sub>

### Conditional mapping

**Status: ⚠️ partial** · **MapStruct:** @Condition presence-check methods (e.g. only map non-blank strings)

**telescope:**

```java
import static io.github.eschizoid.telescope.mapping.Mapping.*;

Telescope.mapper(Order.class, OrderDto.class,
    // per-field value predicate — 'only map non-blank strings', else default:
    toOrElse(Order::promoCode, OrderDto::promoCode, "NONE", String::isBlank),
    toOrElseGet(Order::traceId, OrderDto::traceId, () -> UUID.randomUUID().toString(), String::isBlank),
    // whole-source predicate gating on deep writes / stamping rows:
    when(order -> order.shipping() != null,
        to(Telescope.of(Order.class).field(Order::shipping).field(Shipping::country),
           OrderDto::shipCountry)),
    when(order -> order.priority() == Priority.HIGH,
        constant(OrderDto::expediteFlag, true)));
```

Both @Condition shapes have typed, tested analogs: per-field value predicates (toOrElse/toOrElseGet 4-arg, which
additionally null-short-circuit before the predicate) and whole-source predicates (when(...)), both forward-only like
@Condition. What's missing is MapStruct's blanket application: one @Condition method automatically gates EVERY property
of the matching type across the mapper, while telescope requires one row per gated field — real repetition on wide DTOs.
Also: when(...) wraps only telescope-based rows (plain to(srcAcc, tgtAcc)/via/drop rejected at construction with a
pointer to toOrElse), when(...) pins its predicate to the top-level source pair (not nested pairs), and there is no
conditional analog on the patch/update path.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java:647-649 (when(predicate, inner)
factory; javadoc 599-646: 'closes MapStruct's @Condition for whole-source predicate gating', forward-only, nesting
rejected), 263-273 (toOrElse with `missing` predicate — 'Generalises ... to cover empty-string, empty-collection ...
MapStruct has no equivalent'), 308-318 (toOrElseGet with predicate);
core/src/main/java/io/github/eschizoid/telescope/mapping/Conditional.java:88-128 (predicate-gated row; construction-time
rejection of field-iso/nested inners with alternative hints), 44-57 (predicate evaluated once per top-level forward
call, thread-safety contract); core/src/test/java/io/github/eschizoid/telescope/MappingWhenTest.java:32-186 (gated
flat-to-nested, constant, compute, nested-to-flat, nested-to-nested, zip);
core/src/test/java/io/github/eschizoid/telescope/MappingOrElseTest.java (7 @Test methods)</sub>

## Advanced

### Config inheritance

**Status: ⚠️ partial** · **MapStruct:** @InheritConfiguration reuses a base @Mapping set on a sibling method;
@InheritInverseConfiguration derives the reverse method from the forward one.

**telescope:**

```java
private static final MapStep[] AUDIT_COLUMNS = {
  to(Entity::createdAt, Dto::createdAt),
  to(Entity::updatedAt, Dto::updatedAt),
};

final Mapper<Entity, Dto> mapper = Telescope.mapperBuilder(Entity.class, Dto.class)
  .inherit(AUDIT_COLUMNS) // shared row group, reused across mappers
  .add(to(Entity::email, Dto::emailAddress)) // mapper-specific rows
  .build();

// @InheritInverseConfiguration is free: the SAME rows drive both directions —
Entity back = mapper.backward(mapper.forward(entity));
```

Row groups reuse cleanly across mappers of the SAME (source, target) pair, and the inverse half is free (backward()
derives from the same rows). Verified limitation: a group inherited into a DIFFERENT type pair is silently dropped (rows
are bucketed by their decoded type pair; never-visited buckets are ignored) — cross-DTO-variant reuse per
MapperBuilder's javadoc example does not currently apply the shared rows. Tracked as a bug (#223).

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/conversion/MapperBuilder.java:10-14 (javadoc: "Closes
MapStruct's @InheritConfiguration"), :106-109 (inherit), :150-152 (build delegates to Telescope.mapper);
core/src/main/java/io/github/eschizoid/telescope/Telescope.java:602 (mapperBuilder factory);
core/src/test/java/io/github/eschizoid/telescope/MapperBuilderTest.java:70-87 (same AUDIT_COLUMNS group feeds two
different mappers); core/src/main/java/io/github/eschizoid/telescope/conversion/Mapper.java:330 (forward), :426-433
(backward from the same Iso); README.md:442 ("Same Mapping.to(...) row works both ways")</sub>

### Decorators

**Status: ✅ full** · **MapStruct:** @DecoratedWith(CustomDecorator.class) — an abstract decorator class wraps the
generated mapper, overriding selected methods and delegating to the injected original.

**telescope:**

```java
final Mapper<Entity, Dto> mapper = Telescope.mapper(Entity.class, Dto.class)
  .beforeForward((e) -> e.normalised()) // @BeforeMapping equivalent
  .afterForward(
    (src, dto) -> dto.withDisplayName(src.firstName() + " " + src.lastName()) // @AfterMapping(@MappingTarget) — source-aware
  )
  .afterBackward((e) -> e.withLastModifiedAt(Instant.now()));

// Full-method override is plain composition — the Mapper is a value, not a generated class:
Function<Entity, Dto> decorated = (e) -> isSpecial(e) ? customDto(e) : mapper.forward(e);
```

Hooks compose left-to-right and return new immutable Mapper instances, so decoration happens at the definition site
(typically the @Bean/@Produces method) — no abstract class, no delegate injection, no annotation. Because Mapper is a
first-class value rather than a generated interface impl, whole-method conditional override is ordinary function
composition; the decorated mapper is simply what you register as the bean. The idiom is no weaker than @DecoratedWith —
it removes the constructor-injection ceremony MapStruct decorators need.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/conversion/Mapper.java:455 (beforeForward, javadoc
:436-439 cites @BeforeMapping), :493 (afterForward, javadoc :472-476 cites @AfterMapping), :509 (source-aware BiFunction
afterForward, javadoc :497-502 cites @AfterMapping with @MappingTarget), :539 (beforeBackward), :568 (afterBackward);
:246-252 (asTelescope carries the hook chain), :286-288 (toForwardMapper carries hooks); :151-159 (public
Mapper.create(forward, backward, ...) for wholesale wrapping)</sub>

### DI component models

**Status: ✅ full** · **MapStruct:** componentModel = "spring" / "cdi" / "jakarta" annotates the generated mapper impl
so it is discovered as an injectable bean.

**telescope:**

```java
// Spring Boot (telescope-spring-boot-starter on the classpath):
@Configuration
class Mappers {
  @Bean
  Mapper<UserEntity, UserDto> userMapper() {
    return Telescope.mapper(UserEntity.class, UserDto.class);
  }
}
// inject Mapper<UserEntity, UserDto> directly, or look up dynamically:
Mapper<?, ?> m = telescopeMapperRegistry.get(UserEntity.class, UserDto.class);

// Quarkus/CDI (telescope-quarkus): same shape with @Produces —
@Produces Mapper<UserEntity, UserDto> userMapper() { return Telescope.mapper(...); }
```

Telescope mappers are values built in code, so the @Bean/@Produces method that MapStruct generates is instead the
one-liner where you build the mapper anyway — zero extra ceremony versus componentModel. Both starters add a
(sourceClass, targetClass)-indexed TelescopeMapperRegistry MapStruct has no analog for (duplicate pairs fail fast,
configurable via telescope.registry.fail-fast). Caveat: there is no dedicated plain-Jakarta-EE/jsr330 module — the
Quarkus module's auto-collection uses ArC's @All — but a Mapper is an ordinary object producible from a standard CDI
@Produces method in any container.

<sub>Evidence:
spring-boot-starter/src/main/java/io/github/eschizoid/telescope/spring/TelescopeAutoConfiguration.java:34-60
(@AutoConfiguration builds TelescopeMapperRegistry from every Mapper bean; javadoc :26-28 "declare @Bean `Mapper<A, B>`
and it shows up in the registry");
quarkus/src/main/java/io/github/eschizoid/telescope/quarkus/TelescopeProducer.java:23-42 (@ApplicationScoped producer,
ArC @All `List<Mapper<?, ?>>` collector); README.md:373 (starter row in the comparison table)</sub>

### Enum mapping

**Status: ⚠️ partial** · **MapStruct:** @ValueMapping(source = "X", target = "Y") per-constant renames with
ANY_REMAINING/ANY_UNMAPPED defaults; @EnumMapping name-transformation strategies (prefix/suffix/case).

**telescope:**

```java
// Same-name constants — exhaustiveness validated when the mapper is built (both directions):
enumTo(UserEntity::status, UserDto::status, EntityStatus.class, DtoStatus.class)

// Renamed constants / ANY_REMAINING — a typed switch instead of @ValueMapping strings:
to(UserEntity::status, UserDto::status,
   s -> switch (s) { case ARCHIVED -> DtoStatus.CLOSED; default -> DtoStatus.valueOf(s.name()); },
   d -> switch (d) { case CLOSED -> EntityStatus.ARCHIVED; default -> EntityStatus.valueOf(d.name()); })
```

The by-name case is fully covered and arguably stronger than MapStruct — mismatched constant sets fail at mapper-build
time with a named diff, instead of at the first unlucky call. What's missing is the declarative sugar for asymmetric
enums: per-constant renames, @EnumMapping prefix/suffix/case name transformations, and ANY_REMAINING/ANY_UNMAPPED
defaults all require a hand-written (though javac-exhaustive and refactor-safe) switch or lambda inside a to(src, tgt,
fwd, bwd) row; enumTo itself deliberately rejects non-bijective enum pairs. Workaround quality is high, but it is manual
code, not a factory.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java:150-155 (enumTo javadoc: "Closes
MapStruct's @ValueMapping gap for the common 'status enums that line up by name' case"), :175-188 (enumTo impl over
Enum.valueOf both ways), :190-225 (factory-time exhaustiveness diff naming missing constants and pointing to to(src,
tgt, fwd, bwd)); core/src/test/java/io/github/eschizoid/telescope/MappingEnumToTest.java:62 (happy-path round-trip),
:91-113 (mismatch diagnostics + escape-hatch message); README.md:369, :988 (comparison rows)</sub>

### Subclass mapping

**Status: ⚠️ partial** · **MapStruct:** @SubclassMapping(source = Sub.class, target = SubDto.class) — polymorphic
dispatch over an abstract/sealed source hierarchy, inheriting the parent method's mapping config.

**telescope:**

```java
sealed interface Payment permits CreditCard, BankTransfer, Crypto {}

Function<Payment, PaymentDto> dispatch = Match.<Payment, PaymentDto>of(Payment.class)
  .when(CreditCard.class, creditCardMapper::forward)
  .when(BankTransfer.class, bankMapper::forward)
  .when(Crypto.class, cryptoMapper::forward)
  .exhaustive(); // throws at build time naming any uncovered permit; .partial() opts out

// codegen sibling: @Bridge on a sealed interface pair emits a pattern-match switch over the permits
```

For sealed hierarchies the coverage is real and in one way stronger — .exhaustive() names every uncovered permit, and
the @Bridge codegen path emits a compile-time switch for sealed-to-sealed pairs. Two genuine gaps versus
@SubclassMapping: (1) Match.of() rejects non-sealed roots, so plain abstract-class hierarchies (common in legacy JPA
models) have no dispatcher — closest workaround is a hand-rolled instanceof chain; (2) per-permit handlers are
independent mappers with no automatic inheritance of shared parent-field config (mitigable by sharing a MapStep[] group
via mapperBuilder().inherit(...), but that is manual). The project's own README lists full @SubclassMapping as a
MapStruct-only shape.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/conversion/Match.java:41-153 (Match builder; :60-67
rejects non-sealed roots; :101-120 exhaustive() verifies coverage via Class.getPermittedSubclasses; :127-129 partial();
:136-153 Prism.downcast-routed dispatch); core/src/test/java/io/github/eschizoid/telescope/MatchTest.java:33-77
(exhaustive dispatch + missing-permit diagnostics), :103-118 (partial escape hatch);
codegen/src/main/java/io/github/eschizoid/telescope/codegen/BridgeProcessor.java:1789-1810 (generateSealed:
sealed-source bridge emits pattern-match switch, requires every permit @Bridge-annotated to a permit of the sealed
target); README.md:456-457 (README concedes "full @SubclassMapping polymorphic dispatch" to MapStruct)</sub>
