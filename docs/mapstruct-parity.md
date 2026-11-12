# telescope ↔ MapStruct — migration coverage matrix

An evidence-grounded audit of MapStruct's feature surface against telescope — a coverage audit, not a parity claim: a
majority of rows are ⚠️ partial, each with its real limitation stated. Every row cites the telescope source and tests it
rests on. A citation names the file and then the class, method, or test display name it means, rather than a line
number, so it can be searched for and stays true when the lines above it move. Audited against MapStruct 1.6.3 semantics
and telescope `main`.

Every row is scored for both of telescope's paths. **Runtime** is the mapper `Telescope.mapper` and
`Telescope.mapperForward` build. **Generated** is a `@Bridge static final` mapper field, whose rows the annotation
processor compiles into a bridge class (ADR-0022), so each conversion is a direct call with no reflection. The two paths
share one pairing spec, and `CrossPathCorpusTest` holds them to the same result on every row the generated path
compiles. A row the generated path cannot compile is refused at compile time, on the row, by a message naming it; a
row's notes name each such refusal that bears on its feature, and the runtime path still runs the row when the field is
built without `@Bridge`.

**Legend:** ✅ full — the use case is covered, possibly via a different idiom that is no worse · ⚠️ partial — the core
use case works, with the real limitation stated in the row's notes · ❌ missing — the path has no form for the feature,
and the row names the workaround.

## Summary

