# telescope-examples-springboot

End-to-end demos: **Spring Boot 4.0.1 + Jackson + Hibernate 7 + H2 + telescope**, running on JDK 25. Three modules,
three stories — pick whichever matches your situation.

| Module                 | Story                                                                                                   | Pick when                                                                                             |
| ---------------------- | ------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| **`order-jpa/`**       | telescope as the record↔entity mapping engine across a realistic e-commerce domain with deep nesting    | You want to see telescope handle a wide surface: JPA, deep records, validation, bulk patches, mappers |
| **`product-starter/`** | `telescope-spring-boot-starter` auto-wires every `Mapper<A, B>` bean into a single dispatching registry | You want zero-config wiring and one `TelescopeMapperRegistry` to dispatch by `(source, target)` pair  |
| **`invoicing/`**       | `@Bridge`-annotated record↔bean pairs get conversion classes emitted at compile time, with deep recursion into other user-declared bridges | You want zero-reflection compile-time-bound conversion; no `Telescope.mapper(...)` call anywhere |

All three modules are standalone — each has its own build, depends on `io.github.eschizoid:telescope*` from
Maven Central, and is intentionally **not** part of the main telescope build, so they exercise telescope the way a real
downstream consumer would: as versioned artifacts, not sibling subprojects.

---

## `order-jpa/` — the e-commerce showcase

A real enterprise stack with telescope handling the record↔entity conversion between the API layer and the persistence
layer. Multiple controllers on the same `Order` domain demonstrate the runtime DSL, the codegen-emitted holders,
accumulating validation, and bulk patch application — pick the angle that matches your case.

## What it shows

The same `Order` domain record graph round-trips through Jackson → telescope → Hibernate → telescope → Jackson, with two
interchangeable mapper implementations:

| Path                              | Implementation                                                                                  | When to pick it                                                                    |
| --------------------------------- | ----------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| `POST /orders/runtime`            | `Telescope.mapper(Order.class, OrderEntity.class, Mapping.to(...), Mapping.via(...), ...)`      | Fewer LOC; method-reference accessors; no codegen generation cost                  |
| `POST /orders/codegen`            | Hand-rolled `forward()` / `backward()` on top of the `@Focus` / `@BeanFocus`-emitted holders    | Maximum predictability; zero reflective bookkeeping at runtime                     |
| `POST /orders/validated`          | `Telescope.of(Order.class).each(...).field(...).updateValidated(...)` + `@RestControllerAdvice` | Accumulating per-line-item errors into one 400 payload — not first-failure-wins    |
| `POST /orders/{id}/bulk-update`   | `Telescope.all(over(path1, fn), over(path2, fn), ...)` folded into one reusable normaliser      | Apply N field patches in one structural pass on a loaded order                     |
| `POST /orders/{id}/inspect`       | `read` / `find` / `count` / `exists` terminals on a path described in the request body          | Debug / admin / GraphQL-style introspection over the live order graph              |
| `GET  /orders/{id}/redacted`      | `Telescope.from(Order.class).to(RedactedOrder.class).using(forward, backward)` — lossy one-way  | Project a stored order into a narrower public view (mask PII), reject reverse      |
| `GET  /orders/{id}/partner-label` | `Mapper<Order, PartnerShippingLabel>.forward(...)` — full mapper-driven projection              | Hand a partner system the shape it expects, derived from one mapper definition     |
| `PATCH /orders/{id}/from-partner` | `Mapper<Order, PartnerShippingLabel>.patch(existing, partial)` — sparse overlay                 | Accept partner-side updates, apply only non-null fields back onto the stored order |

All four flows reuse the same `OrderRepository` (Spring Data JPA) and the same `OrderEntity` graph.

### Domain shape — wide enough to exercise the deep-mapping surface

