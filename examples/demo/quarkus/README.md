# The order service on Quarkus

The same order API on Quarkus, with the orders kept in memory. The service, its rules and its mappers are the shared
domain's, unchanged; what changes is the container. The partner import's SKU cleanup and mapping are interfaces that
`telescope-codegen` writes as CDI beans here and as Spring beans in the Spring app.

A request's path: `PATCH /orders/SO-1001` JSON → `OrderChanges` → the `Order` entity, and back out as an `OrderDto`.

```bash
./gradlew :examples:demo:quarkus:quarkusDev
```

```bash
curl -s localhost:8080/orders/SO-1001
```

The app is the [order-service demo](../README.md) on Quarkus 3, with Quarkus REST and Jackson, and `telescope-quarkus`
on the classpath. It serves the endpoints of the [Spring app](../spring/README.md), and the warehouse's shipping label,
over the same [`domain`](../domain/) module, and keeps its orders in memory, so the two orders it starts with come back
on every restart.

## The three hops, on Quarkus

### 1. REST ↔ service

A resource method returns the entity the desk gave it, and `@TelescopeResponse` beside `@GET` names the DTO the client
receives. `telescope-quarkus` maps the value where Quarkus REST writes the body, so the method holds no conversion code
and a Java caller still gets the `Order`:

```java
@PATCH
@Path("/{number}")
@TelescopeResponse(to = OrderDto.class)
public Order change(@PathParam("number") final String number, final OrderChanges changes) {
  return desk.change(number, changes);
}
```

[`OrderV2Resource`](src/main/java/io/github/eschizoid/telescope/demo/quarkus/OrderV2Resource.java) serves the mobile
app's shapes. The body is read as the `PlaceOrderV2` the app sends and mapped, through the same field the Spring app's
`@TelescopeRequestBody` finds, to the `PlaceOrder` the desk takes before the method runs; the created order leaves as an
`OrderV2Dto`:

```java
@POST
@TelescopeResponse(to = OrderV2Dto.class)
public RestResponse<Order> place(@TelescopeRequestBody(from = PlaceOrderV2.class) final PlaceOrder command) {
  final var order = desk.place(command);
  return RestResponse.ResponseBuilder.<Order>created(URI.create("/v2/orders/" + order.getNumber()))
    .entity(order)
    .build();
}
```

`GET /orders/{number}` converts otherwise, through the `OrderMappings` interface the processor generates for `Order`, as
on Spring: `desk.get(number).toOrderDto()`. A misconfigured method, such as one that returns a bare `Response`, stops
the app at boot with the method named.

[`OrderImports`](src/main/java/io/github/eschizoid/telescope/demo/quarkus/OrderImports.java) and
[`SkuCleanup`](src/main/java/io/github/eschizoid/telescope/demo/quarkus/SkuCleanup.java) are the same `@TelescopeMapper`
and `@TelescopeTransformer` interfaces as on Spring. With `telescope-quarkus` on the classpath, `telescope-codegen`
writes them as `@ApplicationScoped` CDI beans, and
[`OrderResource`](src/main/java/io/github/eschizoid/telescope/demo/quarkus/OrderResource.java) injects them with
`@Inject`.

The partner's JSON text is read by telescope-jackson's `JsonBinder`, as on Spring. The binder needs only `jackson-core`
3, so it runs beside the Jackson 2 that Quarkus 3 brings. telescope-jackson's `TelescopeModule` is a Jackson 3 databind
module, so this app does not install it, and the customer endpoints the Spring app serves through it are not here.

### 2. Service

[`OrderBeans`](src/main/java/io/github/eschizoid/telescope/demo/quarkus/OrderBeans.java) produces the shared
`OrderDesk`, the seller from `application.properties`, and loads the sample orders at startup. The PATCH, the lifecycle
rules, the invoice and the computed totals are the domain's, unchanged; `ApiErrors` turns a refused step into a 409. A
refused import is answered with a 400 whose `trace` says how each field of the payload was read.

### 3. Service ↔ entity

The entities are the same classes the Spring app persists, kept here by `InMemoryOrders`, so there are no proxies and no
lazy loading. A PATCH still writes into the entity in place with `EDITS.patchInto`, refilling the list of lines it holds
through `addLine`, and the same test checks it.

## Try it

The `curl` calls in the [Spring app's README](../spring/README.md#try-it) work here unchanged, the lifecycle calls
included, except three that belong to the Spring app: `/customers`, `/orders/{n}/customer` and `/orders/dashboard`,
whose point is what it does not load from the database.

## Tests

[`OrderApiTest`](src/test/java/io/github/eschizoid/telescope/demo/quarkus/OrderApiTest.java) is a `@QuarkusTest`: it
starts the app and calls each endpoint over HTTP with REST Assured.
[`OrderApiIT`](src/native-test/java/io/github/eschizoid/telescope/demo/quarkus/OrderApiIT.java) makes the same calls on
the app built as a native executable, which needs GraalVM's `native-image`. The shipping label is the call that needs
the service providers: its mapping is a `@Bridge` field in the warehouse's own package,
[`shop.warehouse`](src/main/java/shop/warehouse/ShippingLabels.java), which the image initializes at run time, as it
does an application's classes, so the label names its recipient only when the image finds the generated bridge's
provider; `OrderApiIT` also checks the line the label logs at startup that says the provider is registered. The
extension registers what telescope's own code needs there: its service providers, the method references of `@Bridge`
field rows and of the generated transformer beans, and the types the mapped bodies are read and written as. The app
registers the rest itself: `application.properties` indexes the shared domain module, so the build can walk the DTOs
down to the records they hold, and `OrderResource` registers `ImportedOrder` and `ImportedLine`, which the refused
import's trace reads through a runtime `fromMap` mapper:

```bash
./gradlew :examples:demo:quarkus:test
./gradlew :examples:demo:quarkus:testNative -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false
```

Inside this build telescope-core is a local project, which Quarkus would load with the app's own classes while it loads
the extension with the dependencies; `application.properties` loads it parent first so both see one copy, as they do
with the published jar.

The root project has a `:quarkus` module of its own, so this one sets a group of its own in its build file; Gradle tells
two projects apart by group and name.
