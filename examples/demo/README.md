# The order-service demo

A small shop sells mugs, T-shirts, caps and tote bags. Its order service is a REST API over a database: customers place
orders, pay for them, the warehouse ships them, and an order that has not shipped can be cancelled. The service refuses
what a shop would refuse: an unpaid order does not ship, a paid order no longer changes, and a shipped order is not
cancelled.

The business rules fit on one page. What takes the code is moving an order between its shapes. The same order is a JPA
entity holding the cost of every item, the payment provider's card token and the warehouse's notes; the public JSON,
which must show none of them; the mobile app's older JSON, which names half its fields differently and wants money as
text; a partner's feed, where every value arrives as a string; a PATCH body that must change only what it sends; and an
invoice that no one object holds. Hand-written, that is a page of getters and setters per shape, and each one drifts the
day someone adds a field. This demo writes each shape's difference as a few typed rows and lets the build check them.

A request's path: `PATCH /orders/SO-1001` JSON → `OrderChanges` → `OrderState` → the `Order` entity, and back out as an
`OrderDto`.

The service runs three ways over the same code:

| Module                         | What it is                                                                     | Run it                                        |
| ------------------------------ | ------------------------------------------------------------------------------ | --------------------------------------------- |
| [`domain`](domain/)            | the entities, the DTOs, the business rules and the service's mappers           | (a library)                                   |
| [`spring`](spring/README.md)   | the REST API on Spring Boot 4, with JPA over H2, through the Spring starter    | `./gradlew :examples:demo:spring:bootRun`     |
| [`quarkus`](quarkus/README.md) | the same REST API on Quarkus 3, with the generated CDI beans, orders in memory | `./gradlew :examples:demo:quarkus:quarkusDev` |
| [`native`](native/README.md)   | the service as one command, built as a GraalVM native image; the CI gate       | `./gradlew :examples:demo:native:nativeRun`   |

Then try it:

```bash
curl -s localhost:8080/orders/SO-1001
curl -s -X POST localhost:8080/orders/SO-1001/ship   # 409: an unpaid order does not ship
```

The service's mappers are `@Bridge` fields in
[`OrderMappers`](domain/src/main/java/io/github/eschizoid/telescope/demo/order/OrderMappers.java) and
[`RequestMappers`](domain/src/main/java/io/github/eschizoid/telescope/demo/api/RequestMappers.java). A row names only
what differs between two types; fields of the same name map without one, nested objects and lists included.
`telescope-codegen` compiles each field into a plain Java class at build time. A few mappers run on the runtime path
instead, each for a reason given where it appears: the import's trace, the Spring app's customer cards, and the native
module's coverage of shapes the service does not use.

## The three hops

A request crosses three layers, and each crossing has its own mapping problems. They are listed here in the order a
request meets them.

### 1. REST ↔ service: wire DTOs, and the bodies mapped on the way in and out

**The public API hides the cost price, the card token and the warehouse's notes.** The entity holds all three. The DTO
has no field for any of them, and a `drop` row says the notes stay behind even if the DTO gains a field of that name.
Each line maps through its own field, and the payment through one field per case of the sealed `Payment`:

```java
@Bridge
public static final ForwardMapper<OrderLine, OrderLineDto> LINE = mapperForward(
  OrderLine.class,
  OrderLineDto.class,
  drop(OrderLine::getCostPrice)
);

@Bridge
public static final ForwardMapper<Card, CardDto> CARD = mapperForward(Card.class, CardDto.class, drop(Card::token));

@Bridge
public static final ForwardMapper<BankTransfer, BankTransferDto> BANK = mapperForward(
  BankTransfer.class,
  BankTransferDto.class,
  toOneWay(BankTransfer::iban, BankTransferDto::accountEnding, Masks::lastFour)
);
```

**The customer's name lives on another entity.** `path` reads it through the order's customer:
`to(path(Order::getCustomer, Customer::getName), OrderDto::customerName)`.

**An order converts itself.** The processor writes an `OrderMappings` interface with one method per field whose source
is an order. `Order implements OrderMappings`, so code that holds an order writes `order.toOrderDto()`,
`order.toOrderSummary()` or `order.toOrderV2Dto()`.

**The list endpoint returns summaries, and the handler returns entities.** On Spring, `@TelescopeGetMapping` names the
response type and the starter maps the value where Spring MVC writes the body, so the handler does not convert it:

```java
@TelescopeGetMapping(value = "/orders", to = OrderSummary.class)
List<Order> list() {
  return orders.list();
}
```

**The mobile app speaks version 2, and phones that never update keep calling it.** The app calls the customer the
`buyer` and a discount code a `coupon`, which it expects never to be null. It reads JSON numbers as floating point,
which would round a price, so version 2 sends money as text; it shows the date as it arrives, so the date is text in the
app's format; and it shows tags as labels, so they are a set. The v2 response is another field over the same entity:

