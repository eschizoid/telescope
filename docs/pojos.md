# Working with POJOs

Deep-mapping and direct navigation for mutable bean classes — write strategies, `ofBean`, `@Bridge`, and the aliasing
rules that come with mutability. [← back to README](../README.md)

Telescope's deep-mapping factory handles any combination of records and POJOs through one entry point. The same
`Telescope.map(A.class, B.class, ...)` call covers record↔record, POJO↔POJO, and the cross-paradigm record↔POJO mix at
any depth — the engine picks per side whether to drive the canonical constructor (records) or `Beans.autoWriter` (POJOs)
at every type pair the recursion encounters. The alternative is to navigate the POJO directly with
`Telescope.ofBean(...)`. Either way updates are immutable.

**Aliasing — beans aren't records.** An update rebuilds the _spine_ (the path to the changed field) with fresh objects
and shares references to untouched subtrees. With records that's always safe; with mutable POJOs the new and old object
share the same off-path sub-POJO instances, so mutating a shared sub-object afterward shows through both. Treat the
shared parts as effectively immutable.

## Convert — `Telescope.map` / `Telescope.mapper`

The same factory described under [Type conversion](type-conversion.md) handles POJO↔POJO and cross-paradigm record↔POJO
pairs without ceremony — components match by name on either side (`Pojo::getX` / `RecordOrPojo::x` normalized to `x`),
nested POJOs recurse, and container hops auto-lift. The POJO mechanics this section covers are the bean-construction
lever (`writeBean` / `writeBeans`) for when the auto-detect ladder can't pick a strategy.

```java
import static io.github.eschizoid.telescope.mapping.Mapping.SETTERS;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.writeBeans;

class LegacyUser {
  /* getId(), getEmail(), getName() + no-arg ctor + setters */
}

record UserRecord(String id, String email, String name) {}

// Same-name 1-liner — every getter/component lines up by normalized name.
final Telescope<LegacyUser, UserRecord> conversion = Telescope.map(LegacyUser.class, UserRecord.class);
```

Renames (`Mapping.to(srcAcc, tgtAcc)`), typed transforms (`Mapping.to(srcAcc, tgtAcc, fwd, bwd)`), null-coalescing
defaults (`Mapping.toOrElse` / `toOrElseGet`), by-name enum mapping (`Mapping.enumTo`), and pre-built nested mappers
(`Mapping.via(srcAcc, tgtAcc, mapper)`) work the same way they do for records — see the rows under
[Type conversion](type-conversion.md).

**`writeBean` — pin a POJO write strategy.** With no hint, a POJO is built through the first of these it offers: a
static `builder()`, then a single public all-args constructor (when its parameter names are known, from `-parameters` or
from the debug information `-g` keeps, and match the property names), then a no-arg constructor with setters. The
builder is skipped in two cases. First, when it has no member named for a property that the next strategy in the order
would write: every property, if the class has a name-matched all-args constructor, and otherwise every property a public
setter writes. A builder silently drops a property it has no member for, so taking it would lose a value the other
strategy keeps. Second, when a member named for any property cannot take a value of that property's type (a varargs
`p(String...)` behind a `String` property, a `long` member behind a `Long` getter), since every write through the
builder would then fail. A null bound to a primitive property ends at the type's default (`0`, `false`) whichever
strategy builds the bean. The generated `@Bridge`, `@BeanFocus` and `@FromMap` code takes the same order, so a POJO
offering several of these is built the same way on both paths. Pass an explicit `writeBean(target, strategy)` row to
force one of `BUILDER` / `SETTERS` / `CONSTRUCTOR`, either to keep a different strategy than the order picks or for a
class the auto path refuses because it offers a write path it will not guess at (immutable all-args-only POJOs compiled
with neither `-parameters` nor `-g`, ambiguous multi-ctor classes). A `CONSTRUCTOR` hint binds arguments by parameter
name when the names are known, from `-parameters` or `-g`, and every one names a property. With no names, or with names
none of which is a property, it binds by position. A constructor only some of whose known names are properties is
refused when the mapper is built, whether a `writeBean` hint or a `writeBeans(...)` default names it, since by name the
other parameters would have no value and by position one property's value could land in a parameter named after another:
name every parameter after a property, or write the class through setters or a builder. Inside a native image the names
`-g` keeps cannot be read, so compile with `-parameters` to get this check there. Pinning the constructor the order
would pick anyway binds it the same way. A class whose only write path is its private fields — a no-arg constructor and
no other constructor, no public setters and no static `builder()` — is refused outright: add public setters, an all-args
constructor or a static `builder()`, or supply an explicit mapping row that produces it. Package-private setters do not
count; neither path calls them.

