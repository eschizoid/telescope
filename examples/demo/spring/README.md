# The order service on Spring Boot

The shop's order API with its orders in a database. Here the mapping meets JPA: the `Order` entity's lines are a
collection Hibernate manages and deletes orphans from, each line points back at its order, and the customer is a lazy
association that stays a proxy until something reads it. A PATCH has to update that managed entity in place, and a
response has to be built from it without leaking what only the shop may see.

A request's path: `POST /v2/orders` JSON → `PlaceOrderV2` → `PlaceOrder` → the `Order` entity in H2, and back out as an
`OrderV2Dto`.

```bash
./gradlew :examples:demo:spring:bootRun
```

```bash
curl -s localhost:8080/orders/SO-1001
```

The app is the [order-service demo](../README.md) on Spring Boot 4, with its orders in an in-memory H2 database through
Spring Data JPA, and `telescope-spring-boot-starter` on the classpath. Two orders are loaded at startup: `SO-1001`,
placed and unpaid, and `SO-1002`, paid and shipped. The service's mappers live in the shared [`domain`](../domain/)
module; this app adds what is Spring's.

## The three hops, on Spring

### 1. REST ↔ service

[`OrderController`](src/main/java/io/github/eschizoid/telescope/demo/spring/OrderController.java) returns entities from
most handlers, and the starter's mapping annotations name what the client receives. The value is mapped where Spring MVC
writes the body, so those handlers do not convert it, and a Java caller of the method still gets the entity:

```java
@TelescopeGetMapping(value = "/orders", to = OrderSummary.class)
List<Order> list() {
  return orders.list();
}

@TelescopePostMapping(value = "/orders/{number}/ship", to = OrderDto.class)
Order ship(@PathVariable final String number) {
  return orders.ship(number);
}
```

Two handlers convert by hand. `GET /orders/{number}` returns `orders.get(number).toOrderDto()`, through the entity's
generated `OrderMappings`. The partner import calls
[`OrderImports`](src/main/java/io/github/eschizoid/telescope/demo/spring/OrderImports.java), a `@TelescopeMapper`
interface, which runs [`SkuCleanup`](src/main/java/io/github/eschizoid/telescope/demo/spring/SkuCleanup.java), a
`@TelescopeTransformer`, and then the import's bridge. `telescope-codegen` writes both as Spring components. When the
service refuses an imported order, the handler answers with a problem response whose `trace` property says how each
field of the payload was read.

The mobile app's cart sends version 2's request shape. `@TelescopeRequestBody` reads the body as `PlaceOrderV2`, through
Spring's own `@RequestBody` machinery, and maps it to the `PlaceOrder` the service takes before the handler runs:

```java
@TelescopePostMapping(value = "/v2/orders", to = OrderV2Dto.class)
ResponseEntity<Order> placeV2(@TelescopeRequestBody(from = PlaceOrderV2.class) final PlaceOrder command) {
  return created("/v2/orders/", orders.place(command));
}
```

The starter finds each mapper by its pair at startup: here, the bridges generated from the `@Bridge` fields in the
domain module, which sit in the package of their source.

### 2. Service

[`OrderService`](src/main/java/io/github/eschizoid/telescope/demo/spring/OrderService.java) runs each of the shared
`OrderDesk`'s operations in a transaction. The PATCH, the lifecycle rules, the invoice and the computed totals are the
domain's, unchanged; [`ApiErrors`](src/main/java/io/github/eschizoid/telescope/demo/spring/ApiErrors.java) turns a
refused step into a 409 and an unknown order into a 404.

### 3. Service ↔ entity

`Order` and its lines are JPA entities, the lines a `@OneToMany(mappedBy = "order", orphanRemoval = true)` that only
`addLine` fills. A PATCH runs `EDITS.patchInto(order, changes)` on the entity the persistence context manages, which
writes only the fields the client sent and refills the list Hibernate already holds through `addLine`, so the old rows
are deleted and each new line gets its foreign key.

The order's customer is `@ManyToOne(fetch = LAZY)`, so until something reads it the order holds a Hibernate proxy, a
generated subclass of `Customer`.
[`CustomerCards`](src/main/java/io/github/eschizoid/telescope/demo/spring/CustomerCards.java) maps a `CustomerCard` and
the `Customer` entity both ways: `POST /customers` signs a customer up through `forward`, and `GET /orders/{n}/customer`
reads the order's customer back into a card through `backward`. The field has no `@Bridge`, because a sign-up or a
support lookup does not need generated code, so it runs on the runtime path, which is where every mapper can start. That
path reads the proxy through getters looked up on `Customer` itself and loads the row once.

