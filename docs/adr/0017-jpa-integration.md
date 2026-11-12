# ADR-0017: `telescope-jpa` — persistence-aware container copies and proxy handling

**Status:** Accepted, amended · **Date:** 2026-10-08 · **Accepted:** 2026-11-11 · **Amended:** 2026-11-12

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
that already holds one clears and refills that instance instead of replacing it, through the owner's adder under the
`ADDERS` write strategy, replaces through the setter a held container that cannot be changed, and refuses by name only a
property no setter or modifiable container can take. At most one active policy is registered, and it replaces the
standard one as a whole. Whether `patch` refills a container the same way is checked before this module is built, and
fixed in core if it does not.

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
and refilled, is a later step with its own amendment. It plugs into the same `ContainerCopy` hook, but how the DTO
side's identifier is found, how new elements and removals are ordered, and how hash-based sets and list order behave are
not settled by this record.

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