```java
@Bridge
public static final ForwardMapper<Order, OrderV2Dto> V2 = mapperForward(
  Order.class,
  OrderV2Dto.class,
  to(path(Order::getCustomer, Customer::getName), OrderV2Dto::buyer),
  toOrElse(Order::getDiscountCode, OrderV2Dto::coupon, "NONE"),
  toFormatted(Order::getPlacedOn, OrderV2Dto::placedOn, "dd.MM.yyyy"),
  compute(OrderV2Dto::total, Pricing::totalText),
  drop(Order::getInternalNotes)
);
```

Each line's price is formatted with `toFormattedNumber(OrderLine::getUnitPrice, OrderLineV2Dto::price, "0.00")`. The
status converts from the enum to its name, the quantity widens from `int` to `long`, and the tags turn from a list into
a set, each by name and without a row.

The app's cart sends its own request shape too: the buyer's email, each item as `product` and `qty`, and a `coupon`. It
maps to the one request the service places orders from, the items through two hand-written methods named in a `via` row:

```java
@Bridge
public static final Mapper<PlaceOrderV2, PlaceOrder> V2 = mapper(
  PlaceOrderV2.class,
  PlaceOrder.class,
  to(PlaceOrderV2::buyerEmail, PlaceOrder::customerEmail),
  to(PlaceOrderV2::coupon, PlaceOrder::discountCode),
  via(PlaceOrderV2::items, PlaceOrder::lines, ItemsV2::forward, ItemsV2::backward)
);
```

On Spring the handler takes the service's type, and `@TelescopeRequestBody` names the type on the wire:

```java
@TelescopePostMapping(value = "/v2/orders", to = OrderV2Dto.class)
ResponseEntity<Order> placeV2(@TelescopeRequestBody(from = PlaceOrderV2.class) final PlaceOrder command)
```

**A partner sends orders as untyped JSON, every value written as text.** `@FromMap` on `ImportedOrder` generates
`ImportedOrderFromMap`, which binds the parsed map with no reflection. Every field has the name the service uses, so
`RequestMappers.IMPORT` has no rows: the built-in conversions turn `"2"` into an `int`, `"EXPRESS"` into the `Delivery`
enum and `"true"` into a `boolean`. Partners also type SKUs by hand, so a `@TelescopeTransformer` cleans them before the
mapping, and a `@TelescopeMapper` interface runs both. The processor writes each as a Spring bean or a CDI bean, and the
import handler calls it:

```java
@TelescopeTransformer
public interface SkuCleanup extends TelescopeTransformation<ImportedOrder, String> {
  default Telescope<ImportedOrder, String> path() {
    return Telescope.of(ImportedOrder.class).each(ImportedOrder::lines).field(ImportedLine::sku);
  }

  default Transformation<String> transform() {
    return new Transformation<>(null, (sku) -> sku.strip().toUpperCase(Locale.ROOT));
  }
}

@TelescopeMapper(from = ImportedOrder.class, to = PlaceOrder.class, transformers = SkuCleanup.class)
public interface OrderImports {
  PlaceOrder map(ImportedOrder imported);
}
```

**A partner's order is refused, and they need to know why.** The import's 400 response carries the `trace()` of
[`ImportTrace`](domain/src/main/java/io/github/eschizoid/telescope/demo/api/ImportTrace.java), the same binding built at
run time, which says for each field the key it read and the value it took. A quantity sent as `qty` shows up as a line
with `quantity=null`, next to the refusal `line CAP-03 has a quantity of 0`.

### 2. Service: domain records, patches, merges and computed values

**A PATCH changes only what the client sent.** The service reads the order's state out of the entity as an `OrderState`
record, lays the client's `OrderChanges` over it with `patch`, which keeps every field the client left null, and writes
the result back. The fields a client may never change are dropped by name on the `CHANGES` mapper, so a field added to
`OrderState` later fails the build until someone decides whether a PATCH may change it:

```java
final OrderState current = order.toOrderState();
final OrderState wanted = OrderMappers.CHANGES.patch(current, changes);
OrderMappers.STATE.into(order, wanted);
```

**An order goes through payment and shipping, and the rules hold at each step.** `POST /orders/{n}/pay` takes payment
for a placed order, `ship` hands a paid order to the carrier, and `cancel` cancels any order that has not shipped. An
unpaid order does not ship, a paid order no longer changes, and a shipped order is not cancelled; each refusal is a 409.
These rules are plain Java in
[`OrderDesk`](domain/src/main/java/io/github/eschizoid/telescope/demo/order/OrderDesk.java).

**The tracking number is shown only once the order has shipped.** The warehouse prints the carrier's label when an order
is paid, which reserves the tracking number, but the parcel may wait a day. `when` gates the row, and the row writes
into the nested `shipping` object through the navigator `@Focus` generates on `OrderDto`:

```java
when(OrderRules::shipped, to(Order::getTrackingNumber, OrderDtoTelescope.of().shipping().trackingNumber()))
```

