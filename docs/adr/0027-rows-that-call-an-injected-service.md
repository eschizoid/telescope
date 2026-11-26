# ADR-0027: Rows that call an injected service

**Status:** Proposed · **Date:** 2026-11-26

## Context

A `compute` row calls a static method or a method of the source. The README prices a line with
`compute(OrderLine::getUnitPrice, Catalog::price)`, where `Catalog.price(LineDto)` is static. In a Spring or Quarkus
service the catalog is a bean, usually over a repository or a client, so a PATCH that prices lines from the database has
to price them by hand around the mapper. ADR-0018 left context parameters to an ADR of their own, and ADR-0026 left the
injected service to it.

Three facts shape the answer. A `@Bridge` mapper is a `static final` field, built when its class is initialised, before
any container exists, so the service cannot be in the field. The same mapper is called from places no container reaches:
a service method, a scheduled job, a unit test. And mappers nest: `EDITS` names `LINE` in a `via` row, so a service
`LINE` calls is a service `EDITS` needs.

The example was written first, on both starters and from a plain service method, and the decisions below are the ones
that keep it reading as it does in decision 8: the mapper field stays a `static final` declaration of rows, and the
handler stays one call.

## Decision

1. **A row names the service's class and a method of it.**

   ```java
   compute(OrderLine::getUnitPrice, Catalog.class, Catalog::price) // catalog.price(line)
   ```

   `Catalog::price` is an unbound reference to an instance method, typed as
   `Mapping.WithService<Catalog, LineDto, BigDecimal>`, a `Serializable` `BiFunction`. One rule covers every row that
   takes a service: its service form takes the service's class just before the function, and the function takes the
   service before whatever the plain row's function takes. The class literal earns its place three ways. It says at the
   row that the mapper cannot run alone, which a row reading exactly like the static form would hide. It fixes the
   service type for inference. And it gives the mapper its services as values, without decoding the reference, which a
   native image does not do. `services()` on a mapper lists them: those of its own rows, then those of each mapper its
   `via` rows name and each pair bridge it reaches, each class once.

2. **Rows whose function runs forward only, once per call, take a service.**

   | Row                   | Service form                                                                     | The function                            |
   | --------------------- | -------------------------------------------------------------------------------- | --------------------------------------- |
   | `compute`, a value    | `compute(OrderLine::getUnitPrice, Catalog.class, Catalog::price)`                | `(Catalog, LineDto) -> BigDecimal`      |
   | `compute`, a supplier | `compute(OrderLine::getAddedOn, Clock.class, Clock::instant)`                    | `(Clock) -> Instant`                    |
   | `when`'s predicate    | `when(Promotions.class, Promotions::applies, inner)`                             | `(Promotions, OrderChanges) -> boolean` |
   | `toOrElseGet`         | `toOrElseGet(LineDto::sku, OrderLine::getSku, Skus.class, Skus::next)`           | `(Skus) -> String`                      |
   | `toOneWay`            | `toOneWay(LineDto::sku, OrderLine::getProduct, Products.class, Products::bySku)` | `(Products, String) -> Product`         |

   `constant` takes none: its value is captured once, and a value a service gives is worked out per call, which is
   `compute`'s job. `via` takes none of its own: a conversion that needs a service is a mapper with a service row, which
   a `via` row names, and its services become the outer mapper's. The two-way `to(src, tgt, forward, backward)` and a
   `via` row naming a hand-written class take none, since both directions would need the service and no case calls for
   it. A method name with one form for each overload, as `Catalog` declaring both `price()` and `price(LineDto)`, is
   ambiguous to javac, as it is for a plain row; hold the one meant in a `static final` field.

3. **A bound mapper carries its services.** `with(services...)` on a `Mapper`, a `ForwardMapper` or an `EditMapper`
   returns a copy bound to the services given, of the same type; the mapper it is called on is unchanged. It refuses by
   name, before returning, a null, a value no service of the mapper accepts, two values for one service, and a service
   the mapper needs that it was not given, naming the row that needs it. A value is matched to a service by
   `isInstance`, so a container's proxy binds as the bean, and a request-scoped service binds as the scoped proxy both
   containers inject. So a bound mapper always runs. In an application on either starter, the container binds the mapper
   (decision 4) and user code never calls `with`; `with` is for code with no container and for tests.

   A call on a mapper that needs a service and has none bound, such as `OrderMappers.EDITS.patchInto(order, changes)` on
   the field itself, is refused before anything is read or written, never run with null:

   ```
   IllegalStateException: OrderMappers.EDITS needs a Catalog for compute(OrderLine::getUnitPrice, Catalog::price)
   in OrderMappers.LINE, and none is bound. Inject the EditMapper<OrderChanges, Order> bean, or bind one with
   OrderMappers.EDITS.with(catalog).
   ```

   On the runtime path `with` builds the mapper's conversion once more, with each service function closed over its
   service (`line -> catalog.price(line)`), so a bound mapper converts through the same composition, and writes through
   the same `ContainerCopy` and bean writers, as a mapper with a static `compute` row, and a call costs what that one
   costs. A conversion holding a service row belongs to its mapper and is never put in `DeepMap`'s per-pair cache.
   Binding costs one mapper build.