```java
import static io.github.eschizoid.telescope.mapping.Mapping.CONSTRUCTOR;
import static io.github.eschizoid.telescope.mapping.Mapping.writeBean;

// OrderPojo has a public (String sku, int qty) ctor, no builder, no setters — autoWriter would
// refuse without -parameters or -g. The hint forces the CONSTRUCTOR strategy explicitly.
final Telescope<OrderRecord, OrderPojo> conv = Telescope.map(
  OrderRecord.class,
  OrderPojo.class,
  writeBean(OrderPojo.class, CONSTRUCTOR),
  to(OrderRecord::sku, OrderPojo::getSku)
);
```

Validation is eager: a misconfigured hint (`BUILDER` on a no-builder class, hint targeting a record, duplicate hint,
unused hint) throws at `Telescope.map(...)` time — not on the first conversion deep in production. So does a
`writeBeans(...)` default naming a strategy that cannot build a bean the mapper builds, and a bean built through its
setters, whether a hint names them or the order picks them, with no setter for a property the mapper writes into it,
which would otherwise drop the value. A bean the mapper never builds, such as the source of a `mapperForward` mapper,
and a property nothing writes are not held to either. And with `telescope-codegen` on the annotation-processor path, the
structural rejections that don't need the live classpath — a hint targeting a record, a duplicate hint — move up to
**compile time**: statically-visible `map(...)` / `mapper(...)` call sites are replayed by the verifier and those
violations surface as compile errors with the identical diagnostic text
([details in `telescope-codegen`](../codegen/README.md#compile-time-mapper-verification)). The builder-feasibility and
unused-hint checks stay at construction time (both still eager and loud).

A getter the class derives from its other state, such as a `getFull()` joining two names, has no setter, no builder
member and no constructor parameter of its name. A strict two-way mapper leaves it out of the pairing on either side, on
both paths and in the verifier: nothing could write it, so the mapper asks for no row that fills or reads it. A
getter-only property that a builder member or a constructor parameter writes is refused like any other property nothing
pairs with.

`Mapper.into(target, source)` updates, in place, the properties the mapper writes, through the setters of the object it
is handed, whatever strategy `forward` builds a new one with. A bean built by a builder or a constructor may have no
setter for one of them, and `into` then refuses the call by the property's name before it writes anything. The refusal
comes on the first `into` call for a target of that class rather than when the mapper is built, since a mapper used only
through `forward` needs no setters.

`into` merges a `Collection` or `Map` property where it can and replaces it where it cannot. When the target's getter
returns a container that can be changed, `into` clears it and refills it with the converted elements, so the container
keeps its identity, which is what a managed `@OneToMany` with orphan removal needs. When the getter returns null, or a
container that cannot be changed, `into` writes the converted container through the setter: an unmodifiable JDK
container (`List.of`, `Collections.unmodifiableList` and their kin) is known in advance, and any other container whose
refill throws, such as `Arrays.asList` or a library's immutable collection refusing the change, or a natural-order
`TreeSet` handed a null, has its old elements put back, so anyone else holding it sees it unchanged, and is then
replaced. A null container from the source is written through the setter as null, as `forward` gives it. Only a
container property nothing can write, one with no setter whose target holds no container or one that cannot be changed,
is refused by the property's name. Container properties are written before every other property, so a refused call
leaves the target's other properties as they were. Both paths make the same decision through the same `ContainerCopy`
methods.

```java
final OrderEntity managed = repository.findById(id).orElseThrow();
final List<LineEntity> lines = managed.getLines();
mapper.into(managed, dto);
assert managed.getLines() == lines; // the same list, now holding dto's lines
```

**Adders, removers and back-references.** No row says how a collection is written. A mapper writing a collection
property of a bean, through `forward`, `backward`, `into` or `patchInto`, ties each converted element to the owner by
the rule below, read from what the classes declare, and `patch` builds its result through the same adders. A property's
type alone decides nothing. The same rule runs on the runtime and the generated path.

| Step                                    | What decides it                                                                                                                                                                                                                                                                                                                                                               | What the write does                                                                                                                                                                                                                |
| --------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1. The owner's adder                    | a public instance method whose name starts with `add`, taking the collection's element class and returning nothing or the owner; a method named for the collection (`addLine` for `lines`) may take a wider class than `Object` the element can be passed as; the most specific parameter type wins, then the name; a method named for another collection is left to that one | each element goes through it, wherever the owner is written member by member (a setters build, `into`, `patchInto`); the collection needs no setter                                                                                |
| 2. The held collection or the setter    | no adder                                                                                                                                                                                                                                                                                                                                                                      | `into` and `patchInto` clear and refill the collection the owner holds where it can change, and otherwise write it through the setter, as described above                                                                          |
| 3. The back-reference                   | a `backReference(OrderLine::getOrder)` row; else, with `telescope-jpa`, the property `@OneToMany(mappedBy)` names; else none                                                                                                                                                                                                                                                  | where no adder sets it, every element the owner holds after the write points back at it                                                                                                                                            |
| 4. A property the element already fills | the element's conversion writes it, by name or by a row                                                                                                                                                                                                                                                                                                                       | it keeps its value and is not treated as a back-reference                                                                                                                                                                          |
| 5. Removal                              | the owner's remover: a method whose name starts with `remove`, chosen as the adder is, which may also return a `boolean`                                                                                                                                                                                                                                                      | each element the write takes out goes through it; with no remover, its back-reference, where step 3 names one, is cleared if it still points at the owner                                                                          |
| 6. Undecided                            | two candidate adders, or removers, that neither the parameter type nor the name can settle                                                                                                                                                                                                                                                                                    | none of them is used: the collection is written as in step 2, a warning names them, logged once when the mapper is built and printed at compile time on a `@Bridge` and by the verifier, and `explain()` lists them as left unused |
| 7. Refused                              | an undecided adder of a collection with no setter, or no adder, writable collection or setter that can take the elements                                                                                                                                                                                                                                                      | the mapper is refused when it is built, and at compile time on every `@Bridge` and in the verifier, with the methods or the property named                                                                                         |

Mutable beans handed across as the source holds them, a same-typed collection such as `Mapper<Order, Order>` copies, go
through no adder or remover and are never pointed back: the write fills and refills the collection with the source's own
elements, which keep the owner they point at. Values handed across, a record, an enum, a `String`, a boxed primitive or
a JDK value type such as `LocalDate` or `UUID`, do go through the adder and remover, so an adder that keeps each tag
once keeps it once on every write. Only the method named for a collection may take a wider class than its element: an
`addAttachment(Serializable)` is no adder of a list of `Serializable` lines.

A plain bean whose elements point back at their owner, with no adder setting the pointer and no `mappedBy`, names the
property with a `backReference` row. The row also overrides `mappedBy` where you want to pin it, and is how a
`telescope-jpa` entity whose `@OneToMany` has no `mappedBy` gets one set; without it, that entity's elements are left as
their conversion builds them.

```java
final Mapper<Order, OrderEntity> mapper = Telescope.mapper(
  Order.class,
  OrderEntity.class,
  backReference(LineEntity::getOrder)
);
```

A `backReference` row is refused by name when the getter's type is not the owner class of a collection the mapper
writes, when no collection the mapper writes converts elements of that class, when the element's conversion already
writes the property, or when the element has no setter for it that takes the owner.

An adder that rejects an element by throwing has its exception thrown on, once the held collection has its elements
back: the setter never writes the collection past the adder's check and wiring. The value `forward` builds for `into` to
read from runs none of its adders, so each adder runs once per element, on the target. Where the collection the owner
holds after the write has an element equal to one taken out, the taken one has its back-reference cleared rather than
going to the remover, since the remover's own `remove` would take out the equal element. With an adder and a remover and
no declared back-reference, nothing says how to untie it, so the taken instance keeps pointing at the owner.

```java
public class OrderEntity {

  private final List<LineEntity> lines = new ArrayList<>();

  public List<LineEntity> getLines() {
    return lines;
  }

  public void addLine(final LineEntity line) {
    line.setOrder(this);
    lines.add(line);
  }
  // ...
}

final Mapper<Order, OrderEntity> mapper = Telescope.mapper(Order.class, OrderEntity.class);
```

`explain()` reports how each collection is written, one line each, under `Collections`, on the runtime and the generated
mapper alike, and once for a pair of one class both ways:

```text
Collections
  • OrderEntity.lines: through OrderEntity.addLine(LineEntity)
  • Basket.items: refilled in place, back-reference Item.basket set by backReference and cleared on removal
  • Order.lines: refilled in place, back-reference OrderLine.order set from mappedBy and cleared on removal
  • Team.members: refilled in place
```

`refilled in place` is what `into` and `patchInto` do; a new owner `forward` builds gets the converted collection
through its setter. With `-Atelescope.verify=warn` the verifier prints the same line for each collection as a
compile-time note; a default build prints none.

Coming from the `ADDERS` write strategy: delete the `writeBean(X.class, ADDERS)` row, since the adder is now found by
its type. A bean with a no-argument constructor and an adder is built through its setters and adders with no row, also
when it has no setter at all.

On a `@Bridge` field's mapper, `into` is generated with the bridge when the target class has a setter for every property
the bridge writes other than a container: plain setter and adder calls, no reflection, with the same result, for a
target of exactly that class. A subclass, whose own setters may take a value differently, and a target class without
such members are written by the runtime `into` above, and `explain()` says which of the two writes.

**`writeBeans(STRATEGY)` — one default for every bean target.** When every entity in the recursion shares the same
construction shape (the common JPA case: every `@Entity` needs `SETTERS` so Hibernate's identity assignment fires), one
`writeBeans(SETTERS)` row replaces N per-class enumerations. Per-class `writeBean(X.class, ...)` still wins for class
`X`. At most one `writeBeans(...)` default per call.

```java
import static io.github.eschizoid.telescope.mapping.Mapping.BUILDER;
import static io.github.eschizoid.telescope.mapping.Mapping.SETTERS;
import static io.github.eschizoid.telescope.mapping.Mapping.writeBean;
import static io.github.eschizoid.telescope.mapping.Mapping.writeBeans;

final Mapper<Order, OrderEntity> orderMapper = Telescope.mapper(
  Order.class,
  OrderEntity.class,
  writeBeans(SETTERS), // default for OrderEntity, CustomerEntity, LineItemEntity, AddressEmbeddable, …
  writeBean(CashRegisterEntity.class, BUILDER) // override on one specific target
);
```

**Composing the conversion into a path.** The mapping result is a `Telescope<A, B>`, so it threads through a longer path
the same way any other telescope does:

```java
Telescope.of(Page.class)                  // Page is a record holding List<LegacyUser>
    .each(Page::items)
    .then(conversion)                     // each POJO ↔ record at this hop
    .field(UserRecord::email)
    .update(page, String::toLowerCase);
```

**`Telescope.mapper(...)` — the `Mapper<A, B>` sibling.** Same deep recursion, but the return is a `Mapper<A, B>`
exposing `forward` / `backward` / `read` / `patch` / `asTelescope` / `liftList` / `liftSet` / `liftOptional` /
`liftMapValues`. `patch(base, partial)` overlays non-null fields of `partial` onto `base` — useful for sparse JSON /
form updates. It always returns a new object, even when `partial` changes nothing, and never changes `base`.
`patchInto(target, partial)` is the same overlay written in place: it writes into `target` exactly the properties
`patch` would overlay, and only those, the way `into` writes, so a field the partial leaves null keeps its value and a
`Collection` or `Map` the target holds is refilled in place. It returns `target`, and is the PATCH of a managed entity.
`asTelescope()` returns the mapper as a `Telescope<A, B>` for `.then(...)` composition into a longer typed path
(bridging record-side navigation into entity-side leaves, or vice versa). The `lift*` methods promote an element-level
mapper to a container-level mapper without going through a `via(...)` row — useful when the lifted mapper is the
call-site root (e.g., a bulk handler that converts a `List<Order>` payload to `List<OrderEntity>`). A lifted mapper's
root is a container with no fields to overlay, so its `patch` and `into` throw `UnsupportedOperationException`.

```java
final Mapper<UserBean, UserView> mapper = Telescope.mapper(UserBean.class, UserView.class);

final UserView withFresh = mapper.patch(view, new UserView(null, "new@x", null));

// Container promotion for a bulk endpoint:
final Mapper<List<UserBean>, List<UserView>> bulk = mapper.liftList();
final List<UserView> view = bulk.forward(beans);

// Thread the conversion into a longer Telescope chain via .then():
Telescope.of(Page.class)
    .each(Page::items)
    .then(mapper.asTelescope())
    .field(UserView::email)
    .update(page, String::toLowerCase);
```

For a worked end-to-end demo, an order service whose PATCH writes into a managed JPA entity through `into` and fills its
lines through the entity's own adder, see [`examples/demo/`](../examples/demo/README.md).

**`@Bridge` — reflection-free, compile-checked (any pair).** The codegen counterpart to `Telescope.map(...)`. Annotate
the source you own with the target type; the processor generates `<Source>Bridge.BRIDGE`, a `Telescope<Source, Target>`
built from direct component/getter reads and constructor / builder / setter calls. Both sides may be records or POJOs —
record⇄record, record⇄POJO, POJO⇄POJO. Fields match by name (a bijection); a name mismatch or a missing construction
strategy is a compile error, not a runtime one:

```java
import io.github.eschizoid.telescope.annotations.Bridge;

@Bridge(UserDto.class)
record UserEntity(String id, String email) {}

// Generated alongside:  UserEntityBridge.BRIDGE  (a Telescope<UserEntity, UserDto>)
UserDto dto = UserEntityBridge.BRIDGE.read(entity);

// BRIDGE is a Telescope value, so it threads through a longer path:
final Page lowered = Telescope.of(Page.class)
  .each(Page::entities) // each UserEntity on the page
  .then(UserEntityBridge.BRIDGE) // view it as a UserDto
  .field(UserDto::email)
  .update(page, String::toLowerCase);
```

It auto-detects each side's strategy at compile time (record canonical constructor; POJO builder → name-matched
constructor → no-arg + setters, the order the runtime takes). Renames and per-field conversions are rows on a `@Bridge`
field initialised by `Telescope.mapper(...)`, which the processor compiles into the bridge; see
[a generated mapper declared on its field](codegen.md#a-generated-mapper-declared-on-its-field-bridge). Wire up
`telescope-codegen` as shown under [Compile-time codegen](codegen.md#installing-the-processor).

**`from/to/using` — hand-written.** When the mapping is lossy, one-directional, or just custom, write both functions
yourself:

```java
public static final Telescope<LegacyUser, UserRecord> USER_CONVERSION = Telescope.from(LegacyUser.class)
  .to(UserRecord.class)
  .using(
    (l) -> new UserRecord(l.getName(), l.getEmail(), l.getAddress()),
    (r) -> {
      final var u = new LegacyUser();
      u.setName(r.name());
      u.setEmail(r.email());
      u.setAddress(r.address());
      return u;
    }
  );
```

## Navigate — `ofBean`

When you'd rather not define a mirror record, navigate the POJO directly. `.field(Pojo::getX)` reads via the getter;
`set`/`update` rebuild the POJO immutably with that one property changed (write strategy auto-detected per type: builder
→ all-args constructor → setters; a POJO writable only through its private fields still reads, and is refused on the
first write). Deep paths and `.each(...)` compose like records:

```java
Telescope.ofBean(LegacyUser.class)
  .field(LegacyUser::getAddress)
  .field(Address::getCity)
  .update(user, String::toUpperCase); // new LegacyUser; the original is untouched
```

**Cost — measured.** `ofBean` rebuilds the whole POJO and re-reads every getter at _each_ level of the path: a 3-level
update benchmarks at roughly 10–14x a hand-written copy (see [`benchmarks/`](../benchmarks/README.md)). Fine for
ordinary use (sub-microsecond); for a hot loop over many objects, convert to a record once with
`Telescope.map(Pojo.class, Record.class)` and navigate the record (or use `@BeanFocus` codegen) instead. The runtime
deep-mapping conversions are cheaper than deep navigation-and-update — the current per-benchmark numbers live in
[the benchmark table](../benchmarks/README.md).

## Scope

`Telescope.map(...)` / `@Bridge` match by exact name and need a same-named field on each side (with optional rename rows
via `Mapping.to(srcAcc, tgtAcc)`); nested collections recurse automatically. Neither path writes a private field: the
runtime writes through setters, a static `builder()` or a constructor, the same members `@Bridge` generates calls to,
and both refuse a POJO that offers none of them. The runtime reaches those members through a private lookup, so under
JPMS a POJO package that is exported but not opened needs `opens <package> to io.github.eschizoid.telescope.internal`.
