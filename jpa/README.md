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
row that also holds a `List<Book>`, is copied rather than handed across. Copying iterates the source, and iterating a
lazy `@OneToMany` or `@ElementCollection` loads it: one query per entity inside a session, and
`LazyInitializationException` outside one. On a list endpoint that maps a page of entities, that is one query for the
page and one more for every row.

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

What a row then holds is the entity's own collection, so changing the row's list changes the entity's, and the provider
writes that change to the database at the next flush where the entity owns the association. Reading it inside the
session loads it, as reading the entity's property would; reading it after the session has closed throws, as reading the
entity's property would. The module changes when a collection loads, not whether reading it can fail.

What it does not change:

- **A collection whose elements convert is still read.** `List<OrderLine>` mapped to `List<OrderLineDto>` builds a new
  list of converted elements, which needs the elements. A mapper reads only the properties its target declares, so a
  collection the target has no property for is never read in the first place, with or without this module.
- **`into` reads the collection the entity holds.** Writing a container into an entity that already holds one writes
  into that instance, so a managed `orphanRemoval` collection keeps its identity, and the elements it held are read
  first so that a write that fails can put them back. A held collection that cannot be read, such as a lazy one outside
  its session, is replaced through the setter instead, and refused by the property's name where there is no setter.

## A managed collection is merged by identifier

`into` and `patchInto` writing a collection of entities into an entity that holds one merge it by identifier: a sent
element whose identifier matches a held element is written into that element in place, a new one is added, and a held
element nothing matches is removed. A row that did not change is not written.

```java
public record OrderForm(Long id, List<LineForm> lines) {}
public record LineForm(Long id, String sku, int quantity) {}

static final Mapper<OrderForm, Order> EDIT = Telescope.mapper(OrderForm.class, Order.class, writeBean(Order.class, ADDERS));

final Order order = orders.findById(id).orElseThrow();
EDIT.into(order, form);
// A line sent as it is held:    no statement.
// A line with a new quantity:    one UPDATE of that line, its other columns as they were.
// A line the form leaves out:    one DELETE, through orphan removal.
// A line sent with no id:        one INSERT, added through Order.addLine.
```

Without the module, the same `into` clears the order's lines and adds a new instance per sent line: every line the order
held is deleted and every sent one inserted, and a sent line carrying the id of a held one fails at flush, since it is a
new instance with the identifier of a managed one.

- **The identifier is the converted element's.** The entity's `@Id` or `@EmbeddedId`, on a field or a getter, read
  through its getter from the element the conversion built, so whatever the element conversion writes into it, by name
  or through a row, is what is matched. A sent element with no identifier is new, and so is one whose primitive
  identifier, a `long id`, holds 0; a boxed `Long` holding 0 is an identifier. An identifier the collection does not
  hold is new where the entity assigns its identifiers, and refused by name where it is generated, by `@GeneratedValue`
  or by a Hibernate generator annotation such as `@UuidGenerator`.
- **Refused before anything is written.** One identifier sent twice is refused, and so are two elements sent with
  different identifiers that convert into equal values of a set, which would keep one and delete the other's row. To
  find those, each sent element is converted a second time when a converted set comes out smaller than what was sent, so
  a `compute` row or a converter with an effect of its own runs twice for it. Two new elements, sent with no identifier,
  that convert equal are not refused: the set keeps one and drops the other, as `forward` does without the module. The
  check runs before the first write, through every level of collections, so a refusal in an element's own collection
  leaves every element above it as it was. A collection nothing has loaded yet is loaded by that check, so its refusals
  come first too.
- **Order and sets.** A list ends in the order the elements were sent. A collection that already holds the merged
  elements in that order is not touched, so an unchanged collection leaves its owner clean. A set whose element changed
  its `hashCode` is refilled with the same instances, so it finds each one again. A collection that is refilled is dirty
  to the provider, so on a `@Version` owner a reordered list or a rehashed set costs one UPDATE of the owner's version;
  on a set mapped through a join column, Hibernate also writes the moved element's join column again.
- **It needs a conversion that can write in place.** That is a nested pair the mapper converts property by property, or
  the mapper of a `via(..., Mapper)` row, on both paths. Elements converted by a `via` converter class or method pair,
  whose written properties are the hand-written code's own, are cleared and refilled as without the module, and so are
  elements handed across unchanged, a `Map`, and elements that are not entities. On a managed owner, a converter that
  sets the identifier of a held element still fails at flush there, as it does today; map the elements with a mapper to
  merge them.
- **A collection that cannot be read is not merged.** A lazy collection outside its session is written through the
  setter, as without the module. Any other failure to load it, such as a database error, reaches the caller.

The generated bridge merges the same way, through the same policy: each generated bridge carries the conversion of one
element, its check and its write in place, and a bridge converting a collection of those elements hands it to
`ContainerCopy`. `MergeByIdTest` and `MergeByIdEdgesTest` count the statements Hibernate issues on both paths.

## A lazy proxy is read as its entity

A lazy `@ManyToOne` is a runtime subclass of the entity until something reads it. Reflection over that subclass sees the
proxy: a `Telescope.merge` source would be filed under the proxy's class rather than the entity's, so a row reading the
entity would not find it, and `into` would bind its setters to the proxy class. With this module, core resolves a
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

| Provider           | Core SPI                          | What it does                                                           |
| ------------------ | --------------------------------- | ---------------------------------------------------------------------- |
| `LoadedContainers` | `ContainerCopy.Policy`            | shares an unloaded container, merges a collection of entities by `@Id` |
| `HibernateProxies` | `ProxyResolver` (in `conversion`) | resolves a Hibernate proxy to its entity class without initializing it |

At most one active `ContainerCopy.Policy` may be registered, so an application that registers a policy of its own
alongside this module is refused when the first container is copied, naming both. Several `ProxyResolver`s may be
registered; core asks them in turn.

## Cost

`LoadCheckBenchmark` measures what the policy adds to a copy. A container whose class is the JDK's own, such as an
`ArrayList`, is never a provider's lazy collection, so the policy copies it without asking: on the measured run that
cost about 1 ns and no allocation. A container of another class pays for `isLoaded`, which with Hibernate as the
provider took about 22 ns and allocated 64 bytes per call, whatever the collection's size. Where the collection has not
been loaded, that call replaces the query loading it would issue.

## Native image

The proxy resolver binds its two calls as `LambdaMetafactory` functions on a JVM and as `MethodHandle` closures inside a
native image, the rule every runtime accessor in telescope follows. `:jpa:imageTest` runs the module's tests on the
closure substrate. The module's `native-image.properties` initializes its providers at build time, beside core's, and
the Jakarta Persistence utility at run time.