4. **Both starters register each such field as a bean of its own type, already bound.** For every `@Bridge` field whose
   rows name a service, its own or through a mapper it nests, the Spring and Quarkus starters register one bean whose
   type is the field's declared generic type, `EditMapper<OrderChanges, Order>`, `ForwardMapper<Order, OrderDto>` or
   `Mapper<Order, OrderDto>`, holding the field's mapper bound to the one bean of each service type. A handler or a
   service takes it the way it takes its repository, by constructor injection, and calls it:

   ```java
   OrderController(OrderRepository orders, EditMapper<OrderChanges, Order> edits) { ... }
   ```

   - **Found from what the processor recorded.** The field's generated provider records the declaring class, the field's
     name, its declared type and its service classes, and hands over the field's value, so neither starter scans classes
     or reads the field reflectively. Spring's auto-configuration registers a bean definition per field, typed by that
     generic type, from the providers `ServiceLoader` finds; the Quarkus deployment reads the providers from the index
     and produces a synthetic bean per field with the same type. A field the processor never saw has no provider and no
     bean, and is bound with `with`.
   - **Resolved once, at startup.** Each bean is a singleton built at startup: Spring builds it with the other
     singletons, and the Quarkus extension creates each one when the application starts rather than on first use. No
     bean of a service type, or two with none primary, fails startup with the field, the row and the service named, and
     the two bean names, in words `EndpointMappers` holds for both starters. The Quarkus deployment marks the service
     beans it looks up as unremovable.
   - **Matched by type, then by the field's name.** An injection point of the field's generic type takes the field's
     bean. The bean is named, and qualified, by the field as its class writes it, `OrderMappers.EDITS`: a Spring bean
     name usable with `@Qualifier`, a CDI `@Named`. One declaration per pair is allowed in a compilation, so two beans
     of one type come only from two modules; then an unqualified injection point fails with the container's own
     ambiguity error, which lists both names, and `@Qualifier("OrderMappers.EDITS")` or `@Named("OrderMappers.EDITS")`
     picks one. Two fields giving one name fail startup with both declaring classes named.
   - **The registry and the endpoints use the same bean.** A field bean of type `Mapper<A, B>` is a `Mapper` bean, so
     `TelescopeMapperRegistry` collects it under its pair like any other, and the registry's refusal of two `Mapper`
     beans for one pair applies. `EditMapper` and `ForwardMapper` beans are not `Mapper`s and stay out of the registry,
     which stays keyed by pair and holds `Mapper`s only. A mapped endpoint whose lookup reaches a field with services,
     through the registry or through the generated bridge the forward fallback finds, uses that field's bean, and a
     generated `@TelescopeMapper` bean whose bridge has service rows injects it, so each container binds each field
     once. A `Mapper` bean the application declares itself is the application's to bind, and one still unbound fails
     startup with the method and the row named.

   Nothing is bound process-wide: the binding lives in the container's bean, and two application contexts in one JVM
   each hold their own.

5. **The generated path.** `FieldRows` reads a service form when its class argument is a class literal and its function
   is a reference to an instance method of that class or a type it extends, a reference to a static method taking the
   service first, or a `static final` field holding the function; an inline lambda is refused by name on the row, as on
   every row. Each operation of the generated bridge takes the services after its own arguments, in the order
   `services()` lists them, as `forward(LineDto source, Catalog catalog)`, and calls `catalog.price(source)` directly.
   The class the processor writes for the field holds the bound services in final fields and passes them on, `with`
   returns another instance of it through the same `BridgeGrant` constructor, and an unbound instance refuses before
   calling the bridge. A bridge hands a nested bridge the services it needs, and `BridgeFn` gains a form taking them, so
   a runtime mapper that reaches the pair hands over its own. The generated provider records the field's service classes
   with its row kinds, so a call naming other services runs the runtime mapper, as for any rows that differ.
   `CrossPathCorpusTest` gains each service form under each operation, bound; the unbound refusal in the same words on
   both paths with nothing written; each refusal `with` makes; a service reached through a mapper a `via` row names; and
   one service shared by two rows. No difference between the paths is introduced, so nothing joins ADR-0019's registers.