The starter brings [`telescope-jpa`](../../../jpa/README.md), and with JPA on the classpath it does two things here. It
resolves the customer proxy to `Customer`, which is what lets the invoice's `merge` find it: a merge files each source
under its class, and without the module the proxy is filed under its generated subclass, so
`from(Customer::getName, InvoiceDto::billTo)` would find no customer. And wherever a mapping only copies a collection
whose type is the same on both sides, it leaves one Hibernate has not loaded as it is instead of loading it to copy it.
The order list does not need that: a mapper reads only the properties its target declares, so the summaries read the
customer and the lines their total comes from, and never the tags.

`spring.jpa.open-in-view` stays on in [`application.yml`](src/main/resources/application.yml) because a mapped response
is converted after the handler returns, when the lazy customer, lines and tags are read.

## Try it

```bash
curl -s localhost:8080/orders                       # summaries, mapped from the entities
curl -s localhost:8080/v2/orders/SO-1001            # version 2: buyer, coupon, dates and money as text
curl -s localhost:8080/orders/SO-1001/invoice       # merged from the order, customer, seller and totals
curl -s localhost:8080/orders/SO-1002/customer      # the lazy customer, read into a card

curl -s -X PATCH localhost:8080/orders/SO-1001 -H 'Content-Type: application/json' \
  -d '{"delivery":"EXPRESS","lines":[{"sku":"BAG-04","quantity":3}]}'

curl -s -X POST localhost:8080/orders/SO-1001/ship  # 409: an unpaid order does not ship
curl -s -X POST localhost:8080/orders/SO-1001/pay   # paid; the tracking number is reserved, not shown
curl -s -X POST localhost:8080/orders/SO-1001/ship  # shipped; now the tracking number shows
curl -s -X POST localhost:8080/orders/SO-1001/cancel  # 409: a shipped order is not cancelled

curl -s localhost:8080/v2/orders -H 'Content-Type: application/json' \
  -d '{"buyerEmail":"grace@example.com","items":[{"product":"TEE-02","qty":2}],"coupon":"VIP25"}'

curl -s localhost:8080/customers -H 'Content-Type: application/json' \
  -d '{"name":"Edsger Dijkstra","email":"edsger@example.com"}'

curl -s localhost:8080/orders/import -H 'Content-Type: application/json' \
  -d '{"customerEmail":"grace@example.com","giftWrap":"true","lines":[{"sku":" cap-03 ","quantity":"2"}]}'

curl -s localhost:8080/orders/import -H 'Content-Type: application/json' \
  -d '{"customerEmail":"grace@example.com","lines":[{"sku":"CAP-03","qty":"2"}]}'
```

The last call is refused with a 400, and its `trace` shows why: the partner sent `qty` instead of `quantity`, so the
line was read with `quantity=null`.

## Tests

```bash
./gradlew :examples:demo:spring:test
```

- [`OrderApiTest`](src/test/java/io/github/eschizoid/telescope/demo/spring/OrderApiTest.java) starts the app on a random
  port and calls every endpoint above over HTTP, checking the response bodies, the lifecycle's 409s and, after a PATCH,
  what the database holds.
- [`CustomerProxyTest`](src/test/java/io/github/eschizoid/telescope/demo/spring/CustomerProxyTest.java) checks that the
  order's customer is an uninitialised proxy, and that the card mapper's `backward` over it gives the customer's values
  and loads the row once.
- [`MapperLoggingTest`](src/test/java/io/github/eschizoid/telescope/demo/spring/MapperLoggingTest.java) turns a mapper's
  logger up through Logback and checks that its structure, and then each conversion's values, reach the log.
- [`substrate/HibernateSubstrateTest`](src/test/java/io/github/eschizoid/telescope/demo/spring/substrate/HibernateSubstrateTest.java)
  is coverage of telescope's runtime path over Hibernate's own classes, which the service's generated mappers never
  reach, over small entities of its own: a proxy unwrapped to its persistent class without loading it, `backward` over a
  loaded `PersistentSet` and `PersistentMap`, a value cycle across a `PersistentBag` back-edge, and a path that hops
  from records to beans through a sealed bridge and narrows to one case.