```
Order (record)                  OrderEntity (@Entity)
├── id: Long                    ├── id: @Id @GeneratedValue Long
├── orderNumber: String         ├── orderNumber: String
├── customer: Customer  ─────►  ├── customer: @ManyToOne CustomerEntity
├── shippingAddress: Address    ├── shippingAddress: @Embedded AddressEmbeddable
├── billingAddress: Address     ├── billingAddress: @Embedded AddressEmbeddable
├── lineItems: List<LineItem>   ├── lineItems: @OneToMany List<LineItemEntity>
└── giftWrap: Optional<Address> └── giftWrap: @Embedded AddressEmbeddable (nullable)

Customer (record)               CustomerEntity (@Entity)
├── id: Long                    ├── id: @Id @GeneratedValue Long
├── name: String                ├── name: String
└── email: String               └── email: String

LineItem (record)               LineItemEntity (@Entity)
├── sku: String                 ├── sku: String
├── quantity: int               ├── quantity: int
└── unitPrice: BigDecimal ────► └── unitPriceCents: long
                                                      (typed transform — 19.99 ↔ 1999)
```

### Telescope capabilities demonstrated

- **`Telescope.mapper(A, B, Mapping... rows)`** — the runtime factory, used in `RuntimeOrderMappers`.
- **`Mapping.to(srcAcc, tgtAcc)`** — same-typed correspondence (mostly inferred via auto-mapping).
- **`Mapping.to(srcAcc, tgtAcc, fwd, bwd)`** — **typed transform** for `BigDecimal ↔ long-cents`.
- **`Mapping.via(srcAcc, tgtAcc, nestedMapper)`** — compose sub-mappers (Customer, Address, LineItem) into the top-level
  Order mapper.
- **`Mapping.drop(srcAcc)` / `Mapping.drop(srcAcc, targetClass)`** — declare a source field intentionally NOT mapped to
  the target. `partnerLabelMapper` uses both: top-level `drop(Order::metadata)` keeps internal metadata off the partner
  DTO, and nested `drop(Customer::tags, PartnerCustomer.class)` keeps Customer's internal tag set off the partner-facing
  customer shape — the recursion hits `(Customer, PartnerCustomer)` and the scoped drop fires there.
- **`WriteHint.writeBean(Class, SETTERS)`** — pin the bean write strategy to no-arg-ctor + setters (required for
  Hibernate-managed identity assignment).
- **`Mapper.forward(...)` / `Mapper.backward(...)`** — both directions from one definition.
- **`Mapper.patch(existing, partial)`** — sparse overlay; only non-null fields from `partial` land on `existing`. Powers
  the `PATCH /orders/runtime/{id}` endpoint.
- **`Telescope.of(Order.class).field(Order::customer).field(Customer::email).update(...)`** — deep update through two
  levels of nesting; used in both controllers to lowercase the email pre-write.
- **`Telescope.all(overIfPresent(...), mapIfPresent(...))`** — sparse-PATCH composition with no if-ladder; powers the
  `POST /orders/{id}/bulk-update` endpoint.
- **`@Focus` / `@BeanFocus`** — annotation-driven codegen that emits `<X>Path<R>` navigators and `<X>Telescope` metadata
  holders. Consumed inline in `CodegenOrderMappers`.
- **`Optional<Address>`, `List<LineItem>`, `Map<String, String>`, `Set<String>` cardinality** — recurse through the
  runtime factory without special-casing; `Order.metadata` shows the Map auto-lift end-to-end with `@ElementCollection`,
  `Customer.tags` does the same for Set auto-lift + the typed `SetPath.each()` terminal.
- **Hibernate LAZY-proxy unwrap** — `OrderEntity.customer` is `@ManyToOne(fetch = LAZY)`. When telescope's
  `Mapper.backward(...)` reads a `HibernateProxy`, it forces a single initialization fetch (counted via Hibernate's
  `Statistics.getEntityFetchCount()`) and resolves the persistent class via `Beans.persistentClassOf(...)`. Pinned by
  `OrderCustomerLazyFetchTest`.
- **Sealed-narrow after a paradigm hop** — `Order.payment: sealed Payment` (record-side,
  `CreditCard | PayPal | BankTransfer`) bridges to `legacy.PaymentEntity` (bean-side, mirror sealed hierarchy) via
  `PaymentMappers.paymentBridge()`. The flagship chain
  `Telescope.of(Order.class).field(Order::payment).then(paymentBridge()).as(CreditCardEntity.class).field(CreditCardEntity::getCardNumber).update(...)`
  crosses records → sealed bridge → prism narrow → bean-getter field in one expression. Each `.field(...)` re-resolves
  its FieldOptics dispatch per accessor declaring-class — record-side vs bean-side never confused. Pinned by
  `SealedNarrowAfterParadigmHopTest`.

## Running

