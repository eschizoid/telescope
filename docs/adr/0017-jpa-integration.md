# ADR-0017: `telescope-jpa` — persistence-aware container copies and proxy handling

**Status:** Accepted, amended · **Date:** 2026-10-08 · **Accepted:** 2026-11-11 · **Amended:** 2026-11-12, 2026-11-19,
2026-11-26

## Context

Entity-to-DTO mapping is the most common reason a team adopts a mapper, and it is where a mapper meets the persistence
provider's own collection and proxy types. Three behaviors decide whether a mapping is safe on a JPA entity:

- **Lazy collections.** A same-typed container component is copied rather than shared with the source. Copying iterates
  the source, so a lazy `@OneToMany` backed by a provider collection (`PersistentBag`, `PersistentSet`) is loaded from
  the database inside a session and throws `LazyInitializationException` outside one. On a list endpoint that maps a
  page of entities whose collection the response never reads, the copy adds one query per entity. MapStruct's generated
  `new ArrayList<>(entity.getItems())` behaves the same way; its users avoid it with
  `@Mapping(target = ..., ignore = true)` or a hand-written `@Condition` method calling the provider's `isInitialized`.
- **Proxies.** A lazy `@ManyToOne` reference is a runtime subclass of the entity. Reflection over its class sees the
  proxy rather than the entity. Core handles Hibernate's `HibernateProxy` today through an optional, reflectively loaded
  accessor pair in `Beans`, which is also the one documented exception to the native-image accessor rule.