6. **Native images, the verifier and `explain()`.** Nothing is looked up reflectively beyond the container's own bean
   lookup by type. A service is a value handed to `with`, the generated bridge calls its method directly, and the
   service classes come from class literals, so the runtime path decodes a service function only to check the class it
   takes after the service, which it skips inside a native image as it does for a `compute` row reading the source.
   Quarkus removes a bean no injection point names, so the deployment reads the service classes the processor recorded
   from the index and keeps those beans; a service only a mapper built at run time needs is the application's to keep,
   and the startup refusal names `@Unremovable`. `MapperVerifierProcessor` holds a service function to the rule its
   plain row's function follows, and checks a `with` call on a `@Bridge` field it can resolve, the static type of each
   argument against the field's services, refusing a missing, extra or repeated service at compile time; every other
   binding is checked when `with` runs. `explain()` names the service and whether one is bound:

   ```
   OrderLine.unitPrice: computed by Catalog.price(LineDto), Catalog bound
   OrderLine.unitPrice: computed by Catalog.price(LineDto), no Catalog bound
   ```

   It says bound, not injected, because a mapper cannot tell a container's value from a test's.

7. **ADR-0026 and ADR-0024.** `EditMapper` gains `with` and `services()` as the other mappers do. This fills the gap
   ADR-0026's decision 3 leaves and changes nothing it decides: a `compute` row of an edit mapper still takes the source
   of its pair, and with the service form it also takes the service. The two can land in either order; the README below
   needs both. ADR-0024's `over(path, fn)` edits take no service here. If an edit needs one, its service form follows
   the same rule, `over(path, Normalizer.class, Normalizer::apply)`, and is accepted only in a mapper's `before` and
   `after` rows, where the mapper's `with` binds it, since `Telescope.all` has nothing to bind it to.

8. **The README's opening example after ADR-0026 and this ADR.** The entities, `OrderChanges`, `OrderDto` and `DTO` are
   unchanged. The catalog is a bean over the product table:

   ```java
   @Service // @ApplicationScoped on Quarkus
   public class Catalog {

     private final ProductRepository products;

     public Catalog(ProductRepository products) {
       this.products = products;
     }

     public BigDecimal price(LineDto line) {
       return products.bySku(line.sku()).getPrice();
     }

     public BigDecimal cost(LineDto line) {
       return products.bySku(line.sku()).getCost();
     }
   }
   ```

   The mapper fields stay `static final` declarations of rows; only `LINE`'s two `compute` rows change:

   ```java
   public final class OrderMappers {

     // A line as a PATCH sends it. The price and the cost come from the catalog bean.
     @Bridge
     public static final EditMapper<LineDto, OrderLine> LINE = edits(
       LineDto.class,
       OrderLine.class,
       compute(OrderLine::getUnitPrice, Catalog.class, Catalog::price),
       compute(OrderLine::getCostPrice, Catalog.class, Catalog::cost),
       drop(LineDto::unitPrice), // a price the client sends is ignored
       keep(OrderLine::getId)
     );

     // A PATCH: how an OrderChanges is written into an Order. It needs a Catalog, through LINE.
     @Bridge
     public static final EditMapper<OrderChanges, Order> EDITS = edits(
       OrderChanges.class,
       Order.class,
       via(OrderChanges::lines, Order::getLines, LINE),
       keep(Order::getId), // the fields a PATCH may never change
       keep(Order::getNumber),
       keep(Order::getCustomer),
       keep(Order::getInternalNotes)
     );
   }
   ```

   On Spring, the controller takes `EDITS` the way it takes its repository, and the handler stays one call:

   ```java
   @RestController
   class OrderController {

     private final OrderRepository orders;
     private final EditMapper<OrderChanges, Order> edits;

     OrderController(OrderRepository orders, EditMapper<OrderChanges, Order> edits) {
       this.orders = orders;
       this.edits = edits; // OrderMappers.EDITS, bound to the Catalog bean
     }

     @TelescopePatchMapping(value = "/orders/{number}", to = OrderDto.class)
     Order change(@PathVariable String number, @RequestBody OrderChanges changes) {
       final var order = orders.findByNumber(number);
       edits.patchInto(order, changes); // writes only the fields the client sent, pricing new lines
       return order;
     }
   }
   ```

   On Quarkus the resource reads the same:

   ```java
   @Path("/orders")
   public class OrderResource {

     private final Orders orders;
     private final EditMapper<OrderChanges, Order> edits;

     OrderResource(Orders orders, EditMapper<OrderChanges, Order> edits) {
       this.orders = orders;
       this.edits = edits;
     }

     @PATCH
     @Path("/{number}")
     @Transactional
     @TelescopeResponse(to = OrderDto.class)
     public Order change(@PathParam("number") String number, OrderChanges changes) {
       final var order = orders.byNumber(number);
       edits.patchInto(order, changes);
       return order;
     }
   }
   ```

   Called from a service method outside any endpoint, the mapper is injected the same way. A test with no container
   binds it with `with`:

   ```java
   @Service
   public class OrderService {

     private final OrderRepository orders;
     private final EditMapper<OrderChanges, Order> edits;

     public OrderService(OrderRepository orders, EditMapper<OrderChanges, Order> edits) {
       this.orders = orders;
       this.edits = edits;
     }

     @Transactional
     public Order change(String number, OrderChanges changes) {
       final var order = orders.findByNumber(number);
       edits.patchInto(order, changes);
       return order;
     }
   }

   final var service = new OrderService(orders, OrderMappers.EDITS.with(new Catalog(products))); // in a test
   ```

   The demo's `Catalog` becomes a bean over a products table in each app, and each app hands the framework-free
   `OrderDesk` the bound mapper it injects.