Prereq: **JDK 25** on `PATH`. The Gradle wrapper handles the Gradle distribution.

```bash
# from this directory
./gradlew bootRun
```

Then in a second terminal:

```bash
# Create an order through the runtime path
curl -s -X POST http://localhost:8080/orders/runtime \
  -H 'Content-Type: application/json' \
  -d '{
    "orderNumber": "ORD-2026-0001",
    "customer": {"name": "Alice Example", "email": "ALICE@example.com"},
    "shippingAddress": {"street": "100 Main St", "city": "Brooklyn", "state": "NY", "zip": "11201"},
    "billingAddress":  {"street": "200 Billing Ave", "city": "Brooklyn", "state": "NY", "zip": "11201"},
    "lineItems": [
      {"sku": "SKU-A", "quantity": 2, "unitPrice": 19.99},
      {"sku": "SKU-B", "quantity": 1, "unitPrice": 49.50}
    ],
    "giftWrap": {"street": "300 Gift Rd", "city": "Brooklyn", "state": "NY", "zip": "11201"}
  }' | jq

# Same payload, codegen path
curl -s -X POST http://localhost:8080/orders/codegen \
  -H 'Content-Type: application/json' \
  -d '{...}' | jq

# PATCH only the order number (runtime path)
curl -s -X PATCH http://localhost:8080/orders/runtime/1 \
  -H 'Content-Type: application/json' \
  -d '{"orderNumber": "ORD-PATCHED"}' | jq

# Re-normalise emails on an existing order (codegen path showcases the deep-update fast path)
curl -s -X POST http://localhost:8080/orders/codegen/normalise-emails/1 | jq
```

## Running the integration tests

```bash
./gradlew test
```

Two test classes, `RuntimeOrderFlowTest` and `CodegenOrderFlowTest`, drive the full Spring Boot context against an H2
in-memory database and assert that the JSON ↔ record ↔ entity round-trip preserves every nested value.

## What you should learn from this

1. **Records + Jackson + telescope compose cleanly.** Jackson handles JSON ↔ record; telescope handles record ↔ entity.
   No copy constructors anywhere. No reflection in the hot path on the codegen route.

2. **Bidirectional mapping is genuinely useful in production.** One `Telescope.mapper(...)` definition feeds both the
   POST (forward to entity, save) and the GET (load entity, backward to record). With MapStruct you'd write two separate
   `@Mapper` interfaces.

3. **The runtime path is honest about what it costs.** First call to `Telescope.mapper(A, B, ...)` builds the cached
   pair; subsequent calls are O(1) dispatch through the holder constants. The codegen path makes that cost explicit and
   visible in source.

4. **Hibernate doesn't fight telescope.** The bean side handles `@Entity` POJOs the same way it handles plain POJOs — no
   JPA awareness in the library, just standard `getX()`/`setX()` conventions. `@Embeddable` works without ceremony;
   `@OneToMany` cascades along the natural list-of-children mapping.

5. **You can pick your trade-off per call site.** The two flows share a domain and an entity graph verbatim; only the
   mapper layer varies. Add or swap call sites without restructuring anything else.

---

## `product-starter/` — the auto-wired-registry showcase

A minimal Spring Boot app demonstrating `telescope-spring-boot-starter`: drop `@Bean Mapper<A, B>` declarations into
your config, the starter's `TelescopeMapperRegistry` auto-discovers them and indexes by `(sourceClass, targetClass)`.
The controller looks up mappers at request time via `registry.get(Product.class, Target.class)` — one source class,
multiple target shapes, picked by a runtime parameter.

### Endpoints

| Path                           | Target                             | Strategy                                        |
| ------------------------------ | ---------------------------------- | ----------------------------------------------- |
| `POST /products?view=record`   | `Product` (record canonical)       | No write — record returned as-is                |
| `POST /products?view=dto`      | `ProductDto` (Lombok `@Data`)      | `writeBeans(SETTERS)` — Lombok setters          |
| `POST /products?view=manifest` | `ProductManifest` (immutable POJO) | `writeBean(ProductManifest.class, CONSTRUCTOR)` |
| `GET  /products/{id}?view=...` | Same three shapes                  | Picked by `view` parameter via registry         |
| `GET  /products/{id}/manifest` | `ProductManifest` (always)         | Dedicated endpoint for the immutable view       |

