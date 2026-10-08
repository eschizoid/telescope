# ADR-0017: `telescope-jpa` — persistence-aware container copies and proxy handling

**Status:** Proposed · **Date:** 2026-10-08

## Context

Entity-to-DTO mapping is the most common reason a team adopts a mapper, and it is where a mapper meets the persistence
provider's own collection and proxy types. Three behaviours decide whether a mapping is safe on a JPA entity:

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

None of this belongs in core, which carries no persistence dependency, except the patch behaviour, which is a property
of writing into any existing mutable container rather than of JPA.

## Decision

Add a `telescope-jpa` module written against the Jakarta Persistence API (`jakarta.persistence:jakarta.persistence-api`)
rather than a provider, so it serves Hibernate and EclipseLink alike.

1. **Copy only what is loaded.** The module registers a container-copy policy through a `ServiceLoader` SPI consulted by
   `ContainerCopy`, the one function both the runtime mapper and generated bridges call to copy a same-typed container.
   For a source collection `PersistenceUtil.isLoaded` reports as not loaded, the policy returns the instance unchanged;
   a loaded collection is copied as usual. Because both paths call the same function, the runtime and codegen paths
   cannot disagree about it.
2. **Proxy unwrapping moves out of core.** The `HibernateProxy` accessor pair leaves `Beans` and becomes a provider of
   the same SPI family, resolving a proxy to its entity class. Core is left with no provider-specific code, and the
   native-image exception goes with it.
3. **Starter wiring.** The Spring Boot starter and the Quarkus extension register the module when `jakarta.persistence`
   is on the classpath, as they already register mappers.

The patch behaviour stays in core: writing a same-typed container into a target that already holds a mutable instance
clears and refills that instance instead of replacing it. Whether `patch` does this today is checked before this module
is built, and fixed in core if it does not.

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
  provider-specific branch in core for each behaviour and keeps the native-image exception.
- **Rely on `share(...)` alone.** Rejected as the only answer: it makes every entity mapper opt out field by field,
  which is the MapStruct `ignore = true` pattern in typed form rather than an improvement on it.

## Open questions

- The exact SPI shape in `ContainerCopy`, settled once the same-typed copy behaviour is on main.
- Whether `PersistenceUtil.isLoaded` is cheap enough per call on the hot path, measured with the `Benchmarks` workflow
  against a control before the policy is enabled by default.