| Area                      | Feature                                                             | Runtime | Generated |
| ------------------------- | ------------------------------------------------------------------- | :-----: | :-------: |
| Core mapping              | [Implicit same-name mapping](#implicit-same-name-mapping)           |   ✅    |    ✅     |
| Core mapping              | [Explicit rename](#explicit-rename)                                 |   ✅    |    ✅     |
| Core mapping              | [Nested source path](#nested-source-path)                           |   ✅    |    ✅     |
| Core mapping              | [Multiple source parameters](#multiple-source-parameters)           |   ⚠️    |    ⚠️     |
| Conversions               | [Implicit type conversions](#implicit-type-conversions)             |   ⚠️    |    ⚠️     |
| Conversions               | [Format strings](#format-strings)                                   |   ✅    |    ✅     |
| Conversions               | [Expressions](#expressions)                                         |   ✅    |    ✅     |
| Conversions               | [Constants and defaults](#constants-and-defaults)                   |   ✅    |    ✅     |
| Collections & containers  | [Collection element mapping](#collection-element-mapping)           |   ✅    |    ✅     |
| Collections & containers  | [Map mapping](#map-mapping)                                         |   ⚠️    |    ⚠️     |
| Collections & containers  | [Collection target strategies](#collection-target-strategies)       |   ✅    |    ✅     |
| Collections & containers  | [Stream support](#stream-support)                                   |   ⚠️    |    ⚠️     |
| Lifecycle & customization | [Before/after hooks](#beforeafter-hooks)                            |   ✅    |    ✅     |
| Lifecycle & customization | [Context parameters](#context-parameters)                           |   ⚠️    |    ⚠️     |
| Lifecycle & customization | [Qualifiers](#qualifiers)                                           |   ✅    |    ✅     |
| Lifecycle & customization | [Mapper composition](#mapper-composition)                           |   ✅    |    ✅     |
| Object creation & update  | [In-place update methods](#in-place-update-methods)                 |   ✅    |    ✅     |
| Object creation & update  | [Object factories](#object-factories)                               |   ⚠️    |    ⚠️     |
| Object creation & update  | [Builder support](#builder-support)                                 |   ⚠️    |    ⚠️     |
| Object creation & update  | [Records and constructor mapping](#records-and-constructor-mapping) |   ⚠️    |    ⚠️     |
| Policies & null handling  | [Unmapped-target policy](#unmapped-target-policy)                   |   ✅    |    ✅     |
| Policies & null handling  | [Null value strategies](#null-value-strategies)                     |   ⚠️    |    ⚠️     |
| Policies & null handling  | [Null check strategy](#null-check-strategy)                         |   ⚠️    |    ⚠️     |
| Policies & null handling  | [Conditional mapping](#conditional-mapping)                         |   ⚠️    |    ⚠️     |
| Advanced                  | [Config inheritance](#config-inheritance)                           |   ⚠️    |    ⚠️     |
| Advanced                  | [Decorators](#decorators)                                           |   ✅    |    ✅     |
| Advanced                  | [DI component models](#di-component-models)                         |   ✅    |    ✅     |
| Advanced                  | [Enum mapping](#enum-mapping)                                       |   ⚠️    |    ⚠️     |
| Advanced                  | [Subclass mapping](#subclass-mapping)                               |   ⚠️    |    ⚠️     |

**Tally — Runtime: 15 ✅ · 14 ⚠️ · 0 ❌ · Generated: 15 ✅ · 14 ⚠️ · 0 ❌.** On the runtime path no feature area is
unreachable; every ⚠️ row has a working idiom for its core use case, with the residual gaps named in its notes. The
generated path scores at least as high as the runtime on every row.

## Core mapping

### Implicit same-name mapping

**Runtime: ✅ full** · **Generated: ✅ full** · **MapStruct:** Unannotated properties map by name automatically

**telescope:**

```java
// No rows = pure deep auto-recursion: same-name components identity-map (a
// same-typed List/Set/Map is copied), nested records/POJOs recurse,
// List/Set/Map/Optional lift automatically
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

**Generated path:** a `@Bridge` field with no rows compiles the same deep same-name copy:

```java
@Bridge
static final Mapper<UserEntity, UserDto> DTO = Telescope.mapper(UserEntity.class, UserDto.class);
```

Each nested pair of records or beans gets a bridge of its own, containers lift, and a nested pair that has a `@Bridge`
field of its own converts through that field's bridge. A field initialised by `Telescope.mapper` is strict and one
initialised by `Telescope.mapperForward` is lenient, as the two runtime factories are.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/Telescope.java, the javadoc on
`map(Class, Class, MapStep...)` (deep recursion, and the 'Same-name 1-liner' paragraph),
`mapper(Class, Class, MapStep...)`, and the 'Lenient by default' paragraph on `mapperForward` ('matches MapStruct's
generated-mapper default'); core/src/test/java/io/github/eschizoid/telescope/DeepMappingTest.java, the test 'Pure
same-name deep copy — no overrides needed' (Telescope.map(SameAddrEntity.class, SameAddrDto.class) with zero rows);
docs/adr/0002-no-fuzzy-auto-mapping.md; codegen/src/main/java/io/github/eschizoid/telescope/codegen/FieldRows.java,
`read` (a `Telescope.mapper` initializer is strict, a `mapperForward` one lenient);
codegen/src/main/java/io/github/eschizoid/telescope/codegen/BridgeProcessor.java, `bridgeNameOf` (a nested pair a field
declares converts through that field's bridge);
codegen/src/test/java/io/github/eschizoid/telescope/codegen/BridgeFieldTest.java, the test 'a field's mapper runs the
bridge generated from its rows, and gives the runtime mapper's result';
codegen/src/test/java/io/github/eschizoid/telescope/codegen/CrossPathCorpusTest.java, the test 'every generated cell is
converted the same way by both paths'</sub>

### Explicit rename

**Runtime: ✅ full** · **Generated: ✅ full** · **MapStruct:** @Mapping(source = "email", target = "contactEmail")

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

**Generated path:** on a `@Bridge` field the same `to(Customer::email, CustomerDto::contactEmail)` row names the pair of
properties, and the processor compiles it into the generated bridge. A row on a field is keyed to the field's own pair:
one naming an accessor of a nested class is refused with "a row keyed to a nested pair has no generated form yet", and a
rename at a nested pair goes on that pair's own `@Bridge` field, which the outer bridge then converts the nested
property through. That is the shape of a MapStruct mapper, with one method per nested pair. A converting `to` row, with
a `BridgeFn` or a forward and a backward method reference, or a `via(...)` row renames and converts one property at
once; the generated code is the same direct call it is for a property that is not renamed. A fan-out, one source
property written into several targets by one `to` row each, takes a `mapperForward` field, and its rows convert the
property the same way into every target. The strict `Telescope.mapper` refuses a fan-out by name on both paths, since
`backward` would have no one target to read the property back from, and both paths refuse a property one row drops and
another writes. A rename onto the target of another source property left under its own name is refused by that
property's name by a strict bridge and the strict `Telescope.mapper`, at any pair with rows of its own and whatever
other rows it has, since the property would have nowhere to be written; a lenient bridge, like `mapperForward`, leaves
it unread instead.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java, `to(Accessor, Accessor)`
(same-typed rename) and `to(Accessor, Accessor, Function, Function)` (typed transform for renames that also change
type); core/src/test/java/io/github/eschizoid/telescope/DeepMappingTest.java, the test '5-level nesting with two renames
— every field threads through correctly' (to(CompanyEntity::founded, CompanyDto::since) rename in 5-level nesting);
core/src/main/java/io/github/eschizoid/telescope/Telescope.java, the javadoc on `map(Class, Class, MapStep...)` (a
rename row applies wherever recursion lands on that type pair);
codegen/src/test/java/io/github/eschizoid/telescope/codegen/CrossPathCorpusTest.java, the tests 'a property both renamed
and converted by its own conversion converts the same way on both paths' and 'a property left under its own name whose
target a rename of another property fills is refused by name, the same way on both paths';
internal/src/main/java/io/github/eschizoid/telescope/internal/pairing/SourceWrites.java (a fan-out refused by a mapper
that converts both ways, and a property both dropped and written refused, on both paths);
codegen/src/main/java/io/github/eschizoid/telescope/codegen/FieldRows.java (a fan-out refused when its rows convert
differently); codegen/src/test/java/io/github/eschizoid/telescope/codegen/CrossPathCorpusTest.java, the test 'a property
written into two targets of a two-way mapper, or both dropped and written, is refused by name on both paths';
codegen/src/main/java/io/github/eschizoid/telescope/codegen/FieldRows.java, `accessor` (a row keyed to a nested pair
refused); codegen/src/test/java/io/github/eschizoid/telescope/codegen/BridgeFieldTest.java, the test 'a row the
generated bridge cannot express is refused by name' (the `to(Address::city, AddressDto::city)` case)</sub>

### Nested source path

**Runtime: ✅ full** · **Generated: ✅ full** · **MapStruct:** @Mapping(source = "customer.address.city", target =
"city") — dotted path into nested source

**telescope:**

```java
import static io.github.eschizoid.telescope.Telescope.path;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

// Nested source -> flat target: source side is a real Telescope path, each hop typed
Mapper<Order, OrderDto> mapper = Telescope.mapper(
    Order.class, OrderDto.class,
    to(path(Order::customer, Customer::address, Address::city), OrderDto::city));

// path(...) is shorthand for the field chain, which reads the same way:
to(Telescope.of(Order.class).field(Order::customer).field(Customer::address).field(Address::city), OrderDto::city)

// Or with a @Focus-generated navigator (zero runtime reflection on the path):
to(OrderTelescope.of().customer().address().city(), OrderDto::city)
```

All three shapes covered: nested source -> flat target, flat source -> nested target (building each null object on the
way, described in the 'Null objects on the path' paragraph of `to(Accessor, Telescope)` in Mapping.java), and nested ->
nested. Paths are typed Telescope values instead of dotted strings, so javac checks every hop. A source path is started
at the mapper's source; one whose first property is read through a class the source neither is nor extends is refused
when the mapper is built, and by the compile-time verifier where it can tell. Rows using Telescope paths apply at the
outer (source, target) pair only — same scope as MapStruct's per-method @Mapping. A path through `field` steps alone
that the mapper writes through, a source path on a two-way mapper or a target path on any mapper, is refused by name
when the mapper is built if it steps through a class no write strategy builds; a path of another shape is set through
the lattice as it is.

A path through `field` steps alone reads a null object on the way as the leaf's null value, and a write through it
builds a null object unless the value is null: a source path writes back on `backward` and `patch`, a target path on
`forward`.

**Generated path:** full, all three shapes. A source path into a target property, a source property into a target path,
and a row with a path on both sides, each path written in place from `Telescope.of` or `Telescope.ofBean`, as
`Telescope.path(...)`, or as a `@Focus` or `@BeanFocus` navigator's chain (the `OrderTelescope.of()` form above, ended
by a property or by `get()`), each of which generates the same code as that chain, compile into a null-safe chain of
reads, each object read once, and direct calls to each class's accessors and write strategy, and handle a null object on
the way as the runtime does. A row with a path on both sides reads through the one and writes through the other in each
direction the mapper converts, after the other paths through the same first property in the order the rows came, and
takes no part in `patch`, on both paths. Its two paths end at a property of one type, or the row is refused by name. A
path held in a variable, a step other than `field` (`filter`, `then`, `fieldByName`, a navigator's container step or
bridge hop) and a lambda step are refused on the row by name. A mapper that writes through a path refuses one through a
class no write strategy builds, on both paths in the same words.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java, `to(Telescope, Accessor)`
(javadoc: 'Closes MapStruct's @Mapping(source = "a.b.c", target = "flat")'), `to(Telescope, Telescope)` (both nested),
and `to(Accessor, Telescope)` (the nested-target mirror);
core/src/test/java/io/github/eschizoid/telescope/TelescopeMappingTest.java, the tests 'forward reads at the nested
source path and writes to the flat target accessor', 'single-focus on both sides: read at src telescope, write at tgt
telescope', and 'real @Focus-generated navigators on BOTH sides of Mapping.to(Telescope, Telescope)';
core/src/test/java/io/github/eschizoid/telescope/NestedSourcePathTest.java and NestedTargetPathTest.java;
codegen/src/test/java/io/github/eschizoid/telescope/codegen/CrossPathCorpusTest.java, the tests 'a row reading the
source through a path converts the same way on both paths, a null object on the way included' and 'a row writing a
source property into the target through a path converts the same way on both paths, a null object on the way included'
and 'a row with a path on both sides converts the same way on both paths, a null object on either side included';
codegen/src/main/java/io/github/eschizoid/telescope/codegen/FieldRows.java, `steps` (the paths a field compiles, and the
refusal of every other step) and `pathToPath` (a path on both sides, and the refusal of two paths ending at different
types); codegen/src/test/java/io/github/eschizoid/telescope/codegen/BridgeFieldTest.java, the tests 'a row the generated
bridge cannot express is refused by name' (the path cases) and 'a field reading the source through a path into a target
path runs its generated bridge, giving what the runtime mapper gives';
codegen/src/test/java/io/github/eschizoid/telescope/codegen/PathShorthandBridgeTest.java, including the test 'a
generated navigator in a row generates exactly what the path(...) it stands for generates';
codegen/src/test/java/io/github/eschizoid/telescope/codegen/CrossPathCorpusTest.java, the test 'a row whose path is a
generated navigator converts the same way on both paths as the path it stands for'</sub>

### Multiple source parameters

**Runtime: ⚠️ partial** · **Generated: ⚠️ partial** · **MapStruct:** CustomerDto toDto(Customer c, Address a) — one
target built from several sources

**telescope:**

```java
import static io.github.eschizoid.telescope.mapping.MergeStep.auto;
import static io.github.eschizoid.telescope.mapping.MergeStep.from;

Mapper<Sources, Profile> mapper = Telescope.merge(
  Profile.class,
  auto(Customer.class), // backfill same-name fields from Customer
  from(Audit::createdBy, Profile::createdBy), // source slot inferred from the accessor's class
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

**Generated path:** partial, and closer to MapStruct's typed signature than the runtime. A `@Bridge` field initialised
by the `Telescope.merge(...)` call above written in place compiles into a bridge with a typed
`forward(Customer customer, Audit audit)`, its parameters in the order the rows first name their classes, and a
`forward(Sources)` the field's mapper calls, so `forward(Sources.of(customer, audit))` builds the same target on both
paths. Each component is decided as the runtime decides it: a `from` row binds the component it names, and an `auto` row
each component of a name its source has that no row before it binds, so where two sources carry one name the first
`auto` fills it. Two rows binding one component, a row naming no component and an `auto` match of two types are refused
when the field is compiled, in the words the runtime refuses them with when the mapper is built, as is a `from` row
reading through anything but an accessor of a source class (a static method taking the source, or a method of another
object), which names no source the bag holds; a missing source fails `forward` in the runtime's words, at the same
component. The generated path's own limits: the target is a public record declaring no type parameters, a bean target is
refused by name, and so are a primitive component no row binds and a `from` row reading a type its component does not
hold (possible when javac infers the row's shared type as a supertype of both), each of which the runtime path accepts
and then fails to construct on every `forward`, the second with "Failed to construct". Like the runtime merge, it is
forward only.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/Telescope.java, `merge(Class, MergeStep...)` and its
javadoc, including the 'Distinct runtime classes' and 'Backward is unsupported' paragraphs;
core/src/main/java/io/github/eschizoid/telescope/mapping/MergeStep.java, `from(Accessor, Accessor)` (class-inferred
rows) and `auto(Class)` (same-name backfill); core/src/test/java/io/github/eschizoid/telescope/MergeTest.java, the tests
'forward assembles a target from two sources, class inference picks each slot', '3-source forward — same factory shape,
just more rows', '5-source forward — same factory, 5 sources, 5 rows', and 'forward called with a Sources bag missing
one of the row source classes throws naming the class' (IllegalStateException at forward time);
codegen/src/main/java/io/github/eschizoid/telescope/codegen/FieldRows.java, `merged` (the rows a merge field compiles);
codegen/src/main/java/io/github/eschizoid/telescope/codegen/BridgeProcessor.java, `emitMerge` (each component decided as
the runtime merge decides it);
internal/src/main/java/io/github/eschizoid/telescope/internal/pairing/PairingMessages.java, the `merge*` messages both
paths refuse and fail with; codegen/src/test/java/io/github/eschizoid/telescope/codegen/CrossPathCorpusTest.java, the
test 'a merge on a @Bridge field builds the same target on both paths, typed or from the bag, and refuses alike';
codegen/src/test/java/io/github/eschizoid/telescope/codegen/BridgeFieldTest.java, the tests 'a merge field's mapper runs
its generated bridge forward, and refuses backward as the runtime does' and 'a merge field the generated bridge cannot
build is refused by name'; benchmarks/src/jmh/java/io/github/eschizoid/telescope/benchmarks/MergeBenchmark.java,
`bridgeField` and `bridgeTyped` against `handBag` and `handMerge`</sub>

## Conversions

### Implicit type conversions

**Runtime: ⚠️ partial** · **Generated: ⚠️ partial** · **MapStruct:** Automatic conversions between source/target types:
primitive<->wrapper autoboxing, `String<->number`, `enum<->String`, `date/time<->String`, widening numeric conversions —
all silent, no declaration needed.

**telescope:**

```java
record Order(String id, String total, String status, List<String> tags) {}
record OrderDto(long id, BigDecimal total, Status status, Set<String> tags) {}

// No row on either path: same-named properties convert, and the mapper converts back.
final var mapper = Telescope.mapper(Order.class, OrderDto.class);   // runtime
@Bridge static final Mapper<Order, OrderDto> DTO = mapper(Order.class, OrderDto.class); // generated

// A widening converts forward only, so a two-way mapper refuses it and mapperForward takes it:
Telescope.mapperForward(Count.class, CountDto.class); // int -> long, Integer -> BigDecimal, ...

// Dates and times against text, and narrowing, stay explicit rows:
Telescope.mapper(Src.class, Dst.class,
    to(Src::at, Dst::at, Instant::toString, Instant::parse));
```

Built in on both paths, in both directions: `String` to and from every number but `char` (the primitives, their
wrappers, `BigInteger` and `BigDecimal`), an enum by constant name, and `boolean`/`Boolean` (`true` or `false` in any
case). A number widens to one that holds each of its values, by Java's widening rules plus the integral types and
`BigInteger` into `BigInteger` and `BigDecimal`; that converts forward only, so `Telescope.mapper` and a strict
`@Bridge` refuse the pair naming the row to write, while `mapperForward` and `@Bridge(lenient = true)` take it. A list
and a set convert into each other's kind, each element converted too: into a set, equal elements collapse to the first,
and into a list, the elements take the set's iteration order. Two different enum types whose constants match by name
convert as well (see [Enum mapping](#enum-mapping)), and so do a primitive and its wrapper, matching MapStruct's
null->0/false behaviour. The conversions apply to properties that pair by exact name, or by a plain `to(src, tgt)` row;
no name is guessed (ADR-0002).

A `String` that is no valid number, constant or boolean fails on that value with the property and the value named, for
example `'total' got "12x", not a BigDecimal`, the same message on both paths. The generated bridge calls
`Long.parseLong`, `Enum.valueOf`, `new BigDecimal(...)` and `String.valueOf` directly and reads each property once.

What stays explicit: narrowing (`long` to `int`, `double` to `float`, `double` to `BigDecimal`, which has no NaN or
infinity), and dates and times against a `String`, which need a format. Each is refused when the mapper is built, or at
compile time on `@Bridge`, with a message naming the row that converts it: `toFormatted(source, target, pattern)` for a
date or a time written into a `String` (see [Format strings](#format-strings)), and
`to(source, target, forward, backward)` for the others.

**Generated path:** partial for the same reason as the runtime path. The bridge decides each pair through the same
`PairingRules`, so it converts and refuses the same pairs; the `to(source, target, forward, backward)` row that converts
a date or a narrowing names its two directions by method reference, since an inline lambda is refused on a `@Bridge`
field.

<sub>Evidence: internal/src/main/java/io/github/eschizoid/telescope/internal/pairing/PairingRules.java, `builtIn` and
`scalarOf` (which pairs convert, in which directions) and the `reshapes` check in `decidePair` (a list against a set);
internal/src/main/java/io/github/eschizoid/telescope/internal/pairing/Scalar.java, `widensTo`;
internal/src/main/java/io/github/eschizoid/telescope/internal/pairing/PairingMessages.java, `notAValue`,
`wideningOneWay`, `narrowing` and `textNeedsFormat`; core/src/main/java/io/github/eschizoid/telescope/BuiltIns.java (the
runtime conversions); codegen/src/main/java/io/github/eschizoid/telescope/codegen/BridgeProcessor.java,
`emitConvertDirection` and `emitReshapeDirection` (the generated ones);
codegen/src/test/java/io/github/eschizoid/telescope/codegen/CrossPathCorpusTest.java, the test 'a built-in conversion
converts the same way on both paths, and both refuse and fail alike';
codegen/src/test/java/io/github/eschizoid/telescope/codegen/BuiltInAgreementTest.java (the verifier agrees);
core/src/test/java/io/github/eschizoid/telescope/BuiltInConversionTest.java; docs/adr/0002-no-fuzzy-auto-mapping.md
(amended for built-in conversions)</sub>

### Format strings

**Runtime: ✅ full** · **Generated: ✅ full** · **MapStruct:** @Mapping(numberFormat = "$#.00") / @Mapping(dateFormat =
"dd.MM.yyyy") — annotation attribute, MapStruct generates DecimalFormat/DateTimeFormatter code for both directions.

**telescope:**

```java
// Both directions: forward formats, backward and patch parse the text back.
Telescope.mapper(Order.class, OrderDto.class,
    toFormatted(Order::shipDate, OrderDto::shipDate, "dd.MM.yyyy"),
    toFormattedNumber(Order::total, OrderDto::total, "$#,##0.00"),
    toFormatted(Order::placedAt, OrderDto::placedAt, "yyyy-MM-dd HH:mm", ZoneOffset.UTC)); // an Instant needs a zone

// Forward only, for a pattern that does not read back:
Telescope.mapperForward(Order.class, OrderView.class,
    toFormattedOneWay(Order::shipDate, OrderView::month, "MMMM yyyy"));

// Codegen (@Bridge field) form, the same rows:
public record Order(LocalDate shipDate, BigDecimal total) {
  @Bridge
  static final Mapper<Order, OrderDto> DTO = Telescope.mapper(Order.class, OrderDto.class,
    toFormatted(Order::shipDate, OrderDto::shipDate, "dd.MM.yyyy"),
    toFormattedNumber(Order::total, OrderDto::total, "$#,##0.00", Locale.US));
}
```

`toFormatted` writes a `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetDateTime`, `OffsetTime`, `ZonedDateTime`,
`Instant`, `Year`, `YearMonth` or `MonthDay` into a `String` property through a `DateTimeFormatter` pattern, with a
`ZoneId` overload. `toFormattedNumber` writes a primitive number, its wrapper, a `BigInteger` or a `BigDecimal` through
a `DecimalFormat` pattern, with a `Locale` overload. The two-way rows parse on `backward` and `patch`, and their
`OneWay` siblings, which `Telescope.mapper` refuses by name, format forward only. Both properties are method references,
and `javac` refuses a source property that is not a date, a time or a number.

The pattern is checked when the row is built: one the JDK does not compile, one naming a field the class lacks (`HH:mm`
for a `LocalDate`), and an `Instant` given no zone are each refused by name, with the property named. A two-way row is
also probed with dates across three centuries and with months whose English names share a first letter, and with numbers
of the digits the pattern writes, and a pattern whose text reads back as another value is refused: `yy-MM-dd` reads
1899-12-31 back as 2099-12-31, `dd MMMMM yyyy` reads January back as July, and `0.#E0` reads 127 back as 130. Writing
less than the whole time is not refused: `yyyy-MM-dd HH:mm` reads back the time with no seconds. Text that does not
parse fails with the target property, the text and the format named,
`'shipDate' got "2026/12/31", not a LocalDate in the format "dd.MM.yyyy"`, the same message on both paths. A number
reads back only when the whole text is read, and into an integral class only when it converts exactly. Where MapStruct
parses with `DecimalFormat.parse`, which stops at the first character it cannot read, telescope refuses trailing text.

**Generated path:** full. On a `@Bridge` field the pattern is a string constant, which the processor checks at compile
time through the same shared decision, and `MapperVerifierProcessor` checks it on a runtime mapper call too. The
generated bridge holds a `DateTimeFormatter` as a `static final` constant and calls it directly; a `DecimalFormat`,
which is not safe to share between threads, is copied from a `static final` prototype for each call on both paths. A
zone or a locale is a `static final` field the bridge can name, such as `ZoneOffset.UTC` or `Locale.GERMANY`. A pattern
that is not a compile-time constant, and a zone or a locale given as a call such as `ZoneId.of("Europe/Paris")`, are
refused on the row by name, while the runtime builds them: the bridge's formatter is a constant built from text the
processor reads, which is an inherent limit of the generated path, recorded in ADR-0019. Hold the value in a
`static final` field, or build the mapper without `@Bridge`.

What stays explicit on both paths: text parsed into a date or a number on a forward-only mapper whose source holds the
text, `java.util.Date` and `Calendar`, and a date pattern in a locale other than the default, each of which takes a
`to(src, tgt, forward, backward)` or `toOneWay` row.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java, `toFormatted`,
`toFormattedOneWay`, `toFormattedNumber` and `toFormattedNumberOneWay`;
core/src/main/java/io/github/eschizoid/telescope/mapping/Formatted.java (the runtime conversion);
internal/src/main/java/io/github/eschizoid/telescope/internal/pairing/TextFormats.java (which patterns are accepted, and
how a number reads back) and PairingMessages.java, `formattedPhrase` and the `format*` refusals;
codegen/src/main/java/io/github/eschizoid/telescope/codegen/FieldRows.java, `formatted`, `temporalFn` and `numberFn`
(the generated conversion); codegen/src/test/java/io/github/eschizoid/telescope/codegen/CrossPathCorpusTest.java, the
test 'a formatted row on a @Bridge field formats, parses, refuses and fails the same way on both paths';
codegen/src/test/java/io/github/eschizoid/telescope/codegen/MapperVerifierProcessorTest.java, the nested 'Formatted
rows' tests; core/src/test/java/io/github/eschizoid/telescope/FormattedRowTest.java;
benchmarks/src/jmh/java/io/github/eschizoid/telescope/benchmarks/FormattedRowBenchmark.java</sub>

### Expressions

**Runtime: ✅ full** · **Generated: ✅ full** · **MapStruct:** @Mapping(expression = "java(...)") for computed target
values and defaultExpression = "java(...)" for lazily-computed null fallbacks; string-templated Java inside an
annotation.

**telescope:**

```java
Telescope.mapperForward(Order.class, OrderDto.class, // one-way: the hook below fills displayName from two fields
    to(Order::id, OrderDto::id),
    compute(OrderDto::createdAt, Instant::now),          // expression: fresh per forward call
    compute(OrderDto::traceId, () -> UUID.randomUUID().toString()),
    compute(OrderDtoTelescope.of().audit().createdAt(), Instant::now), // nested target
    // source-referencing expression (MapStruct: expression = "java(Names.display(o))"):
    compute(OrderDto::displayName, Names::display)); // static String display(Order o)
  // ...or, as the defaultExpression analog (instead of compute on the same field):
  //   toOrElseGet(Order::createdAt, OrderDto::createdAt, Instant::now)
// or per-field: toOneWay(Order::total, OrderDto::totalText, t -> "$" + t)
```

A better idiom than MapStruct's stringly expression: plain typed Java, javac-checked, refactor-safe, no expression
parser. compute() takes a Supplier, or a method reference to a function of the whole source (a FromSource; a function
held as a plain Function is passed as fn::apply) for a value derived from several source fields; toOneWay(src, tgt, fn)
converts one source field. compute/constant rows are forward-only by design (backward drops the slot).

**Generated path:** the same `compute` row compiles when its supplier is a method reference (`Instant::now`) or a
`static final` field holding one, and when its function is a method reference taking the source (`Names::display`), a
method of the source, or a `static final` field holding one, which the generated bridge calls directly; `toOrElseGet`
and `toOneWay` name theirs the same way. That is MapStruct's expression calling a helper, written as a typed reference.
An inline lambda, as in the `traceId` row above, is refused on the row, and so is a nested target written as a telescope
path, as in the `audit().createdAt()` row: a computed value at a nested pair goes on that pair's own `@Bridge` field.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java, `compute(Accessor, Supplier)`
(javadoc names the expression=java(Instant.now()) gap), `compute(Accessor, FromSource)` (a value read from the whole
source), `compute(Telescope, Supplier)` (nested target), both `toOrElseGet` overloads (defaultExpression, plus the
predicate-gated form), and `toOneWay` (per-field function);
core/src/main/java/io/github/eschizoid/telescope/conversion/Mapper.java, `afterForward(BiFunction)` (source-aware hook,
typed @AfterMapping analog); core/src/test/java/io/github/eschizoid/telescope/MappingConstantComputeTest.java, the
nested class 'Mapping.compute(Tgt::field, Supplier) — lazy, fresh per call' (fresh-per-call and forward-only semantics
pinned); codegen/src/main/java/io/github/eschizoid/telescope/codegen/FieldRows.java (a `compute` row on a `@Bridge`
field takes a method reference or a `static final` field holding the supplier, and `accessor` refuses a target written
as a path); codegen/src/test/java/io/github/eschizoid/telescope/codegen/CrossPathCorpusTest.java, the test 'a compute
row reading the source fills its property the same way on both paths';
codegen/src/test/java/io/github/eschizoid/telescope/codegen/ComputeFromSourceFieldTest.java; README.md, the `constant` /
`compute` example under "Mapping"</sub>

### Constants and defaults

**Runtime: ✅ full** · **Generated: ✅ full** · **MapStruct:** @Mapping(target = "x", constant = "fixed") stamps a
literal; @Mapping(target = "x", defaultValue = "fallback") substitutes when source is null.

**telescope:**

```java
Telescope.mapper(Order.class, OrderDto.class,
    to(Order::id, OrderDto::id),
    constant(OrderDto::tenant,     "production"),       // @Mapping(constant = ...), typed literal
    constant(OrderDto::apiVersion, 7),
    constant(OrderDtoTelescope.of().shipping().country(), "US"),        // nested target
    toOrElse(Order::region, OrderDto::region, "EMEA"),                  // @Mapping(defaultValue = ...)
    toOrElse(Order::displayName, OrderDto::displayName, "(unnamed)", String::isBlank), // predicate-gated
    toOrElseGet(Order::traceId, OrderDto::traceId, () -> UUID.randomUUID().toString()));  // lazy default

// Codegen (@Bridge field) form — the same rows, with literal values:
public record User(String id, String region) {
  @Bridge
  static final Mapper<User, UserEntity> ENTITY = Telescope.mapper(
    User.class,
    UserEntity.class,
    constant(UserEntity::source, "API"),
    toOrElse(User::region, UserEntity::region, "EMEA")
  );
}
```

Complete coverage of MapStruct's constants and defaults on both paths, statically typed
(constant(OrderDto::apiVersion, 7) is an int, not a string literal to parse). The runtime path exceeds MapStruct:
predicate-gated toOrElse/toOrElseGet handle empty-string/empty-collection 'missing' semantics MapStruct's strictly-null
defaultValue cannot, and nested-target constants (@Mapping(target="a.b.c", constant=...)) work via Telescope paths.
Known asymmetries, documented and tested: constant is forward-only (backward drops the slot), and a substituted default
round-trips backward as itself rather than the original null.

**Generated path:** a constant or default is a literal or an enum constant, which the bridge writes in place, and
`toOrElseGet` names its supplier by method reference. That covers MapStruct's surface, whose `constant` is a literal and
whose `defaultValue` is strict-null. The predicate-gated `toOrElse` and `toOrElseGet` compile too, the predicate a
method reference or a `static final` field holding one, tested only on a value that is not null, as the runtime tests
it. A value computed in place (`String.valueOf(1)`) is refused with "a value in a @Bridge mapper must be a literal or an
enum constant", and so is a nested target written as a telescope path on the row; a constant at a nested pair goes on
that pair's own `@Bridge` field.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java, `constant(Accessor, Object)`
(javadoc names the @Mapping constant gap), `constant(Telescope, Object)` (nested target), both `toOrElse` overloads
(strict-null and predicate-gated), and both `toOrElseGet` overloads (lazy and predicate-gated);
core/src/test/java/io/github/eschizoid/telescope/MappingConstantComputeTest.java, the nested class
'Mapping.constant(Tgt::field, value) — eager literal' (constant stamps the literal; backward drops it to the type
default); core/src/test/java/io/github/eschizoid/telescope/MappingOrElseTest.java, every test (null->default,
pass-through, lazy supplier, empty-string/empty-collection predicates);
codegen/src/main/java/io/github/eschizoid/telescope/codegen/FieldRows.java (a constant or default on a `@Bridge` field
is a literal or an enum constant, and `orElse` compiles the predicate-gated rows);
codegen/src/test/java/io/github/eschizoid/telescope/codegen/BridgeFieldTest.java, the tests 'a default computed by a
supplier converts the same on both paths' and 'a row the generated bridge cannot express is refused by name' (the
computed-value cases); codegen/src/test/java/io/github/eschizoid/telescope/codegen/CrossPathCorpusTest.java, the test 'a
when(...) row and a toOrElse row with a predicate convert the same way on both paths, in each direction';
codegen/src/test/java/io/github/eschizoid/telescope/codegen/ConstantLiteralEmissionTest.java; README.md, the `constant`
/ `compute` example under "Mapping"</sub>

## Collections & containers

### Collection element mapping

**Runtime: ✅ full** · **Generated: ✅ full** · **MapStruct:** `List<CarDto>` map(`List<Car>` cars) — generated loop
reuses the element mapping method automatically

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
including nested containers like `List<Optional<X>>` and `Map<K,List<X>>`. Bidirectional round-trip comes free. A list
and a set convert into each other's kind, as MapStruct's List -> Set copy does: into a set, equal elements collapse to
the first, and into a list, the elements take the set's iteration order. A `Deque` or `Queue` field is viewed as a list,
and a field declared as the general `Collection` takes the other side's kind (`PairingRules.settledAgainst`). Target
concrete class (ArrayList/LinkedList/Deque/...) is honored through the allocation table in `PairingRules` and the rules
`ContainerAllocation` layers on it, which the runtime and codegen paths share, rendered at runtime by
`ContainerLifts.allocatorFor`.

**Generated path:** each element pair gets a bridge of its own and the bridge loops over the container, through
`List<Optional<X>>` and `Map<K, List<X>>` alike; a list and a set reshape into each other, and the container class is
the one the shared `ContainerAllocation` decides. A `via` row naming the element pair's `@Bridge` field, or a class with
static `forward` and `backward` methods, converts each element. A `via` row naming a runtime `Mapper` value, as
`userMapper` above, is refused ("names another @Bridge mapper field"), and the element rename in the first example goes
on the element pair's own `@Bridge` field, since a row keyed to a nested pair is refused.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/DeepMap.java, the class javadoc
(List/Set/Map-values/Optional lift the element Iso, containers nest to any depth), the LiftContainer branch of
`computeAutoIso` (recurses on the element type), and `liftViaIfNeeded` (element-level Mapper auto-lifted through the
accessor's container); core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java,
`via(Accessor, Accessor, Mapper)` and its javadoc ('List pair, auto-lifts');
core/src/test/java/io/github/eschizoid/telescope/DeepMappingTest.java, the tests '5-level nesting with two renames —
every field threads through correctly' (element rename fires inside List of List of List),
`List<Optional<Record>> auto-lifts both layers; empty Optional round-trips`,
`Map<K, List<Record>> auto-lifts values + list element`, and the `PreBuiltViaMapper` nested class (via(...) lifts
`Mapper<UserEntity,UserDto>` through a List accessor pair and round-trips, and through a pair declared as the general
Collection); codegen/src/main/java/io/github/eschizoid/telescope/codegen/BridgeProcessor.java, `emitReshapeDirection` (a
list against a set); codegen/src/main/java/io/github/eschizoid/telescope/codegen/FieldRows.java, `via` (what a `via` row
on a `@Bridge` field may name); codegen/src/test/java/io/github/eschizoid/telescope/codegen/CrossPathCorpusTest.java,
the tests 'a via row naming a class on a List, Set, Optional or Map property converts each element the same way on both
paths' and 'a container is built the same way on both paths, or refused by both in the same words';
codegen/src/test/java/io/github/eschizoid/telescope/codegen/NestedContainerParityTest.java</sub>

### Map mapping

**Runtime: ⚠️ partial** · **Generated: ⚠️ partial** · **MapStruct:** `Map<String, StringDto>` map(`Map<String, Entity>`
m); @MapMapping(keyDateFormat/valueDateFormat/keyQualifiedBy/valueQualifiedBy)

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

VALUE mapping is fully covered: auto-lifted through `ContainerLifts.liftMapIntoTargetRaw` at any depth, or explicit
via(...) with a value-level Mapper. KEY mapping is deliberately not supported — key types must match exactly and keys
are copied verbatim; a key conversion (e.g. String keys -> enum keys) requires a manual whole-field to(src, tgt, fwd,
bwd) transform over the entire Map. No per-key/per-value format sugar like @MapMapping(valueDateFormat) — the equivalent
is a hand-written stream/collect transform row. Target Map concrete type is honored; an EnumMap target is built from the
key class its declaration names.

**Generated path:** partial for the same reasons. Values convert through the value pair's bridge, and keys are copied
and must be the same type. The bridge refuses a key mismatch at compile time with its own message, which names the
property and both key types but is worded differently from the runtime's. An explicit value conversion is a `via` row
naming the value pair's `@Bridge` field or a class, and a whole-map transform names its two directions by method
reference.

<sub>Evidence: `DeepMap`'s MAP_VALUES lift; `ContainerLifts.liftMapIntoTargetRaw` ('Preserves source keys verbatim');
`DeepMap`'s via(...) rejection when Map key types differ ('Key types must match exactly; auto-lifting preserves the
source keys'); `ContainerLifts.allocatorFor` (HashMap/LinkedHashMap/TreeMap/ConcurrentHashMap/...; EnumMap built from
its key class, by the `EnumMap` entry of the allocation table in `PairingRules`);
internal/src/main/java/io/github/eschizoid/telescope/internal/pairing/PairingRules.java, `decidePair` (mismatched key
types -> Incompatible) and `containerViewOf` with `decidableKey` (a wildcard key, or one that mentions a type variable,
means the Map is not treated as liftable); core/src/test/java/io/github/eschizoid/telescope/DeepMappingTest.java, the
test '5-level nesting with two renames — every field threads through correctly' (Map values recursed, keys preserved)
and `Map<K, List<Record>> auto-lifts values + list element`;
core/src/test/java/io/github/eschizoid/telescope/DeepMapCoverageTest.java, the nested classes 'D2 — autoIso Map key-type
mismatch' and 'D4 — via(...) Map key-type mismatch' (key-type mismatch rejection asserted);
codegen/src/main/java/io/github/eschizoid/telescope/codegen/BridgeProcessor.java (each component pair decided through
`PairingRules.decidePair`); codegen/src/test/java/io/github/eschizoid/telescope/codegen/CrossPathCorpusTest.java, the
tests 'a map whose declared key is a type variable is copied by both paths, and refused by both the same way, lenient or
not' and 'a via row naming a class on a List, Set, Optional or Map property converts each element the same way on both
paths'; codegen/src/test/java/io/github/eschizoid/telescope/codegen/SortedMapKeyParityTest.java</sub>

### Collection target strategies

**Runtime: ✅ full** · **Generated: ✅ full** · **MapStruct:** CollectionMappingStrategy = ACCESSOR_ONLY /
SETTER_PREFERRED / ADDER_PREFERRED / TARGET_IMMUTABLE; @MappingTarget updates an existing target's collections

**telescope:**

```java
// fresh-allocation path: target collection's DECLARED concrete class is honored
// (ArrayList/LinkedList/Vector/CopyOnWriteArrayList/TreeSet/ConcurrentHashMap/... + user subclasses)
final Mapper<Order, OrderEntity> mapper = Telescope.mapper(
    Order.class, OrderEntity.class,
    writeBean(OrderEntity.class, ADDERS),          // lines filled through addLine(LineEntity)
    writeBean(CashRegisterEntity.class, BUILDER)); // per-class override

// @MappingTarget analog — mutate an existing managed entity in place:
final OrderEntity managed = repository.findById(id).orElseThrow();
mapper.into(managed, dto);   // getLines() cleared and refilled through addLine, same list instance
repository.save(managed);
```

What exists: (1) target collection concrete type preserved on fresh allocation, with plan-time errors instead of silent
ArrayList substitution; (2) SETTER_PREFERRED is the default: a collection is written through its setter by `forward`;
(3) ADDER_PREFERRED is the `ADDERS` write strategy (`writeBean(X.class, ADDERS)`, `writeBeans(ADDERS)` or
`@Bridge(writeStrategy = ADDERS)`): each collection a field of its name stores is filled one element at a time through
its adder (`addLine` for `lines`), which wires the back-reference of a bidirectional association, and needs no setter; a
class with such a collection and no adder of its element is refused by the property's name when the mapper is built and
at compile time; (4) `Mapper.into(target, source)` is the @MappingTarget analog and merges where it can: a `Collection`
or `Map` the target's getter returns that can be changed is cleared and refilled, so it keeps its identity (orphan
removal on a managed collection keeps working), through the adder under `ADDERS`, each adder running once per element; a
null one, or one that cannot be changed (an unmodifiable JDK container, `Arrays.asList`, a library's immutable
collection), is replaced through the setter, as TARGET_IMMUTABLE does; and only a container property with no setter that
holds none or an unchangeable one is refused by name. The collection write goes through the `ContainerCopy.Policy` SPI,
which a persistence module can replace (skip an unloaded collection, merge by `@Id`). The remaining differences:
ACCESSOR_ONLY on a collection with neither a setter nor an adder has no form, so such a class gains an adder, and
TARGET_IMMUTABLE as an opt-out of the merge for a modifiable container has no form. into() also needs a public setter
for every property other than a container that the mapper writes, refusing by name a property that has none (see
[In-place update methods](#in-place-update-methods)), and rejects record targets.

**Generated path:** full, for the same reasons. Fresh allocation follows the same `ContainerAllocation`, and a strict
bridge over a bean target gets an `into` of its own, which refills each container through the same `ContainerCopy`
methods as the runtime `into` and calls the adder directly under `ADDERS`; where the bridge has no `into`, the field's
mapper runs the runtime one. A `writeBean` hint keyed to the pair's own source or target compiles, `ADDERS` included. A
`writeBean` keyed to a class that is neither side and that no path of the rows steps through is refused ("a nested pair
the generated bridge has no hint for yet"), and a `writeBeans` default that reaches a bean below the pair is refused,
since the generated bridge builds that pair through a bridge of its own. Either goes on the nested pair's own `@Bridge`
field, with its own hint.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/conversion/Mapper.java, `into(Object, Object)` and
`writeSlots` (containers refilled or replaced through `ContainerCopy`, every held container read and one nothing can
write refused before the first write, containers written before every other property);
core/src/main/java/io/github/eschizoid/telescope/conversion/ContainerCopy.java, `Policy`, `Refill`, `refuseUnwritable`,
`refill`, `fill`, `add` and `deferAdders` (the one hook both paths call);
internal/src/main/java/io/github/eschizoid/telescope/internal/pairing/CollectionWrites.java, `intoWrite` (refill,
replace through the setter or refuse) and the adder's names and choice;
internal/src/main/java/io/github/eschizoid/telescope/internal/AdderCalls.java (no adder run on the value `into` reads
from); internal/src/main/java/io/github/eschizoid/telescope/internal/Beans.java, `addersWriter` and `adders`;
codegen/src/main/java/io/github/eschizoid/telescope/codegen/BridgeProcessor.java, `intoStatements` (the generated
`into`, written for a strict bridge only) and `buildExpr` (adder calls under `ADDERS`);
codegen/src/main/java/io/github/eschizoid/telescope/codegen/MapperVerifierProcessor.java, `addersRefusal` (a
`writeBean(..., ADDERS)` row refused at compile time);
codegen/src/test/java/io/github/eschizoid/telescope/codegen/CrossPathCorpusTest.java, the test 'into refills a container
the target holds where it can change, replaces one it cannot through the setter and refuses one nothing can write, and
the adders strategy fills a collection through its adder once per element or refuses a class with none, on both paths';
core/src/test/java/io/github/eschizoid/telescope/MapperIntoCollectionTest.java;
core/src/main/java/io/github/eschizoid/telescope/ContainerLifts.java, `liftListIntoTargetRaw` with `allocatorFor` (the
result's runtime class is the one `ContainerAllocation` decides for the declared type);
core/src/test/java/io/github/eschizoid/telescope/ContainerAllocatorCorpusTest.java, 'the reflective path allocates
exactly what the generated path allocates, and refuses the rest' (every public `java.base` container class);
codegen/src/main/java/io/github/eschizoid/telescope/codegen/FieldRows.java, `hintsOffThePair` (a `writeBean` keyed
elsewhere refused); codegen/src/test/java/io/github/eschizoid/telescope/codegen/WriteHintRowTest.java, the tests 'a
write hint on a @Bridge field rebuilds the side it names as the runtime path does', 'a writeBean keyed to a class other
than the pair's sides is refused by name' and 'a writeBeans default reaching a bean below records or through sealed
cases is refused by name'</sub>

### Stream support

**Runtime: ⚠️ partial** · **Generated: ⚠️ partial** · **MapStruct:** `List<CarDto>` map(`Stream<Car>` cars) — generated
terminal collect; Stream-typed sources/targets in mapper signatures

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

**Generated path:** partial for the same reasons. The bridge reads container kinds from the same `ContainerView`, so a
`Stream` property is refused on both paths, and the call-site one-liner works with the field's mapper:
`cars.map(CarMappers.CAR::forward).toList()`, each call one direct call into the bridge.

<sub>Evidence: internal/src/main/java/io/github/eschizoid/telescope/internal/pairing/ContainerView.java, the `Kind` enum
(auto-lift container kinds are exactly LIST, SET, COLLECTION, MAP_VALUES, OPTIONAL — no STREAM);
internal/src/main/java/io/github/eschizoid/telescope/internal/pairing/PairingRules.java, `containerViewOf` (recognizes
only Optional, List, Set and Map subtypes and the Deque, Queue and Collection interfaces by name — a Stream-typed
component falls through to Incompatible); grep for 'Stream<' across core/src/main, core/src/test, and examples/ finds
one hit, the JDK type used inside ContainerAllocatorCorpusTest, and no Stream mapping API. One fixture declares a record
named Stream (TelescopeTest); a user type that happens to carry the name is mapped as the record it is;
codegen/src/main/java/io/github/eschizoid/telescope/codegen/BridgeProcessor.java (each component pair decided through
the same `PairingRules`)</sub>

## Lifecycle & customization

### Before/after hooks

**Runtime: ✅ full** · **Generated: ✅ full** · **MapStruct:** @BeforeMapping / @AfterMapping callback methods invoked
around the generated mapping

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

**Generated path:** the same hooks apply to a `@Bridge` field's mapper, and the hooked mapper converts through the
generated bridge with the hooks around it. They go on a field of their own, since a `@Bridge` field's initializer must
be the `Telescope.mapper(...)` or `mapperForward(...)` call itself and a hook chained onto it is refused with that
message:

```java
@Bridge
static final Mapper<Entity, Dto> DTO = Telescope.mapper(Entity.class, Dto.class, to(Entity::id, Dto::id));

static final Mapper<Entity, Dto> STAMPED = DTO.afterForward(Stamps::stamp);
```

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/conversion/Mapper.java, `beforeForward`, both
`afterForward` overloads (the BiFunction one's javadoc cites MapStruct's `@AfterMapping` with `@MappingTarget`),
`beforeBackward`, and both `afterBackward` overloads (Function and BiFunction);
core/src/test/java/io/github/eschizoid/telescope/MapperPostHooksTest.java, every nested class (all four hooks, chaining
order, direction-laziness, asTelescope() propagation);
core/src/test/java/io/github/eschizoid/telescope/MigrationRegressionTest.java, the tests 'Mapper.forward(non-null) still
threads through hooks', 'Mapper.forward — preForward returning null propagates as null (no NPE in iso.to)', and 'hook
chain (afterForward) composes with lenient construction — stamped fields land on the leniently-built target';
core/src/main/java/io/github/eschizoid/telescope/conversion/Mapper.java, the protected `Mapper(Mapper, BridgeGrant)`
constructor (a field's mapper holds the bridge's two directions as its `Iso`, which a hook keeps);
codegen/src/test/java/io/github/eschizoid/telescope/codegen/BridgeFieldTest.java, the tests 'a field's mapper is the
class generated for it, each direction one call into the bridge' (`afterForward` on the field's mapper) and 'a field
that is not static final, or not a mapper call, is refused by name'</sub>

### Context parameters

**Runtime: ⚠️ partial** · **Generated: ⚠️ partial** · **MapStruct:** @Context param threaded through nested mappings
without being mapped itself (e.g. Locale, cycle-tracking)

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

// The cycle-tracking @Context use case needs zero code — cycles are handled automatically, on both paths:
final var mapper = Telescope.mapper(Node.class, NodeDto.class); // self/mutual recursion just works

final NodeDto dto = NodeBridge.forward(node); // the generated @Bridge cuts the same cycle at the same reference
```

The single most common @Context use case — CycleAvoidingMappingContext — is unnecessary: DeepMap's cycle cache +
value-level seen-set handles self/mutual recursion automatically. A generated `@Bridge` does the same for a type that
can reach itself through its properties: it tracks the objects it is converting and maps the reference that leads back
to one of them to `null`, as the runtime mapper does, so both paths give a graph of the same shape. A type that cannot
reach itself is generated with no tracking at all. What's genuinely missing: a per-invocation context parameter threaded
through nested via(...) mappers. forward(a) takes only the source; row functions and hooks capture context at build
time, so per-request context (Locale, tenant) means building a mapper per context value (build cost is non-trivial) or
closing over a ScopedValue/holder manually. No sugar exists for that.

**Generated path:** partial, with the cycle case covered as above. The build-time closure has no generated form: a
`@Bridge` field is one `static final` mapper, its converters are method references or `static final` fields, and the
inline lambda capturing `locale` above is refused on the row. Per-request context reaches a generated mapper through a
hook on the field's mapper, or through a static helper the row names that reads a `ScopedValue`.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/conversion/Mapper.java, `forward(final A a)` and
`backward(final B b)` (neither takes a context argument); core/src/main/java/io/github/eschizoid/telescope/DeepMap.java,
the `FORWARD_SEEN` / `BACKWARD_SEEN` fields (built-in ThreadLocal IdentityHashMap value-level cycle guard);
core/src/test/java/io/github/eschizoid/telescope/CycleHandlingTest.java, every test (Optional/List self-reference and
A-B mutual recursion map without StackOverflow, zero user code);
codegen/src/main/java/io/github/eschizoid/telescope/codegen/BridgeProcessor.java, `guardsCycles` and `emitDirection`
(the compile-time decision and the generated path-carrying overloads);
codegen/src/test/java/io/github/eschizoid/telescope/codegen/CrossPathCorpusTest.java, the test 'an object graph that
leads back to an object being converted maps to the same shape on both paths' (self-loop, two-object and two-type
cycles, cycles through a List, a nested List, a container subtype and an Optional, forward, backward and patch);
core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java, `to(Accessor, Accessor, Function, Function)`
(typed-transform row where a closure captures context);
codegen/src/main/java/io/github/eschizoid/telescope/codegen/FieldRows.java, `Rows.read` (an inline lambda refused on the
row) and `call` (a converter is a method reference the bridge can name)</sub>

### Qualifiers

**Runtime: ✅ full** · **Generated: ✅ full** · **MapStruct:** @Named("formatter") / custom @Qualifier to pick between
multiple candidate mapping methods for the same type pair

**telescope:**

```java
// Runtime: mappers and converters are VALUES — name the exact one per row, no string qualifier:
Telescope.mapper(Customer.class, CustomerDto.class,
    via(Customer::homeAddress, CustomerDto::homeAddress, homeAddressMapper),
    via(Customer::workAddress, CustomerDto::workAddress, workAddressMapper));
// Codegen: @Named analog — several conversion methods on one helper class, picked per row:
public record UserEntity(String id, Instant expiresAt, Instant createdAt) {
  @Bridge
  static final ForwardMapper<UserEntity, UserDto> DTO = Telescope.mapperForward(
    UserEntity.class,
    UserDto.class,
    toOneWay(UserEntity::expiresAt, UserDto::expiresAt, DateHelpers::expiry),
    toOneWay(UserEntity::createdAt, UserDto::createdAt, DateHelpers::createdAt)
  );
}
```

The ambiguity qualifiers solve doesn't arise at runtime: each row names its converter/mapper by reference, resolved by
javac — safer than @Named string matching. One corner: the DI TelescopeMapperRegistry allows one `Mapper` bean per (src,
tgt) pair, qualified or not. A second mapper for the same pair has to be wrapped in your own type or built where it's
used. On Spring, a bean declared with `@Bean(defaultCandidate = false)` also stays out of the registry, and on Quarkus
so does a `@DefaultBean` one while another `Mapper` bean exists.

**Generated path:** a `@Bridge` field names its helper methods the same way, as in the second example, and the generated
bridge calls each one directly or through a small generated class; a `toOneWay` row is forward-only (same asymmetry as a
single MapStruct qualified method), and `to(src, tgt, Helper::format, Helper::parse)` names both directions. The per-row
mapper value of the first example is a `via` row naming another `@Bridge` field or a class with static `forward` and
`backward` methods; a runtime `Mapper` value is refused on the row.

<sub>Evidence: codegen/src/main/java/io/github/eschizoid/telescope/codegen/FieldRows.java (a converter a `@Bridge` field
names by method reference is called from a small generated class, and `via` names what a `via` row may name);
core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java, `to(Accessor, Accessor, Function, Function)`
(exact function per row) and `via(Accessor, Accessor, Mapper)` (an exact Mapper instance);
spring-boot-starter/src/main/java/io/github/eschizoid/telescope/spring/TelescopeMapperRegistry.java, the constructor and
the 'Construction' section of the class javadoc (duplicate (src,tgt) pairs throw with a message naming what keeps a
second mapper out of the registry)</sub>

### Mapper composition

**Runtime: ✅ full** · **Generated: ✅ full** · **MapStruct:** @Mapper(uses = OtherMapper.class) — delegating nested
type conversions to other mappers or static helper methods

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
// Codegen: point a property at the nested pair's own @Bridge field, or at a class written by hand:
public record Order(String id, Address address, List<Line> lines) {
  @Bridge
  static final Mapper<Order, OrderEntity> ENTITY = Telescope.mapper(
    Order.class,
    OrderEntity.class,
    via(Order::address, OrderEntity::address, Address.ENTITY),
    via(Order::lines, OrderEntity::lines, LineMapper.class)
  );
}
```

Two mechanisms and both directions of MapStruct's uses= are covered: via(src, tgt, mapper) delegates one field to a
pre-built Mapper (with automatic List/Set/Optional/Map element-lifting — MapStruct requires an Iterable method for
that), and auto-recursion means the common uses= case (nested type pairs) needs no declaration at all. Difference in
idiom: telescope binds the delegate per field explicitly, MapStruct auto-selects by type from the uses list —
telescope's form is more verbose when many fields share one nested pair but unambiguous.

**Generated path:** a `@Bridge` field mirrors it with the same `via` row, naming the nested pair's own `@Bridge` field,
whose generated bridge converts the property, or a class with static `forward` and `backward` methods; a converting `to`
row names any other static helper method. The common case needs no row here either: a nested pair the outer bridge meets
converts through that pair's own `@Bridge` field when it has one, which is MapStruct's selection by type, and through a
bridge derived for it otherwise. A `via` row naming a runtime `Mapper` value is refused on the row.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java, `via(Accessor, Accessor, Mapper)`
(its javadoc documents the element-level auto-lift through List/Set/Optional/Map);
core/src/main/java/io/github/eschizoid/telescope/mapping/Via.java (row record);
codegen/src/main/java/io/github/eschizoid/telescope/codegen/FieldRows.java (a `via` row on a `@Bridge` field names
another `@Bridge` field, a class written by hand, or that class's static `forward` and `backward`);
core/src/test/java/io/github/eschizoid/telescope/DeepMappingTest.java, the test 'via(...) takes precedence over
auto-recursion for its target field'; core/src/test/java/io/github/eschizoid/telescope/CycleHandlingTest.java, the
self-reference tests (auto-recursion builds nested sub-mappers with no delegation rows at all);
codegen/src/main/java/io/github/eschizoid/telescope/codegen/BridgeProcessor.java, `bridgeNameOf` (a nested pair a field
declares converts through that field's bridge);
codegen/src/test/java/io/github/eschizoid/telescope/codegen/CrossPathCorpusTest.java, the test 'a field mapped through a
bridge the same compilation generates converts the same way on both paths';
codegen/src/test/java/io/github/eschizoid/telescope/codegen/BridgeFieldTest.java, the tests 'a via row naming a class
converts the same on both paths' and 'a row the generated bridge cannot express is refused by name' (a `via` row naming
a runtime mapper)</sub>

## Object creation & update

### In-place update methods

**Runtime: ✅ full** · **Generated: ✅ full** · **MapStruct:** void update(@MappingTarget Entity target, Dto source) —
mutate an existing bean instead of constructing a fresh one

**telescope:**

```java
final Mapper<OrderDto, OrderEntity> mapper = Telescope.mapper(OrderDto.class, OrderEntity.class, to(OrderDto::sku, OrderEntity::getSku));
final OrderEntity managed = repository.findById(id).orElseThrow();
mapper.into(managed, dto);        // writes dto's mapped fields onto `managed` via setters, same reference back
repository.save(managed);
// sparse sibling: only non-null fields of a partial target overlay the base
final OrderDto updated = mapper.patch(dto, entityPatch); // patch(base A, partial B) -> A

// Codegen (@Bridge field) form — the same rows; into is generated with the bridge:
public final class OrderMappers {
  @Bridge
  public static final Mapper<OrderDto, OrderEntity> ORDER = Telescope.mapper(OrderDto.class, OrderEntity.class, to(OrderDto::sku, OrderEntity::getSku));
}
OrderMappers.ORDER.into(managed, dto); // one call to OrderDtoToOrderEntityBridge.into: plain setter calls, no reflection
```

Deliberate parity feature — the javadoc names @MappingTarget explicitly and the test class pins JPA load-mutate-save
semantics (identity preserved, only mapped fields written, atomic staging). Bean targets only: records throw
UnsupportedOperationException naming the record (MapStruct can't mutate records in place either). A property the mapper
writes that has no public setter is refused where MapStruct's @MappingTarget skips it: when the mapper is built, for a
bean built through its setters, since forward would drop the value as well, and on the first into call, for a bean built
by a builder or a constructor, which forward writes through and into cannot. Bonus over MapStruct: patch() gives
null-skipping sparse update without NullValuePropertyMappingStrategy.IGNORE config.

**Generated path:** a strict `@Bridge` whose target is a bean with a setter for every property it writes gets a static
`into(target, source)`, and the mapper a `@Bridge` field declares calls it for a target of exactly that class; a
subclass, whose own setters may take a value differently, goes to the runtime `into`. `explain()` says so. It writes
what the runtime `into` writes: each property takes the value `forward(source)` gives it, read back through its getter,
every value read before the first setter runs, each written through the setter the runtime chooses from the property's
type, and a nested bean is replaced by the new object forward builds rather than updated in place. Every row the field
form compiles behaves in it as in the runtime `into`. Where the bridge cannot update the target itself (a record, a bean
without such a setter, a setter that takes the value only after a check of its class, a pair of sealed interfaces), it
has no `into`, so a direct call does not compile, and the field's mapper runs the runtime `into`, which refuses in the
same words or writes the same result. A generated `into` builds the new object `forward` builds and copies it across;
where `forward` inlines, as on a flat bean, the JIT can remove that object, and `IntoBenchmark` measures what is left.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/conversion/Mapper.java, `into(Object, Object)` (javadoc
says 'Closes MapStruct's @MappingTarget for the bean path'; two-phase staged writes, returns the same reference, and a
record target is rejected with UnsupportedOperationException) and `patch(Object, Object)` (sparse overlay);
core/src/test/java/io/github/eschizoid/telescope/MapperIntoTest.java, the class javadoc ('Pins Mapper.into(target,
source) — the @MappingTarget equivalent'), the tests 'into(managed, dto) mutates target via setters; reference identity
preserved' and 'Subsequent into(...) calls re-mutate the same instance', 'a paired property without a setter refuses the
mapper rather than being dropped by forward and into', and 'a builder-built target refuses on into by the property's
name, and has written nothing'; codegen/src/main/java/io/github/eschizoid/telescope/codegen/BridgeProcessor.java,
`intoStatements` and `emitInto` (the generated `into`); codegen/src/test/java/io/github/eschizoid/telescope/codegen/
CrossPathCorpusTest.java, the tests 'into updates every property the mapper writes through a setter, and refuses by name
one it has no setter for, on both paths', 'between two sealed interfaces, into writes the target's own case and refuses
another, on both paths' and 'a @Bridge field's mapper writes into the target through the generated bridge's own
into'</sub>

### Object factories

**Runtime: ⚠️ partial** · **Generated: ⚠️ partial** · **MapStruct:** @ObjectFactory — a user method that instantiates
the target (custom constructor args, EntityManager lookup, DI-provided instance) which the generated mapper then
populates

**telescope:**

```java
// 1. Pin HOW the engine constructs a target class (strategy, not arbitrary code):
Telescope.mapper(OrderRecord.class, OrderPojo.class,
    writeBean(OrderPojo.class, WriteStrategy.CONSTRUCTOR),  // or BUILDER / SETTERS
    to(OrderRecord::sku, OrderPojo::getSku));
// 2. Fabricate/load the instance yourself, then let the mapper populate it in place:
mapper.into(entityManager.find(OrderEntity.class, dto.id()), dto);
// 3. Or take over the whole conversion with hand-written functions:
Telescope.from(OrderDto.class).to(OrderEntity.class)
    .using(d -> OrderEntityFactory.create(d), e -> new OrderDto(e.getSku()));
```

No hook to inject an arbitrary user function as the instantiator inside the deep-mapping engine — writeBean() selects
among three reflection strategies (BUILDER/SETTERS/CONSTRUCTOR), it cannot call your code. The two @ObjectFactory
motivations are each covered by a different idiom: 'construct differently' → writeBean strategy hint; 'populate an
instance I obtained elsewhere (JPA/DI)' → mapper.into(existing, source). A truly custom factory (e.g. constructor
needing values not on the source) forces you out to from/to/using where you hand-write the entire forward function,
losing auto-mapping for the remaining fields.

**Generated path:** partial for the same reasons. A `writeBean` hint keyed to the pair's own source or target, or
`@Bridge(writeStrategy = ...)`, picks the strategy the generated rebuild calls, and `mapper.into(existing, source)` on
the field's mapper populates an instance obtained elsewhere through the generated `into`. Neither path has a hook for a
user instantiator; a nested pair built differently goes on its own `@Bridge` field, since a hint keyed to a nested class
is refused.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/mapping/WriteHint.java, `writeBean` / `writeBeans`
(accept only the three-value `WriteHint.WriteStrategy` enum, BUILDER/SETTERS/CONSTRUCTOR — no Supplier/factory function
overload); core/src/main/java/io/github/eschizoid/telescope/conversion/MapperBuilder.java,
`create`/`inherit`/`add`/`build` (no factory hook); core/src/main/java/io/github/eschizoid/telescope/Telescope.java,
`from(Class)` (the from/to/using escape hatch); core/src/main/java/io/github/eschizoid/telescope/conversion/Mapper.java,
`into(Object, Object)` (covers the load-existing-instance use case); no ObjectFactory counterpart exists anywhere in the
main source sets; codegen/src/test/java/io/github/eschizoid/telescope/codegen/WriteHintRowTest.java, the tests 'a write
hint on a @Bridge field rebuilds the side it names as the runtime path does' and 'a writeBean keyed to a class other
than the pair's sides is refused by name';
codegen/src/test/java/io/github/eschizoid/telescope/codegen/CrossPathCorpusTest.java, the test 'a @Bridge field's mapper
writes into the target through the generated bridge's own into'</sub>

### Builder support

**Runtime: ⚠️ partial** · **Generated: ⚠️ partial** · **MapStruct:** Auto-detected builder(): Immutables, Lombok
@Builder, protobuf builders; customizable via BuilderProvider SPI and @Builder(builderMethod=...)

**telescope:**

```java
// Auto-detected: any target with a static builder() whose builder has build() —
// probed automatically, Lombok @Builder just works:
final Mapper<UserDto, UserPojo> m = Telescope.mapper(UserDto.class, UserPojo.class,
    to(UserDto::email, UserPojo::getEmail));
// The builder wins by default over a constructor or setters; pin SETTERS to write through them instead:
Telescope.mapper(UserDto.class, UserPojo.class,
    writeBean(UserPojo.class, WriteStrategy.SETTERS));
// Codegen navigators for Lombok @Builder classes ship in telescope-lombok:
// @Builder class BuilderUser → generated BuilderUserTelescope with builder() rebuild
```

Covers Lombok @Builder (dedicated telescope-lombok module with integration tests) and any conventional static
builder()/build() pair, including Immutables if you map to the generated ImmutableFoo class directly (its static
builder() matches). Gaps vs MapStruct: (1) the factory method name is hard-wired to 'builder' — protobuf's newBuilder()
is NOT detected and there is no @Builder(builderMethod=...) equivalent or BuilderProvider SPI to teach it; (2) a target
with a builder is built through it by default, on both the runtime and the codegen path, and preferring its setters or
constructor takes a writeBean hint (or `@Bridge(writeStrategy = ...)`). Both paths pass the builder over on their own
only when it has a member that cannot take a property's type, or has no member for a property the next strategy would
write (with no other strategy available, any property stored in a field). Builder setter matching (exact / setX / withX)
covers Lombok, Immutables-fluent, and JavaBean-style builders.

**Generated path:** partial for the same reasons, as the paragraph above says of both paths: the processors take the
same `BeanWriteStrategy.AUTO_ORDER`, call the builder directly, and detect the same `builder()` name only.

<sub>Evidence: internal/src/main/java/io/github/eschizoid/telescope/internal/pairing/BeanWriteStrategy.java,
`AUTO_ORDER` (static builder() → name-matched all-args ctor → setters, the order shared by the runtime writer and the
processors) and `auto` (when the builder is passed over);
internal/src/main/java/io/github/eschizoid/telescope/internal/Beans.java — `computeAutoWriter` (the runtime's answers to
that shape), the `builderWriter` factory, and `BuilderWriter` (requires a static method named exactly 'builder()'
returning a type with 'build()'; setter matching by exact name / setX / withX; LMF-de-reflected dispatch);
core/src/main/java/io/github/eschizoid/telescope/mapping/WriteHint.java — the `WriteStrategy` javadoc (BUILDER strategy,
'requires a static builder() method');
lombok/src/test/java/io/github/eschizoid/telescope/codegen/lombok/fixtures/BuilderUser.java (@Builder fixture) and
LombokFocusProcessorTest.java (@Builder and @Value+@Builder navigators verified end-to-end);
core/src/test/java/io/github/eschizoid/telescope/DeepMappingTest.java — the `WriteHints` nested class (writeBean
BUILDER/SETTERS/CONSTRUCTOR hint tests);
codegen/src/test/java/io/github/eschizoid/telescope/codegen/BeanRebuildPrecedenceTest.java and
BeanRebuildCorpusTest.java (the generated rebuild takes the runtime's strategy)</sub>

### Records and constructor mapping

**Runtime: ⚠️ partial** · **Generated: ⚠️ partial** · **MapStruct:** Constructor-based target population for records and
immutable classes (parameter-name matching, @Default constructor selection)

**telescope:**

```java
record UserEntity(String name, String email, AddressEntity address) {}
record UserDto(String fullName, String email, AddressDto address) {}
// records are rebuilt via the canonical constructor, nested records recursed automatically:
final Mapper<UserEntity, UserDto> m = Telescope.mapper(
    UserEntity.class, UserDto.class,
    to(UserEntity::name, UserDto::fullName));   // same-name fields auto-mapped
// immutable all-args-only POJO target; the hint binds by parameter name when -parameters or -g
// keeps the names and every one names a property, by position when none does, and refuses a
// constructor only some of whose names are properties:
Telescope.mapper(OrderRecord.class, ImmutablePojo.class,
    writeBean(ImmutablePojo.class, WriteStrategy.CONSTRUCTOR));
```

Records are the native happy path — canonical-constructor rebuild is the default everywhere (deep mapping, patch, @Focus
codegen), no configuration needed. Immutable non-record classes work via the CONSTRUCTOR write strategy: auto-detected
when there is exactly one public all-args constructor whose parameter names are known (from -parameters, or from the
local variable table -g keeps) and align with getter-derived properties; otherwise pin with writeBean(X.class,
CONSTRUCTOR). No @Default-style disambiguation among multiple constructors — a class with more than one constructor of
the property-count arity is never auto-selected for the constructor strategy (`Beans`' `solePublicConstructor`) and is
refused when nothing else applies, but the writeBean hint plus the single-ctor rule covers the practical cases. Verified
limitation: no counterpart to MapStruct's @Default — a target with multiple same-arity constructors cannot be
disambiguated (records: full; single-ctor immutables: full with -parameters or -g; multi-constructor immutables:
unsupported).

**Generated path:** partial for the same reason. The bridge rebuilds a record through its canonical constructor and an
immutable class through the constructor the runtime chooses, by a direct `new` call. It cannot call a constructor the
generated class cannot reach, so a package-private or protected constructor in another package, and a private no-arg
constructor of an object a path builds through its setters, are refused by name where the runtime builds them; these are
the registered limits of the generated path.

<sub>Evidence: internal/src/main/java/io/github/eschizoid/telescope/internal/Records.java, `RecordInfo` and its
`buildCtorFn` (cached canonical-constructor invoker per record class, components in canonical order, MethodHandle
asSpreader); core/src/main/java/io/github/eschizoid/telescope/conversion/Mapper.java, the `patch` javadoc ('Records:
rebuilds via the canonical constructor'); internal/src/main/java/io/github/eschizoid/telescope/internal/Beans.java,
`ConstructorWriter` (name-matched when the names are known, positional fallback) and `computeAutoWriter` (the
constructor is autoWriter's second rung, after a builder and before setters, guarded by known parameter names and a
name-alignment check, with a loud error otherwise);
core/src/main/java/io/github/eschizoid/telescope/mapping/WriteHint.java, the `WriteStrategy` enum (CONSTRUCTOR strategy
docs); core/src/test/java/io/github/eschizoid/telescope/DeepMappingTest.java, the record↔record fixtures at the top of
the class, the test 'backward(forward(entity)) equals entity for a 5-level mapping' (nested record mapper), and the
`WriteHints` tests 'CONSTRUCTOR hint constructs an immutable all-args-only POJO round-trip', 'autoWriter constructor
fallback handles a same-arity unambiguous immutable POJO without a hint', and 'autoWriter throws cleanly for ambiguous
multi-ctor POJO when no hint is supplied';
codegen/src/test/java/io/github/eschizoid/telescope/codegen/CrossPathCorpusTest.java, the tests 'a pair out of the
generated path's reach is a registered limit of it, which the runtime passes' (the `GENERATED_PATH_LIMITS` register) and
'a target reachable only through a narrower-than-public member is built the same way on both paths';
docs/adr/0019-runtime-and-generated-path-agreement.md</sub>

## Policies & null handling

### Unmapped-target policy

**Runtime: ✅ full** · **Generated: ✅ full** · **MapStruct:** unmappedTargetPolicy = ERROR/WARN/IGNORE at @Mapper or
@MapperConfig level

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

**Generated path:** a strict field refuses an unmatched property at compile time, in the words `Telescope.mapper` uses
when it is built, and a `mapperForward` field is lenient. A `drop(...)` row compiles; the form keyed to a nested pair,
`drop(src, Class)`, is refused with "drop(...) keyed to a nested pair has no generated form yet", and goes on the nested
pair's own `@Bridge` field. `-Atelescope.verify` does not relax a strict bridge: its completeness check is the bridge's
own, so the IGNORE analog is a `mapperForward` field.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/DeepMap.java, `resolveMapper` (strict bijection —
'unmatched fields on EITHER side throw at construction') and `resolveForward` (mapperForward lenient, 'Matches
MapStruct's default behaviour'); core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java, `drop(Accessor)`
and `drop(Accessor, Class)` ('declare it intentionally NOT mapped');
codegen/src/main/java/io/github/eschizoid/telescope/codegen/MapperVerifierProcessor.java, the class javadoc
('-Atelescope.verify=error|warn|off (default error)', '@UncheckedMapping("reason")'), `init` (mode ->
Diagnostic.Kind.ERROR/WARNING/off), and `verifyPairCall` (unmatchedTargets/unmatchedSources reported);
docs/adr/0012-compile-time-mapper-verification.md;
codegen/src/test/java/io/github/eschizoid/telescope/codegen/CrossPathCorpusTest.java, the test 'a strict mapper refuses
a property nothing pairs with whatever other rows it has, in the same words on both paths';
codegen/src/main/java/io/github/eschizoid/telescope/codegen/FieldRows.java, `drop` (the form keyed to a nested pair
refused)</sub>

### Null value strategies

**Runtime: ⚠️ partial** · **Generated: ⚠️ partial** · **MapStruct:** nullValueMappingStrategy /
nullValuePropertyMappingStrategy (RETURN_NULL vs RETURN_DEFAULT, SET_TO_NULL vs IGNORE on update)

**telescope:**

```java
import static io.github.eschizoid.telescope.mapping.Mapping.toOrElse;
import static io.github.eschizoid.telescope.mapping.NullHint.NullStrategy.DEFAULT;
import static io.github.eschizoid.telescope.mapping.NullHint.nullSourceValues;

// SET_TO_DEFAULT analog — per-mapper hint: a null auto-matched or same-typed to(src, tgt) field becomes
// "" / 0 / List.of() / Optional.empty(); a row carrying its own functions receives the null unchanged
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
record/bean/enum/custom-typed fields stay null unless a per-row toOrElse supplies a value, and a row carrying its own
conversion functions is not wrapped at all; (4) DEFAULT is forward-only (documented; backward preserves nulls).

**Generated path:** partial, with one gap more. `nullSourceValues(...)` is refused on a `@Bridge` field, as a hint the
bridge does not compile, so SET_TO_DEFAULT is a strict-null `toOrElse` row per field there. The rest holds as on the
runtime path: a null property propagates, `forward(null)` returns null, and the generated `patch` overlays only the
non-null properties of the partial target.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/mapping/NullHint.java, `nullSourceValues` and the
`NullStrategy` enum (PROPAGATE='SET_TO_NULL', DEFAULT='SET_TO_DEFAULT'; the interface javadoc's opening sentence names
the MapStruct attributes); internal/src/main/java/io/github/eschizoid/telescope/internal/NullDefaults.java, the class
javadoc and `defaultFor` (per-type default table, explicitly 'covers the surface MapStruct's RETURN_DEFAULT populates');
core/src/main/java/io/github/eschizoid/telescope/DeepMap.java, `autoIso` and `wrapDefaultOnNull` (DEFAULT wraps every
auto-recursed per-component Iso via coalesceForward);
core/src/main/java/io/github/eschizoid/telescope/conversion/Mapper.java, `forward` (forward(null) returns null =
RETURN_NULL) and `patch` ('overlay the non-null fields of partial ... leaving the rest of base untouched');
core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java, the `toOrElse` / `toOrElseGet` overloads
(per-field defaults); core/src/test/java/io/github/eschizoid/telescope/NullStrategyTest.java, every nested class
(PROPAGATE default, DEFAULT substitution for String/wrappers/BigDecimal/collections, bean targets, backward-unchanged);
codegen/src/main/java/io/github/eschizoid/telescope/codegen/FieldRows.java, `Rows.read` (a call that is neither a
`Mapping` row nor a `WriteHint` hint, `nullSourceValues` among them, is refused as not a row a `@Bridge` mapper
compiles); codegen/src/test/java/io/github/eschizoid/telescope/codegen/BridgeFieldTest.java, the test 'a field's mapper
is the class generated for it, each direction one call into the bridge' (`forward(null)` and `patch` on the field's
mapper); codegen/src/test/java/io/github/eschizoid/telescope/codegen/CrossPathCorpusTest.java, the test 'a patch whose
partial changes nothing returns a new object on both paths'</sub>

### Null check strategy

**Runtime: ⚠️ partial** · **Generated: ⚠️ partial** · **MapStruct:** nullValueCheckStrategy = ALWAYS — guard every
property read before conversion/setter

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
    nullSourceValues(DEFAULT));  // auto-matched fields and same-typed to(...) rows only, never a row's own fns
```

Telescope's default posture is effectively ALWAYS for everything the engine owns: null sources, null nested objects, and
null containers propagate as null instead of NPE-ing, with no configuration. The gap is custom transform rows — the
user-supplied fwd/bwd functions in to(src, tgt, fwd, bwd) / toOneWay ARE invoked with null under either strategy, and a
via(...) row hands the null to its Mapper, which returns null. nullSourceValues(DEFAULT) wraps only auto-matched fields
and same-typed to(src, tgt) rows, so a row carrying its own functions still receives the null and its result lands on
the target, even when the target type has a table default. There is no per-mapper 'never call my conversion with null'
switch; the recipe is toOrElse for the common cases or a null check inside the function.

**Generated path:** partial in the same place. Each bridge the generated code derives for a nested pair opens with its
own null guard, a path reads a null object on the way as the leaf's null value, and containers pass null through. A
converting row's method is called with the property's value, null included, as the runtime calls the row's function; a
strict-null `toOrElse` compiles into `v == null ? fallback : v`, and `nullSourceValues` is refused on the field.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/DeepMap.java, `arrayLeaf` (its null guard:
`if (s == null) return null` and `if (t == null) return null`) and `lazyCacheIso` (the null-guarding proxy around a
nested pair), internal/src/main/java/io/github/eschizoid/telescope/internal/MhIso.java, `pair` (the composed-handle leaf
guards null the same way) — every auto record/bean pair guarded;
core/src/main/java/io/github/eschizoid/telescope/ContainerLifts.java, the `null` guards in the container copy and lift
isos; core/src/main/java/io/github/eschizoid/telescope/TelescopeFixups.java, `srcT.find(s).orElse(null)` (a
telescope-to-telescope row reads leniently through a null intermediate);
core/src/main/java/io/github/eschizoid/telescope/DeepMap.java, `autoIso` (the only place `wrapDefaultOnNull` is applied)
and the row branch of `populateIso` (same-typed rows go through `autoIso`, rows carrying functions through `fieldIsoOf`,
which does not wrap); internal/src/main/java/io/github/eschizoid/telescope/internal/optics/Iso.java, `coalesceForward`
(`x == null ? defaultValue : inner.to(x)` — inner fn skipped on null) and `liftList` (null pass-through);
core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java, the predicate-gated `toOrElse` and `toOrElseGet`
(null-short-circuit before user predicates fire);
codegen/src/main/java/io/github/eschizoid/telescope/codegen/BridgeProcessor.java, `applyForward` (the `TRANSFORM` branch
calls the converter on the value it reads); codegen/src/main/java/io/github/eschizoid/telescope/codegen/FieldRows.java,
`orElse` (the strict-null default)</sub>

### Conditional mapping

**Runtime: ⚠️ partial** · **Generated: ⚠️ partial** · **MapStruct:** @Condition presence-check methods (e.g. only map
non-blank strings)

**telescope:**

```java
import static io.github.eschizoid.telescope.mapping.Mapping.*;

Telescope.mapperForward(Order.class, OrderDto.class, // one-way, as the gated rows below are
    // per-field value predicate — 'only map non-blank strings', else default:
    toOrElse(Order::promoCode, OrderDto::promoCode, "NONE", String::isBlank),
    toOrElseGet(Order::traceId, OrderDto::traceId, () -> UUID.randomUUID().toString(), String::isBlank),
    // whole-source predicate gating on deep writes / stamping rows:
    when(order -> order.shipping() != null,
        to(Telescope.path(Order::shipping, Shipping::country), OrderDto::shipCountry)),
    when(order -> order.priority() == Priority.HIGH,
        constant(OrderDto::expediteFlag, true)));
```

Both @Condition shapes have typed, tested analogs: per-field value predicates (toOrElse/toOrElseGet 4-arg, which
additionally null-short-circuit before the predicate) and whole-source predicates (when(...)), both forward-only like
@Condition. What's missing is MapStruct's blanket application: one @Condition method automatically gates EVERY property
of the matching type across the mapper, while telescope requires one row per gated field — real repetition on wide DTOs.
Also: when(...) wraps only telescope-based rows (plain to(srcAcc, tgtAcc)/via/drop rejected at construction with a
pointer to toOrElse), when(...) is keyed to the type pair its inner row is keyed to, so its predicate receives the
source converted at that pair, and there is no conditional analog on the patch/update path.

**Generated path:** partial, with the runtime's limits and one of its own. Both shapes compile when each predicate is a
method reference or a `static final` field holding one; an inline lambda, as in the example above, is refused on the row
by name. `when(...)` gates a source path, a target path, a path on each side, a constant and a supplied or computed
value, and refuses a nested `when` and every other inner row in the runtime's words. The bridge tests the predicate
through a `Predicate`, as the runtime does, writes the gated row where it accepts the source and leaves the property to
the rest of the rows and the name pairing where it does not, reports a predicate that throws in the runtime's words, and
leaves the row out of `backward` and `patch`. The generated path's own limit: where a gated constant or value fills a
property no source property of its name fills, the runtime starts that property as a default-tree instance of its type,
and the generated bridge builds that instance only when it is made of public records declaring no type parameters,
primitives, platform classes and classes no write strategy allocates, each of which it fills with null or the
primitive's default, as the runtime does. Any other default tree is refused by name, though the runtime builds it: a
record that is not public or declares type parameters, and a bean with a public no-argument constructor or a public
static `builder()`, whether it is the property's type or a component of a record below it.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java, `when(Predicate, Mapping)` and its
javadoc ('closes MapStruct's @Condition for whole-source predicate gating', forward-only, nesting rejected), the
predicate-gated `toOrElse` (the `missing` predicate — 'Generalises ... to cover empty-string, empty-collection ...
MapStruct has no equivalent'), and the predicate-gated `toOrElseGet`;
core/src/main/java/io/github/eschizoid/telescope/mapping/Conditional.java, the compact constructor (construction-time
rejection of field-iso and nested inners with alternative hints) and the 'Predicate purity AND thread-safety' paragraph
(predicate evaluated once per top-level forward call);
core/src/test/java/io/github/eschizoid/telescope/MappingWhenTest.java, the nested classes for gated flat-to-nested,
constant, compute, nested-to-flat, nested-to-nested and zip rows, and 'Construction-time rejection — invalid inner
rows'; core/src/test/java/io/github/eschizoid/telescope/MappingOrElseTest.java;
codegen/src/main/java/io/github/eschizoid/telescope/codegen/FieldRows.java, `gated` (the inner rows a `when` compiles,
and the runtime's refusals of the rest) and `orElse` (the predicate-gated forms);
codegen/src/test/java/io/github/eschizoid/telescope/codegen/CrossPathCorpusTest.java, the tests 'a when(...) row and a
toOrElse row with a predicate convert the same way on both paths, in each direction' and 'a when(...) predicate that
throws fails forward in the same words on both paths';
codegen/src/test/java/io/github/eschizoid/telescope/codegen/BridgeFieldTest.java, the tests 'a row the generated bridge
cannot express is refused by name' (the `when` cases), 'a field with when(...) rows and a predicate toOrElse runs its
generated bridge, giving what the runtime gives' and 'a when(...) row filling a bean no source property fills is refused
by name, since its default is a bean'</sub>

## Advanced

### Config inheritance

**Runtime: ⚠️ partial** · **Generated: ⚠️ partial** · **MapStruct:** @InheritConfiguration reuses a base @Mapping set on
a sibling method; @InheritInverseConfiguration derives the reverse method from the forward one.

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
derives from the same rows). Remaining limitation: rows bind to their decoded type pair, so a group cannot be reused
across DTO variants of different pairs — but a group inherited into a foreign pair now fails fast at build with an error
naming the unreachable pair and its rows, instead of being silently dropped. Declare a per-variant group typed against
the variant.

**Generated path:** partial, covering only the inverse half: a strict `@Bridge` field generates `backward` from the same
rows. A row group cannot be shared, because the processor reads rows from the field's own factory call: a row held in a
variable or an array, such as `AUDIT_COLUMNS`, is refused with "a @Bridge mapper compiles only rows written in place as
a Mapping factory call", and `mapperBuilder(...)` is not an initializer a `@Bridge` field takes. Each field repeats the
shared rows in place.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/conversion/MapperBuilder.java, the class javadoc
("Closes MapStruct's @InheritConfiguration", and 'Row groups bind to their type pair'), `inherit`, and `build`
(delegates to Telescope.mapper); core/src/main/java/io/github/eschizoid/telescope/Telescope.java, `mapperBuilder`;
core/src/test/java/io/github/eschizoid/telescope/MapperBuilderTest.java, the tests
'inherit(AUDIT_COLUMNS).add(constant(...)) — both row groups apply' and 'multiple inherit groups compose; same builder
feeds multiple distinct mappers' (the same AUDIT_COLUMNS group feeds two mappers of one pair), and the nested class
'Cross-pair inherit fails fast — rows bound to a foreign type pair cannot be silently dropped';
core/src/main/java/io/github/eschizoid/telescope/conversion/Mapper.java, `forward` and `backward` (both directions from
the same Iso); README.md, the "Mapping" example (`dtoMapper.backward(dto); // the same row list, run in reverse`) and
the 'Bidirectional mapping' row of "Capability comparison";
codegen/src/main/java/io/github/eschizoid/telescope/codegen/FieldRows.java, `Rows.read` (a row that is not a factory
call written in place refused) and `read` (a field's initializer is a `Telescope.mapper` or `mapperForward` call);
codegen/src/test/java/io/github/eschizoid/telescope/codegen/BridgeFieldTest.java, the test 'a row the generated bridge
cannot express is refused by name' (the `Rows.ID` case, a row held in a field)</sub>

### Decorators

**Runtime: ✅ full** · **Generated: ✅ full** · **MapStruct:** @DecoratedWith(CustomDecorator.class) — an abstract
decorator class wraps the generated mapper, overriding selected methods and delegating to the injected original.

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

**Generated path:** the same: a `@Bridge` field's mapper is a value, so hooks chained onto it in another field, as in
[Before/after hooks](#beforeafter-hooks), and a function composed over `Mappers.DTO::forward` decorate the generated
bridge in the same way.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/conversion/Mapper.java, `beforeForward` (javadoc cites
@BeforeMapping), `afterForward(Function)` (javadoc cites @AfterMapping), `afterForward(BiFunction)` (source-aware;
javadoc cites @AfterMapping with @MappingTarget), `beforeBackward`, and `afterBackward`; `asTelescope` (carries the hook
chain) and `toForwardMapper` (carries hooks); `Mapper.create(Function, Function, Class, Class, Map)` for wholesale
wrapping (its six-argument sibling is marked module-internal);
codegen/src/test/java/io/github/eschizoid/telescope/codegen/BridgeFieldTest.java, the test 'a field's mapper is the
class generated for it, each direction one call into the bridge' (a hook on the field's mapper)</sub>

### DI component models

**Runtime: ✅ full** · **Generated: ✅ full** · **MapStruct:** componentModel = "spring" / "cdi" / "jakarta" annotates
the generated mapper impl so it is discovered as an injectable bean.

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
(sourceClass, targetClass)-indexed TelescopeMapperRegistry MapStruct has no analog for (duplicate pairs fail when the
registry is built; telescope.registry.fail-fast decides whether a lookup for an unregistered pair throws or returns
null). Caveat: there is no dedicated plain-Jakarta-EE/jsr330 module — the Quarkus module's auto-collection uses ArC's
@All — but a Mapper is an ordinary object producible from a standard CDI @Produces method in any container.

**Generated path:** a `@Bridge` field's mapper is a `Mapper` value too, so
`@Bean Mapper<UserEntity, UserDto> userMapper() { return UserMappers.DTO; }` registers the generated mapper, in the
registry included. A `@TelescopeMapper` interface is implemented by a generated Spring `@Component`, or by an
`@ApplicationScoped` CDI bean under Quarkus, that calls the source's generated bridge when one exists.

<sub>Evidence: spring-boot-starter/src/main/java/io/github/eschizoid/telescope/spring/TelescopeAutoConfiguration.java,
`telescopeMapperRegistry` (@AutoConfiguration builds TelescopeMapperRegistry from every Mapper bean; the class javadoc
says "declare @Bean `Mapper<A, B>` and it shows up in the registry");
spring-boot-starter/src/main/java/io/github/eschizoid/telescope/spring/TelescopeMapperRegistry.java and
quarkus/src/main/java/io/github/eschizoid/telescope/quarkus/TelescopeMapperRegistry.java, the constructor (duplicate
pairs throw) and `get` (fail-fast on a missing pair);
quarkus/src/main/java/io/github/eschizoid/telescope/quarkus/TelescopeProducer.java (@ApplicationScoped producer, ArC
@All `List<Mapper<?, ?>>` collector); README.md, the `telescope-spring-boot-starter` and `telescope-quarkus` rows of
"Published artifacts"; codegen/src/main/java/io/github/eschizoid/telescope/codegen/TelescopeMapperProcessor.java (the
generated Spring component, which reads the bridge a carrier class or a `@Bridge` field declares);
spring-boot-starter/src/test/java/io/github/eschizoid/telescope/spring/TelescopeSpringProcessorTest.java, the test 'a
mapper and a projection written a round after a carrier find the carrier's bridge'</sub>

### Enum mapping

**Runtime: ⚠️ partial** · **Generated: ⚠️ partial** · **MapStruct:** @ValueMapping(source = "X", target = "Y")
per-constant renames with ANY_REMAINING/ANY_UNMAPPED defaults; @EnumMapping name-transformation strategies
(prefix/suffix/case).

**telescope:**

```java
// Same-name constants — no row on either path. Each constant converts to the constant of the same name:
Telescope.mapper(UserEntity.class, UserDto.class);   // runtime
@Bridge(UserDto.class) public record UserEntity(String id, EntityStatus status) {} // generated: one switch per direction

// The same correspondence as a row, for a field that is also renamed:
enumTo(UserEntity::status, UserDto::state, EntityStatus.class, DtoStatus.class)

// Renamed constants / ANY_REMAINING — a typed switch instead of @ValueMapping strings:
to(UserEntity::status, UserDto::status,
   s -> switch (s) { case ARCHIVED -> DtoStatus.CLOSED; default -> DtoStatus.valueOf(s.name()); },
   d -> switch (d) { case CLOSED -> EntityStatus.ARCHIVED; default -> EntityStatus.valueOf(d.name()); })
```

The by-name case is fully covered on both paths, as a field, inside `List`, `Set`, `Map` values and `Optional`, and
across `Optional<->nullable`; a null constant stays null. One rule decides it for the runtime mapper, `@Bridge` and the
mapper verifier: a mapper that converts in both directions needs a counterpart for every constant on each side, and a
forward-only mapper (`mapperForward`, `@Bridge(lenient = true)`) for every source constant, so a target with extra
constants converts forward there. A lenient bridge's lossy backward sends a constant with no counterpart to null, and a
strict bridge applies the both-ways rule to every enum pair below a sub-bridge it shares with a lenient one, down to any
pair with a `@Bridge` of its own, which decides its own fields. A generated switch meeting a source constant added after
the bridge was generated throws an `IllegalArgumentException` naming it. An `EnumSet` of converted constants is refused
on every path, since no rebuild can build an `EnumSet`. A mismatch is refused when the mapper is built, and on the
generated path at compile time, naming the constants that have no counterpart, which is stronger than MapStruct's error
on unmapped source constants alone. What's missing is the declarative sugar for asymmetric enums: per-constant renames,
@EnumMapping prefix/suffix/case name transformations, and ANY_REMAINING/ANY_UNMAPPED defaults all require a hand-written
(though javac-exhaustive and refactor-safe) switch inside a to(src, tgt, fwd, bwd) row on the runtime path, or, on a
`@Bridge` field, the same row naming two static methods that hold the switch. Workaround quality is high, but it is
manual code, not a factory.

**Generated path:** partial for the same reason; the by-name case above holds on both paths. `enumTo` is refused on a
`@Bridge` field with "enumTo(...) has no generated form yet". It has no work to do there: the bridge plans a renamed
pair by its types as it plans a same-named one, so a plain `to(UserEntity::status, UserDto::state)` row converts the
constants by name.

<sub>Evidence: internal/src/main/java/io/github/eschizoid/telescope/internal/pairing/PairingRules.java, `decidePair`
(the enum-by-constant-name branch and its forward-only overload);
internal/src/main/java/io/github/eschizoid/telescope/internal/pairing/EnumCorrespondence.java, `converts`;
core/src/main/java/io/github/eschizoid/telescope/DeepMap.java, `enumByNameIso`;
codegen/src/main/java/io/github/eschizoid/telescope/codegen/BridgeProcessor.java, `planEnum` and `emitEnumDirection`;
codegen/src/test/java/io/github/eschizoid/telescope/codegen/CrossPathCorpusTest.java, the test 'an enum maps to an enum
by constant name on both paths, and both refuse the same mismatches';
codegen/src/test/java/io/github/eschizoid/telescope/codegen/MapperVerifierProcessorTest.java, the nested class 'Enum
pairs — constant names checked in each direction the mapper converts';
core/src/main/java/io/github/eschizoid/telescope/mapping/Mapping.java, `enumTo`;
core/src/test/java/io/github/eschizoid/telescope/MappingEnumToTest.java, the nested classes 'Happy path — enums with
identical constants line up by name' (round-trip) and 'Exhaustiveness validation at factory time' (mismatch
diagnostics + escape-hatch message); codegen/src/main/java/io/github/eschizoid/telescope/codegen/FieldRows.java,
`Rows.read` (`enumTo` falls to the refusal of a row with no generated form);
codegen/src/main/java/io/github/eschizoid/telescope/codegen/BridgeProcessor.java, `planFields` (a renamed pair planned
by its types)</sub>

### Subclass mapping

**Runtime: ⚠️ partial** · **Generated: ⚠️ partial** · **MapStruct:** @SubclassMapping(source = Sub.class, target =
SubDto.class) — polymorphic dispatch over an abstract/sealed source hierarchy, inheriting the parent method's mapping
config.

**telescope:**

```java
sealed interface Payment permits CreditCard, BankTransfer, Crypto {}

Function<Payment, PaymentDto> dispatch = Match.<Payment, PaymentDto>of(Payment.class)
  .when(CreditCard.class, creditCardMapper::forward)
  .when(BankTransfer.class, bankMapper::forward)
  .when(Crypto.class, cryptoMapper::forward)
  .exhaustive(); // throws at build time naming any uncovered permit; .partial() opts out

// codegen sibling: @Bridge on a sealed interface pair emits the same Match.of(...).when(...).exhaustive() dispatch
// over the permits, one arm per per-case bridge
```

For sealed hierarchies the coverage is real and in one way stronger — .exhaustive() names every uncovered permit, and
the @Bridge codegen path refuses at compile time a sealed-to-sealed pair whose source permits do not each carry a
@Bridge to a permit of the sealed target. The runtime mapper dispatches the same way:
`Telescope.mapper(Payment.class, PaymentDto.class)` pairs each permit through its own @Bridge, or through a
`cases(CreditCard.class, CardDto.class)` row where there is no codegen, and refuses an unpaired permit by name when it
is built. Two genuine gaps versus @SubclassMapping: (1) Match.of() rejects non-sealed roots, so plain abstract-class
hierarchies (common in legacy JPA models) have no dispatcher — closest workaround is a hand-rolled instanceof chain; (2)
per-permit handlers are independent mappers with no automatic inheritance of shared parent-field config (mitigable by
sharing a MapStep[] group via mapperBuilder().inherit(...), but that is manual). The project's own README names
@SubclassMapping over open hierarchies as a reason to pick MapStruct.

**Generated path:** partial, with the parent-field gap wider. A sealed pair dispatches over the source's permits, each
through the permit's own `@Bridge`, and an abstract root or an interface that is not sealed is refused at compile time,
naming the fix: seal it and give each permit its own `@Bridge`. A `cases(...)` row is refused on a `@Bridge` field as
not a row it compiles, so each case pairs through its own `@Bridge` only. A `@Bridge` field between two sealed
interfaces whose rows set how properties convert (a drop, a rename, a conversion, a constant or compute, a `via`, a
write strategy) is refused by name, where a runtime row keyed to the interfaces applies to every pair of their cases;
shared parent-field rows go on each case's own `@Bridge` field.

<sub>Evidence: core/src/main/java/io/github/eschizoid/telescope/conversion/Match.java, `of` (rejects non-sealed roots),
`when` (Prism.downcast-routed dispatch), `exhaustive()` (verifies coverage via Class.getPermittedSubclasses), and
`partial()`; core/src/test/java/io/github/eschizoid/telescope/MatchTest.java, the nested classes 'happy path — every
permit covered' and 'exhaustiveness check' (exhaustive dispatch + missing-permit diagnostics), and 'partial() escape
hatch — no exhaustiveness check'; codegen/src/main/java/io/github/eschizoid/telescope/codegen/BridgeProcessor.java,
`generateSealed` (the sealed-source bridge emits a Match dispatch over the permits and requires every permit
@Bridge-annotated to a permit of the sealed target);
codegen/src/test/java/io/github/eschizoid/telescope/codegen/BridgeProcessorTest.java, the nested class 'Sealed roots —
pattern-match dispatch over per-case bridges' (asserts the emitted `.when(...)` arms and `.exhaustive()`); README.md,
"When MapStruct is the right pick" (the `@SubclassMapping` bullet);
codegen/src/test/java/io/github/eschizoid/telescope/codegen/SealedInterfaceRowAgreementTest.java, the nested class 'A
row keyed to the two sealed interfaces applies to every pair of their cases' (the runtime) and the test 'a @Bridge field
on a sealed interface whose rows set how properties convert is refused by name' (the generated path);
codegen/src/test/java/io/github/eschizoid/telescope/codegen/CrossPathCorpusTest.java, the test 'two sealed interfaces
convert case by case to the same result on both paths, at the root and below it'</sub>