- **Managed collections on patch.** A provider tracks a managed entity's collection by instance. Replacing an
  `orphanRemoval` collection with a new instance makes the provider throw at flush ("a collection with
  cascade=all-delete-orphan was no longer referenced"). The safe write clears the managed instance and adds the new
  elements to it.

None of this belongs in core, which carries no persistence dependency, except the patch behavior, which is a property of
writing into any existing mutable container rather than of JPA.

## Decision

Add a `telescope-jpa` module written against the Jakarta Persistence API (`jakarta.persistence:jakarta.persistence-api`)
rather than a provider, so it serves Hibernate and EclipseLink alike.

1. **Copy only what is loaded.** The module registers an implementation of `ContainerCopy.Policy`, the `ServiceLoader`
   SPI that `ContainerCopy` consults, the class both the runtime mapper and generated bridges call to copy a same-typed
   container. For a source collection `PersistenceUtil.isLoaded` reports as not loaded, the policy's `copy` returns the
   instance unchanged; a loaded collection is handed back to `ContainerCopy.standardCopy`. Because both paths call the
   same function, the runtime and codegen paths cannot disagree about it.
2. **Leave an unloaded collection alone on `into`.** The same policy's `refill` replaces the clear-and-refill core makes
   when `into` writes a collection into an entity that already holds one. It is handed a `ContainerCopy.Refill`: the
   owner, the property, the collection the owner holds, the converted collection, and whether elements go through the
   owner's adder, with `Refill.add` to add one element as the write adds it. A held collection the provider has not
   loaded is left as it is; anything else is handed back to `Refill.standard`. A merge by `@Id`, which is not decided
   here, plugs into the same method.
3. **Proxy unwrapping moves out of core.** The `HibernateProxy` accessor pair leaves `Beans` and becomes a provider of
   the same SPI family, resolving a proxy to its entity class. Core is left with no provider-specific code, and the
   native-image exception goes with it.
4. **Starter wiring.** The Spring Boot starter and the Quarkus extension carry the module as a dependency. Its providers
   register themselves with core through `ServiceLoader` and are active only where `jakarta.persistence` is on the class
   path, so an application with JPA gets them with no setup and one without it pays nothing.

The collection write stays in core, through that hook: `into` writing a `Collection` or `Map` property into a target
that already holds one clears and refills that instance instead of replacing it, through the owner's adder where it has
one, replaces through the setter a held container that cannot be changed, and refuses by name only a property no setter
or modifiable container can take. At most one active policy is registered, and it replaces the standard one as a whole.
Whether `patch` refills a container the same way is checked before this module is built, and fixed in core if it does
not.

A `share(src, tgt)` row in core remains the explicit per-field opt-out for cases the policy does not cover, such as a
live concurrent view or a very large container on a hot path.

## Consequences

- An entity-to-DTO mapper needs no per-field rows to avoid loading a lazy collection the caller never reads.
- A DTO built from an unloaded collection holds the provider's collection. Reading it after the session closes throws,
  exactly as reading the entity's field would; the module changes when the load happens, not whether it can fail.
- Core gains one SPI seam in `ContainerCopy` and loses its Hibernate-specific code.
- The module adds a compile-time dependency on the Jakarta Persistence API only; no provider is required at compile
  time.

## Alternatives considered

- **A Hibernate-specific module.** Rejected: the lazy-load and proxy questions are answerable through the Jakarta
  Persistence API, and a provider-specific module would exclude EclipseLink users for no gain.
- **Keep everything in core behind optional class loading**, as the proxy accessors are today. Rejected: it grows a
  provider-specific branch in core for each behavior and keeps the native-image exception.
- **Rely on `share(...)` alone.** Rejected as the only answer: it makes every entity mapper opt out field by field,
  which is the MapStruct `ignore = true` pattern in typed form rather than an improvement on it.

## Not decided here

Merging a managed collection by `@Id`, so a matching element is updated in place instead of the collection being cleared
and refilled, is decided by the second amendment below.

## Open questions

- Whether `PersistenceUtil.isLoaded` is cheap enough per call on the hot path, measured with the `Benchmarks` workflow
  against a control before the policy is enabled by default.

## Amendment (2026-11-12)

Decisions 1, 3 and 4 shipped as written. Two parts of the record are changed.

- **Decision 2 is dropped.** Leaving a held collection the provider has not loaded alone on `into` would discard the
  converted elements in the common case, a DTO written into a managed entity whose lazy collection nothing has read: the
  held collection is that unloaded one, the elements are the DTO's, and leaving it alone writes none of them. It also
  cannot take effect through the hook as built: a refill reads the held collection, to put its elements back if the
  refill fails, before the policy is asked, and that read loads it. The module keeps the standard `refill`. A held
  collection that cannot be read at all, a lazy one outside its session, is written through the setter instead, or
  refused by the property's name where there is none.
- **`patch` stays pure.** In-place updates are `into`'s alone. The object `patch` returns is a new one, never the
  managed instance, so keeping a collection's identity there protects nothing the provider tracks, and refilling the
  base's collections would change the base `patch` documents it leaves as it was. The check this record asked for found
  that `patch` does not refill, and that is kept.

## Amendment (2026-11-19): merging a managed collection by `@Id`

With this module active, `into` and `patchInto` merge a collection of entities by identifier instead of clearing and
refilling it: a converted element whose identifier matches an element the owner holds is written into that element in
place, a new one is added, and a held element nothing matches is removed. Clear-and-refill keeps the collection's
identity but not its elements'. Measured against Hibernate, with its statistics, on a loaded `orphanRemoval`
`@OneToMany` of three elements:

- refilling it with two elements that carry no identifier issues three DELETEs and two INSERTs, the INSERTs first;
- refilling it with elements that carry the held identifiers fails at flush with "detached entity passed to persist",
  since each converted element is a new instance carrying the identifier of a managed one;
- writing the same values into the held elements in place issues no statement, and changing one property of one element
  issues exactly one UPDATE.

So the merge differs from clear-and-refill only where an element is matched, which today fails at flush on a managed
owner, and, on a detached owner later passed to `EntityManager.merge`, resets every property the conversion does not
write to a new instance's default. Where no element is matched, the merge removes every held element and adds every
converted one, which is what clear-and-refill does.

1. **The identifier is read from the converted element.** The DTO element is never inspected. Whatever the element
   conversion writes into the entity's identifier, by name, through a `to` row or through a nested mapper, is what is
   matched, so the pairing is the element conversion's own and both paths read the same value. The identifier is the
   property annotated `jakarta.persistence.Id` or `EmbeddedId`, on a field or a getter of the element's class or of a
   superclass, read through its getter; several `@Id` properties, as an `@IdClass` declares, match on all their values
   together, and values match by `equals`. A conversion that writes nothing into the identifier leaves it as a new
   instance of the entity holds it, so every converted element is new. Matching is scoped to the collection the owner
   holds, so a converted element can only ever update an element of that owner's. A collection whose element class is
   not an `@Entity`, whose identifier has no getter, and a `Map` are refilled as before; an `@ElementCollection` of
   embeddables has no identity to merge by.
2. **New elements, and what an identifier's value says.** A converted element whose identifier is null is new. So is one
   whose identifier is a single property of a primitive type holding that type's default, such as a `long` holding 0,
   which is how the provider reads an instance no row carries yet; a DTO sending a primitive `long` into a boxed `Long`
   identifier therefore sends 0 for a new element, and a converted `Long` of 0 is an identifier like any other, since
   null is how a boxed identifier says an element is new. A new element is added as the conversion built it, through the
   owner's adder where the write goes through one. A non-null identifier that matches nothing is new where the
   identifier is assigned, and refused by the property's name where it is generated: the provider assigns a new row's
   generated identifier, and an instance arriving with one is a detached instance it refuses at flush. An identifier is
   generated where its property is `@GeneratedValue`, or carries an annotation that Hibernate's
   `org.hibernate.annotations.IdGeneratorType` marks, such as `@UuidGenerator`, found by name and never linked; a
   generator another provider declares in an annotation of its own, with no `@GeneratedValue`, is not seen, and its
   identifier is taken for an assigned one.
3. **Refusals, made before anything is written.** Two converted elements carrying one identifier are refused by name. So
   are two sent elements carrying different identifiers that a converted set kept one of, because they converted into
   equal values: written as it stands, the held element the dropped one names would be removed. To read those
   identifiers, every sent element is converted a second time, which only a set holding fewer elements than were sent
   costs; a conversion that is not pure, a `compute` row or a converter with an effect of its own, then runs twice for
   each of them. A dropped element that carries no identifier is not refused: it is a new element equal to another sent
   one, and the set keeps one of the two, dropping the other silently, as `forward` drops it with or without this
   module. Every refusal is made in a read-only check the policy runs in the pass that already refuses a container
   nothing can write, and the check follows each matched element into its own collections, so a refusal two levels down
   is made before the first element of the top level is written. A held collection the provider reports not loaded,
   through `PersistenceUtil.isLoaded`, is loaded by the check, which costs no query the refill would not issue reading
   it, so its refusals come before the first write too and no elements are put back into a collection of a versioned
   owner. Outside its session that load fails with Hibernate's `LazyInitializationException`, matched by class name and
   never linked; that property is then written through its setter, or refused by name where there is none, as without
   the module. Anything else the load throws, such as a database error, reaches the caller.
4. **Write order.** In memory, matched elements are written first, then the held collection drops what nothing matched
   before it takes a new element, so a set never holds a new element beside the one it replaces. The order statements
   reach the database is the provider's: Hibernate flushes an insert before an orphan deletion, so a new element that
   takes a unique key from a removed one conflicts, exactly as it does under clear-and-refill; an element that keeps its
   identifier is matched and updated, and conflicts with nothing.
5. **Sets.** Writing an element in place can change the `hashCode` or the comparator position a set files it under, and
   a set no longer finds an element filed under a stale one. After the writes, a set whose membership changed, or that
   no longer finds every merged element, is cleared and given the merged elements again, the held instances themselves,
   which files each under its current position. A set that ends with fewer elements than the merge produced, because two
   held elements became equal in their writes, is refused by the property's name, with its elements put back.
6. **List order.** A list ends in the order of the converted collection, the order `forward` gives it and clear-and-
   refill gave it. A list that already holds the merged elements in that order is not touched. One that does not is
   cleared and given the merged elements in that order, the held instances themselves.
7. **What a changed collection costs.** A collection left untouched leaves its owner clean, and a versioned owner keeps
   its version. A collection the merge clears and refills is dirty to the provider whatever it ends up holding, so on an
   owner with a `@Version` property a reordered list or a rehashed set costs one UPDATE of the owner, the version's,
   beside the elements' own. Measured against Hibernate, the refill adds no statement on a bag, inverse or not; on an
   `@OrderColumn` list it updates the positions that moved; and on a set the owner maps through a join column,
   Hibernate's snapshot of the set holds a rehashed element under its old hash, so the flush writes its join column
   again, one UPDATE more, which any set that finds the element again costs. On an inverse (`mappedBy`) set the rehash
   adds nothing beyond the element's own UPDATE.
8. **The merge needs a conversion that can write in place.** Writing a converted element into a held one means writing
   the properties the element conversion writes and no others, so telescope has to know them: a nested pair the mapper
   converts property by property, or the mapper of a `via(..., Mapper)` row. Elements converted by a `via` converter
   class or method pair, whose written properties are the hand-written code's own, or handed across unchanged, are
   cleared and refilled exactly as without the module, matched or not, and so are elements of a pair whose rows write
   through a path or whose element class has adders of its own. Refusing a matched element there would break two writes
   that work today, `into` on entities outside any persistence context and on a detached owner later passed to
   `EntityManager.merge`, to prevent one, a managed owner, that fails at flush; that owner keeps today's behavior.
9. **No opt-in.** The merge is automatic wherever the module is active and the element class is an entity with a
   readable identifier, because it differs from clear-and-refill only where clear-and-refill fails or resets state. No
   row selects it; the policy is still one active `ContainerCopy.Policy`, and `Policy.refill` is still its one hook for
   writing a container. Each container write hands the policy the source's container it was converted from and, where
   the elements convert through a conversion that can write in place, a `ContainerCopy.Elements`: the conversion of one
   sent element, the check of one element's write, its own containers included, and that write. `ContainerCopy.Refill`
   also gains a way to put a held element back without running the owner's adder on it, and a refusal by the property's
   name that core throws on with the held collection put back. `Policy` gains the read-only `check`, whose result is
   handed back to the refill of the same write, so the elements are matched once; whatever the check throws reaches the
   caller.
10. **The generated bridge does the same.** Both paths write a property into an existing target as "convert, then write
    the converted value's properties", so an element's write is that second half for the element pair. The runtime
    mapper takes it from the element pair it resolved: a nested pair, the pair's own bridge, or the mapper of a `via`
    row. Every generated bridge declares its own as `INTO_ELEMENTS` and `PATCH_INTO_ELEMENTS`, and a bridge converting a
    collection through a nested pair's bridge, or through the bridge of the `@Bridge` field a `via` row names, hands
    that bridge's to `ContainerCopy`. Where the element pair has no in-place write on one path, it has none on the
    other. The decision is the policy's, made once for both, and `CrossPathCorpusTest` holds the two paths to it. No
    difference between the paths is introduced, so nothing is added to ADR-0019's registers.
11. **Without the module.** Core's standard refill never uses the element conversion, so `into` clears and refills as
    before, and an application without telescope-jpa sees no change. `testWithoutTheModule` counts the statements the
    same write issues there.

## Amendment (2026-11-26): the back-reference from `mappedBy`

A mapper ties each element of a collection it writes to the owner with no row: through the owner's adder where it has
one, and otherwise by setting the element's back-reference to the owner (ADR-0002, amended the same day, records how the
adder and the back-reference are found from what the classes declare). The policy is where `@OneToMany(mappedBy)` is
read:

1. **`Policy.backReference(Class<?> owner, String property)`** answers the element property the owner's collection
   declares as its back-reference, null by default. `LoadedContainers` answers what `@OneToMany(mappedBy = "...")` names
   on the field of the collection's name or its getter. Core reads no persistence annotation, so it stays free of
   provider and specification code. The back-reference is then the property a `backReference` row names, else the one
   `mappedBy` names, else none: a `@OneToMany` with no `mappedBy` points no element back unless a row names the
   property. A property the element's conversion writes keeps its value.
2. **The generated bridge asks the same policy.** The processor reads `mappedBy` from the annotation's name, but where
   the element's own properties would give another answer, the bridge declares its `ContainerCopy.Links` through
   `ContainerCopy.links(owner, property, mappedBy, ...)`, which takes `mappedBy` only where the registered policy
   answers it when the bridge class loads. Without the module, both paths set no back-reference a row does not name, so
   nothing is added to ADR-0019's registers.
3. **Removal.** An element a write takes out goes through the owner's remover where it has one, and otherwise has its
   back-reference cleared where it still points at the owner. On an `orphanRemoval` association the element is deleted
   and the cleared reference adds no statement; without orphan removal it is one UPDATE setting the foreign key to null,
   and the row stays. A clear-and-refill takes out every held element it does not put back, and a merge by `@Id` the
   held elements nothing matched.

## Amendment (2026-11-26): what an edit mapper never writes

The policy also answers, through `ContainerCopy.Policy.generated`, which properties of an entity its mapping generates:
an `@Id` or `@EmbeddedId` with `@GeneratedValue` or a Hibernate generator annotation, and a `@Version`. An edit mapper
(ADR-0026) counts them as accounted for and no conversion of any pair it writes writes them, so they need no setter, and
the processor reads the same annotations by name, so both paths need no row for them; the merge reads a sent element's
identifier from the source's element. An assigned identifier still needs a row.