### Per-class write strategy (the headline capability)

The three target shapes need three different reconstruction strategies. Telescope handles this with one global default
plus per-class overrides:

```java
// productEntityMapper: Hibernate-managed bean — needs no-arg + setters
Telescope.mapper(Product.class, ProductEntity.class, writeBeans(SETTERS))

// productDtoMapper: Lombok @Data — same strategy as the JPA entity
Telescope.mapper(Product.class, ProductDto.class, writeBeans(SETTERS))

// productManifestMapper: immutable POJO — no setters, no no-arg ctor; CONSTRUCTOR is the only option
Telescope.mapper(Product.class, ProductManifest.class, writeBean(ProductManifest.class, CONSTRUCTOR))
```

If `writeBean(Class, STRATEGY)` were ignored and the global default applied, the manifest mapper would fail eagerly at
`Telescope.mapper(...)` construction time because the SETTERS probe can't find the right shape. The per-class hint wins.

### Telescope capabilities demonstrated

- **`telescope-spring-boot-starter` auto-config** — drop `@Bean Mapper<A, B>` declarations in any `@Configuration`, the
  starter discovers them and populates `TelescopeMapperRegistry`. No `@TelescopeMapper`-style annotation magic.
- **`registry.get(Source.class, Target.class)`** — runtime lookup by type pair. The controller doesn't inject specific
  mapper beans — it dispatches polymorphically, which is the pattern that scales when the app has dozens of mappers.
- **`writeBeans(STRATEGY)` global + `writeBean(Class, STRATEGY)` per-target override** — one mapper can mix
  reconstruction strategies when a single target hierarchy has both mutable and immutable shapes.
- **Lombok + Jackson + telescope coexistence** — `@Data` synthesises setters used by `SETTERS`, `@JsonProperty` renames
  fields on the wire without affecting the Java identifiers telescope reads. `telescope-lombok` emits
  `ProductDtoPath<R>` against the same property surface for compile-time-bound navigation.

---

## `invoicing/` — the `@Bridge` codegen showcase

A pure compile-time-bound demo: zero `Telescope.mapper(...)` calls anywhere. Both endpoint pairs route through
`BridgeProcessor`-emitted classes that the user never wrote — direct method calls, no `SerializedLambda` decode, no
runtime field-name probe, no reflective getter/setter dispatch.

### Endpoints

| Path                              | Generated machinery                                                                          |
| --------------------------------- | -------------------------------------------------------------------------------------------- |
| `POST /invoices/lines/forward`    | `InvoiceLinePath.start().asInvoiceLineEntity().read(line)` — navigator hop generated by `@Bridge` |
| `POST /invoices/lines/backward`   | `InvoiceLineBridge.backward(entity)` — generated static method                              |
| `POST /invoices/headers/forward`  | `InvoiceHeaderBridge.forward(header)` — auto-recurses into `InvoiceLineBridge` for the list |
| `POST /invoices/headers/backward` | `InvoiceHeaderBridge.backward(entity)` — same in reverse                                    |

### Telescope capabilities demonstrated

- **`@Bridge(Target.class)`** — annotation-driven codegen emits a `<Source>Bridge` class with a `BRIDGE` constant
  (`Telescope<Source, Target>`) plus static `forward(...)` / `backward(...)` methods. The bijection rule requires the
  source and target expose the same field-name set; types match exactly or via a generated identity.
- **Bridge hop on the navigator** — when a `@Focus`-annotated record also carries `@Bridge`, its emitted
  `<Source>Path<R>` gains an `as<TargetSimpleName>()` method. Because the target is `@BeanFocus`-navigable, the hop
  returns a typed continuation (`InvoiceLineEntityPath<R>`) instead of a terminal `Telescope<R, Target>`. Navigation
  keeps reading like a sentence after the paradigm hop.
- **Deep recursion through user-declared bridges** — `InvoiceHeader` carries `List<InvoiceLine>`. The parent
  `InvoiceHeaderBridge` auto-emits a list-lift that delegates per element to the user-declared `InvoiceLineBridge`
  rather than synthesising its own anonymous Iso. The user owns each sub-bridge by name.
- **Zero `Telescope.mapper(...)` at runtime** — every conversion in this submodule is a direct method call on a
  generated class. Compile-time bound, IDE-navigable, no reflection in the hot path.