## Consequences

- A mapper can price, look up or allocate through a bean, on both paths, and its field still declares only rows. The
  README and the demo price lines from an injected catalog on Spring and on Quarkus.
- In an application on either starter a mapper with services is injected like any other bean, names no service at the
  injection point, and needs no change there when a row gains a service later: the bean binds the new one, or startup
  fails naming the row.
- The injected form needs `telescope-codegen` on the processor path, which a `@Bridge` field already wants: a field the
  processor never saw has no bean, and the container reports the missing one by type. Such a field is bound with `with`.
- Each such field adds one bean to the container, of a mapper type the application declared itself.
- A call on an unbound field is refused when it runs, not when it compiles: nothing at compile time follows the field
  into every method that calls it.
- The PATCH prices every line it writes, a matched one included, so the catalog is asked once per line. A catalog that
  caches or batches its lookups does so on its own side.
- A generated bridge whose pair has service rows takes them as arguments, and `BridgeFn` grows a form for them; a pair
  with none generates as today.
- The claim that a bound call costs what a static `compute` row costs is held by a `ServiceRowBenchmark` in the
  `Benchmarks` workflow, with the static row as the control and allocation under `-Pjmh.profilers=gc` as the durable
  figure.

## Alternatives considered

- **The service inferred from the reference, `compute(tgt, Catalog::price)`, with no class literal.** javac can infer it
  from an exact reference. Rejected: the row then reads exactly like a static call, so nothing at the row says the
  mapper needs a service, the services could be known only by decoding the reference, which a native image does not do,
  and a class with a static and an instance form of the name would bind to whichever the overloads pick.
- **A context parameter type**, `edits(LineDto.class, OrderLine.class, PricingContext.class, ...)` with
  `patchInto(order, changes, context)`. Rejected: every call site carries the context, and the context is a class that
  exists only to carry services to the mapper.
- **A `services(Catalog.class)` head row.** Rejected: it repeats at the head the class the row's function already names,
  and the function still needs the class to be typed.
- **A process-wide binding the starters install**, so `OrderMappers.EDITS.patchInto(...)` runs unchanged. Rejected: it
  is a service locator in a static field. Two application contexts in one JVM, as a test suite's context cache keeps,
  would share it and the last one would win without a word, and tests would bind and unbind global state.
- **A binding scoped to the endpoint's call**, a `ScopedValue` the starter sets around each handler. Rejected: the same
  line would run in a handler and fail in a scheduled job or on another thread, at run time rather than at startup.
- **The services passed on every call**, `EDITS.patchInto(order, changes, catalog)`. Rejected: every call site carries
  them, and a service a nested mapper needs shows at the outer call.
- **`with` in each constructor**, `this.edits = OrderMappers.EDITS.with(catalog)`. Rejected as the container form: each
  class names the services the rows need, injects a catalog it does not otherwise use, and changes when a row gains a
  service. It stays for code with no container and for tests.
- **A binder bean**, `mappers.bind(OrderMappers.EDITS)`. Rejected: it puts a telescope type and a binding line in each
  constructor, where the field's bean needs neither.
- **A configuration class or producer method generated as source for each field.** Rejected: the processor would have to
  know at compile time which container the application runs, which it decides today only for `@TelescopeMapper` beans,
  and it would repeat what the provider already records. The starters register the bean from the provider instead, the
  same way on both containers.
