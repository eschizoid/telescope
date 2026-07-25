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
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.SETTERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;

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

**`writeBean` — pin a POJO write strategy.** `Beans.autoWriter` picks a ladder: `builder()` → no-arg ctor + setters →
no-arg ctor + reflective field injection → single public all-args ctor (when compiled with `-parameters` and ctor
parameter names match the property names). For classes the auto path refuses (immutable all-args-only POJOs without
`-parameters`, ambiguous multi-ctor classes), pass an explicit `WriteHint.writeBean(target, strategy)` row to force one
of `BUILDER` / `SETTERS` / `FIELDS` / `CONSTRUCTOR`:

```java
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.CONSTRUCTOR;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBean;

// OrderPojo has a public (String sku, int qty) ctor, no builder, no setters — autoWriter would
// refuse without -parameters. The hint forces the CONSTRUCTOR strategy explicitly.
final Telescope<OrderRecord, OrderPojo> conv = Telescope.map(
  OrderRecord.class,
  OrderPojo.class,
  writeBean(OrderPojo.class, CONSTRUCTOR),
  to(OrderRecord::sku, OrderPojo::getSku)
);
```

Validation is eager: a misconfigured hint (`BUILDER` on a no-builder class, hint targeting a record, duplicate hint,
unused hint) throws at `Telescope.map(...)` time — not on the first conversion deep in production. And with
`telescope-codegen` on the annotation-processor path, the structural rejections that don't need the live classpath — a
hint targeting a record, a duplicate hint — move up to **compile time**: statically-visible `map(...)` / `mapper(...)`
call sites are replayed by the verifier and those violations surface as compile errors with the identical diagnostic
text ([details in `telescope-codegen`](../codegen/README.md#compile-time-mapper-verification)). The builder-feasibility
and unused-hint checks stay at construction time (both still eager and loud).

**`writeBeans(STRATEGY)` — one default for every bean target.** When every entity in the recursion shares the same
construction shape (the common JPA case: every `@Entity` needs `SETTERS` so Hibernate's identity assignment fires), one
`writeBeans(SETTERS)` row replaces N per-class enumerations. Per-class `writeBean(X.class, ...)` still wins for class
`X`. At most one `writeBeans(...)` default per call.

```java
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.SETTERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBean;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;

final Mapper<Order, OrderEntity> orderMapper = Telescope.mapper(
  Order.class,
  OrderEntity.class,
  writeBeans(SETTERS), // default for OrderEntity, CustomerEntity, LineItemEntity, AddressEmbeddable, …
  writeBean(CashRegisterEntity.class, FIELDS) // override on one specific target
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
form updates. `asTelescope()` returns the mapper as a `Telescope<A, B>` for `.then(...)` composition into a longer typed
path (bridging record-side navigation into entity-side leaves, or vice versa). The `lift*` methods promote an
element-level mapper to a container-level mapper without going through a `via(...)` row — useful when the lifted mapper
is the call-site root (e.g., a bulk handler that converts a `List<Order>` payload to `List<OrderEntity>`).

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

For a worked end-to-end demo using every public Mapping / Mapper / Telescope row through a Spring Boot 4, Hibernate, and
Jackson REST pipeline, see [`examples/springboot/`](../examples/springboot/).

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

It auto-detects each side's strategy at compile time (record canonical constructor; POJO name-matched constructor →
builder → no-arg + setters). Renames and per-field transforms can't be expressed in an annotation — use the runtime
`map` / `from/to/using` for those. Wire up `telescope-codegen` as shown under
[Compile-time codegen](codegen.md#installing-the-processor).

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
→ setters → field injection). Deep paths and `.each(...)` compose like records:

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
via `Mapping.to(srcAcc, tgtAcc)`); nested collections recurse automatically. The `FIELDS` write strategy (and `ofBean`'s
field-injection fallback) uses `setAccessible`, so under JPMS the POJO's package must be `opens`'d to
`io.github.eschizoid.telescope` — `CONSTRUCTOR` / `BUILDER` / `SETTERS` (and all of `@Bridge`) use public members only.
