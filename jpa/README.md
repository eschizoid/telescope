# telescope-jpa

`telescope-jpa` is the entity end of [telescope](../README.md). It makes a mapper safe to run over JPA entities: mapping
an entity never loads a lazy collection the mapping only copies, and never throws `LazyInitializationException` for one,
inside a session or after it has closed. It also lets core read a lazy Hibernate proxy as the entity class it stands
for.

The module is written against the Jakarta Persistence API alone, so it serves any provider that implements it. It
exports no package and has nothing to call: putting it on the class path is the whole setup. Both of its providers are
inactive where what they consult is absent, so it costs nothing in an application without JPA.

## Install

The Spring Boot starter and the Quarkus extension already bring it, so an application that uses either needs nothing
more. Anywhere else, add it beside `telescope-core`, at the same version. It ships from the first release after 2.2.0.

```kotlin
// Gradle
dependencies {
    implementation("io.github.eschizoid:telescope-jpa:$telescopeVersion")
}
```

```xml
<!-- Maven -->
<dependency>
  <groupId>io.github.eschizoid</groupId>
  <artifactId>telescope-jpa</artifactId>
  <version>${telescope.version}</version>
</dependency>
```

It needs `jakarta.persistence-api` 3.x on the class path at run time, which any JPA application has. It does not bring
it.

## A lazy collection is shared until something reads it

A container whose declared type is the same on both sides of a conversion, such as an author's `List<Book>` mapped to a
row that also holds a `List<Book>`, is copied rather than handed across, so that changing the row's list leaves the
entity's alone. Copying iterates the source, and iterating a lazy `@OneToMany` or `@ElementCollection` loads it: one
query per entity inside a session, and `LazyInitializationException` outside one. On a list endpoint that maps a page of
entities, that is one query for the page and one more for every row.

With this module, a container the provider reports as not loaded, through `PersistenceUtil.isLoaded`, is handed across
as it is. A loaded one is copied as before.

```java
public record AuthorRow(Long id, String name, List<Book> books) {}

final List<AuthorRow> rows = authors.stream().map(AUTHOR_ROW::forward).toList();
// Without telescope-jpa: 1 query for the page + 1 per author, to load each author's books.
// With telescope-jpa:    1 query. Each row holds the author's own lazy collection, unloaded.
```

The rule lives in `ContainerCopy`, the class both `Telescope.mapper(...)` and every bridge `@Bridge` generates call to
copy such a container, so the runtime and generated paths cannot disagree about it. The module's tests count the
statements through Hibernate's statistics on both paths, with the module and without it.

What a row then holds is the provider's collection. Reading it inside the session loads it, as reading the entity's
property would; reading it after the session has closed throws, as reading the entity's property would. The module
changes when a collection loads, not whether reading it can fail.

What it does not change:

- **A collection whose elements convert is still read.** `List<OrderLine>` mapped to `List<OrderLineDto>` builds a new
  list of converted elements, which needs the elements. A mapper reads only the properties its target declares, so a
  collection the target has no property for is never read in the first place, with or without this module.
- **`into` reads the collection the entity holds.** Writing a container into an entity that already holds one clears
  and refills that instance, so a managed `orphanRemoval` collection keeps its identity, and the elements it held are
  read first so that a refill that fails can put them back. A held collection that cannot be read, such as a lazy one
  outside its session, is replaced through the setter instead.

## A lazy proxy is read as its entity

A lazy `@ManyToOne` is a runtime subclass of the entity until something reads it. Reflection over that subclass sees
the proxy: a `Telescope.merge` source would be filed under the proxy's class rather than the entity's, so a row reading
the entity would not find it, and `into` would bind its setters to the proxy class. With this module, core resolves a
Hibernate proxy to its entity class through `HibernateProxy.getHibernateLazyInitializer().getPersistentClass()`, which
does not initialize it, so resolving a proxy never loads its row.

Hibernate is not a compile-time dependency of the module. Its two types are looked up by name once, and the resolver is
inactive where they are absent.

Without the module, core has no provider-specific code, and a proxy is read as its own proxy class. Reading a property
through a getter still works, since the proxy overrides each getter, but a merge source of a proxy is filed under the
proxy's class.

## How it plugs in

The module registers two providers through `ServiceLoader`, with a `provides` clause in its `module-info` and a
`META-INF/services` entry for the class path:

| Provider           | Core SPI                         | What it does                                                         |
| ------------------ | -------------------------------- | -------------------------------------------------------------------- |
| `LoadedContainers` | `ContainerCopy.Policy`           | shares a same-typed container the provider has not loaded            |
| `HibernateProxies` | `ProxyResolver` (in `conversion`) | resolves a Hibernate proxy to its entity class without initializing it |

At most one active `ContainerCopy.Policy` may be registered, so an application that registers a policy of its own
alongside this module is refused when the first container is copied, naming both. Several `ProxyResolver`s may be
registered; core asks them in turn.

## Cost

`LoadCheckBenchmark` measures what the policy adds to a copy. A container whose class is the JDK's own, such as an
`ArrayList`, is never a provider's lazy collection, so the policy copies it without asking; only a container of another
class pays for `isLoaded`.

## Native image

The proxy resolver binds its two calls as `LambdaMetafactory` functions on a JVM and as `MethodHandle` closures inside a
native image, the rule every runtime accessor in telescope follows. `:jpa:imageTest` runs the module's tests on the
closure substrate. The module's `native-image.properties` initializes its providers at build time, beside core's, and
the Jakarta Persistence utility at run time.
