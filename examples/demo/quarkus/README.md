# The order service on Quarkus

The same order API on Quarkus, with the orders kept in memory. The service, its rules and its mappers are the shared
domain's, unchanged; what changes is the container. The partner import's SKU cleanup and mapping are interfaces that
`telescope-codegen` writes as CDI beans here and as Spring beans in the Spring app.

A request's path: `PATCH /orders/SO-1001` JSON → `OrderChanges` → `OrderState` → the `Order` entity, and back out as an
`OrderDto`.

```bash
./gradlew :examples:demo:quarkus:quarkusDev
```

```bash
curl -s localhost:8080/orders/SO-1001
```

The app is the [order-service demo](../README.md) on Quarkus 3, with Quarkus REST and Jackson, and `telescope-quarkus`
on the classpath. It serves the same endpoints as the [Spring app](../spring/README.md) over the same
[`domain`](../domain/) module, and keeps its orders in memory, so the two orders it starts with come back on every
restart.

## The three hops, on Quarkus

### 1. REST ↔ service

Quarkus has no mapped response bodies, so each resource converts the entity itself, through the `OrderMappings`
interface the processor generates for `Order`:

```java
@GET
@Path("/{number}")
public OrderDto get(@PathParam("number") final String number) {
  return desk.get(number).toOrderDto();
}
```

[`OrderV2Resource`](src/main/java/io/github/eschizoid/telescope/demo/quarkus/OrderV2Resource.java) takes the version 2
request as it arrives and maps it with the same field the Spring app's `@TelescopeRequestBody` finds:

```java
@POST
public Response place(final PlaceOrderV2 request) {
  final var order = desk.place(RequestMappers.V2.forward(request));
  return Response.created(URI.create("/v2/orders/" + order.getNumber())).entity(order.toOrderV2Dto()).build();
}
```

[`OrderImports`](src/main/java/io/github/eschizoid/telescope/demo/quarkus/OrderImports.java) and
[`SkuCleanup`](src/main/java/io/github/eschizoid/telescope/demo/quarkus/SkuCleanup.java) are the same `@TelescopeMapper`
and `@TelescopeTransformer` interfaces as on Spring. With `telescope-quarkus` on the classpath, `telescope-codegen`
writes them as `@ApplicationScoped` CDI beans, and
[`OrderResource`](src/main/java/io/github/eschizoid/telescope/demo/quarkus/OrderResource.java) injects them with
`@Inject`.

### 2. Service

[`OrderBeans`](src/main/java/io/github/eschizoid/telescope/demo/quarkus/OrderBeans.java) produces the shared
`OrderDesk`, the seller from `application.properties`, and loads the sample orders at startup. The PATCH, the lifecycle
rules, the invoice and the computed totals are the domain's, unchanged; `ApiErrors` turns a refused step into a 409. A
refused import is answered with a 400 whose `trace` says how each field of the payload was read.

### 3. Service ↔ entity

The entities are the same classes the Spring app persists, kept here by `InMemoryOrders`, so there are no proxies and no
lazy loading. A PATCH still writes into the entity in place with `STATE.into`, refilling the list of lines it holds
through `addLine`, and the same test checks it.

## Try it

The `curl` calls in the [Spring app's README](../spring/README.md#try-it) work here unchanged, the lifecycle calls
included, except two that belong to the Spring app: `/customers` and `/orders/{n}/customer`.

## Tests

[`OrderApiTest`](src/test/java/io/github/eschizoid/telescope/demo/quarkus/OrderApiTest.java) is a `@QuarkusTest`: it
starts the app and calls each endpoint over HTTP with REST Assured.

```bash
./gradlew :examples:demo:quarkus:test
```

The root project has a `:quarkus` module of its own, so this one sets a group of its own in its build file; Gradle tells
two projects apart by group and name.