**The total is computed from the lines, never stored.** `compute(OrderDto::total, Pricing::total)` fills the total from
the whole order on every conversion, so it cannot drift from the lines.

**No one object holds an invoice.** The number and date come from the order, the billing name from the customer, the
seller's details from configuration and the amounts from the pricing rules. `merge` builds it from all four. The
customer is the lazy association the order holds, and a Hibernate proxy is filed under the entity class it stands for,
so it reaches the rows that read `Customer`:

```java
@Bridge
public static final Mapper<Sources, InvoiceDto> INVOICE = Telescope.merge(
  InvoiceDto.class,
  from(Order::getNumber, InvoiceDto::number),
  from(Order::getPlacedOn, InvoiceDto::issuedOn),
  from(Customer::getName, InvoiceDto::billTo),
  from(Customer::getEmail, InvoiceDto::email),
  auto(Seller.class),
  auto(Totals.class)
);
```

### 3. Service ↔ entity: JPA, collection identity and the bidirectional association

**Each line points back at its order, and only `addLine` sets that pointer.** `Order` has no setter for its lines.
`writeBean(Order.class, ADDERS)` fills them one element at a time through `addLine`, and a `via` row prices each item
from the catalog, so a client never sends a price:

```java
@Bridge
public static final ForwardMapper<PlaceOrder, Order> PLACE = mapperForward(
  PlaceOrder.class,
  Order.class,
  writeBean(Order.class, ADDERS),
  via(PlaceOrder::lines, Order::getLines, CatalogLines.class),
  drop(PlaceOrder::customerEmail)
);
```

**A PATCH must update the entity the persistence context manages, and keep its list of lines.**
`STATE.into(order, state)` writes into the entity in place. It clears the list the entity already holds and refills it
through `addLine`, so Hibernate's orphan removal deletes the old rows and the new lines get their foreign key:

```java
@Bridge
public static final Mapper<OrderState, Order> STATE = mapper(
  OrderState.class,
  Order.class,
  writeBean(Order.class, ADDERS),
  via(OrderState::lines, Order::getLines, CatalogLines.class)
);
```

**An order's customer is loaded only when something reads it.** On Spring the association is lazy, so the order holds a
Hibernate proxy until then. The support team's `GET /orders/{n}/customer` reads that proxy into a `CustomerCard` through
a mapper with no `@Bridge`, on the runtime path, which reads it as the `Customer` it stands for and loads the row once.
The invoice's `merge` files the same proxy under `Customer` because [`telescope-jpa`](../../jpa/README.md), which the
Spring starter brings, resolves a Hibernate proxy to the entity class it stands for. The same module leaves a lazy
collection Hibernate has not loaded as it is wherever a mapping only copies it. The
[Spring app's README](spring/README.md) has the details.

## The build checks the mappers

`telescope-codegen` refuses a broken row at compile time, with the message the runtime would give.
[`VerifierTest`](domain/src/test/java/io/github/eschizoid/telescope/demo/VerifierTest.java) compiles two of the demo's
mappers with one row taken out. The PATCH mapper without `drop(OrderState::payment)` fails with
`source field 'payment' has no same-name target field`, and the version 2 mapper without the `buyerEmail` rename fails
naming `buyerEmail`.

## Where each feature is tested

| Feature                                                              | Test                                                   |
| -------------------------------------------------------------------- | ------------------------------------------------------ |
| `to`, `path`, a navigator path, `compute`, `when`, `drop`            | `OrderDeskTest`, both apps' `OrderApiTest`, `OrderRun` |
| `toOrElse`, `toFormatted`, `toFormattedNumber`, built-in conversions | `OrderDeskTest`, both apps' `OrderApiTest`             |
| `via` with methods and with a class, sealed cases                    | `OrderDeskTest`, both apps' `OrderApiTest`, `OrderRun` |
| `merge`, with a lazy customer proxy among the sources                | `OrderDeskTest`, both apps' `OrderApiTest`             |
| `OrderMappings` (`order.toOrderDto()`)                               | every test                                             |
| `patch`, `into` with `ADDERS`                                        | `OrderDeskTest`, both apps' `OrderApiTest`, `OrderRun` |
| the order lifecycle and its 409s                                     | `OrderDeskTest`, both apps' `OrderApiTest`, `OrderRun` |
| `@FromMap`, `trace()` in the import's 400                            | `OrderDeskTest`, both apps' `OrderApiTest`, `OrderRun` |
| `explain()`                                                          | `OrderDeskTest`, `OrderRun`                            |
| `@TelescopeMapper`, `@TelescopeTransformer`                          | both apps' `OrderApiTest`                              |
| `@TelescopeGetMapping`, `@TelescopeRequestBody`                      | the Spring app's `OrderApiTest`                        |
| a runtime mapper over a lazy Hibernate proxy                         | the Spring app's `CustomerProxyTest`                   |
| the compile-time verifier                                            | `VerifierTest`                                         |
