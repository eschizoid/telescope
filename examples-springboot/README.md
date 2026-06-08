# telescope-examples-springboot

End-to-end demo: **Spring Boot 4.0.1 + Jackson + Hibernate 7 + H2 + telescope**, running on JDK 25. A real enterprise
stack, with telescope handling the record↔entity conversion between the API layer and the persistence layer. Two
parallel mapper implementations of the same domain show the trade-off between the runtime DSL and the codegen-emitted
holders.

This project is **standalone** — its own `settings.gradle.kts`, its own Gradle wrapper, depends on
`io.github.eschizoid:telescope` from Maven Central. It is intentionally **not** part of the main telescope build, so it
exercises telescope the way a real downstream consumer would: as a versioned artifact, not a sibling subproject.

## What it shows

The same `Order` domain record graph round-trips through Jackson → telescope → Hibernate → telescope → Jackson, with two
interchangeable mapper implementations:

| Path                   | Implementation                                                                               | When to pick it                                                   |
| ---------------------- | -------------------------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| `POST /orders/runtime` | `Telescope.mapper(Order.class, OrderEntity.class, Mapping.to(...), Mapping.via(...), ...)`   | Fewer LOC; method-reference accessors; no codegen generation cost |
| `POST /orders/codegen` | Hand-rolled `forward()` / `backward()` on top of the `@Focus` / `@BeanFocus`-emitted holders | Maximum predictability; zero reflective bookkeeping at runtime    |

Both flows reuse the same `OrderRepository` (Spring Data JPA) and the same `OrderEntity` graph.

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
- **`WriteHint.writeBean(Class, SETTERS)`** — pin the bean write strategy to no-arg-ctor + setters (required for
  Hibernate-managed identity assignment).
- **`Mapper.forward(...)` / `Mapper.backward(...)`** — both directions from one definition.
- **`Mapper.patch(existing, partial)`** — sparse overlay; only non-null fields from `partial` land on `existing`. Powers
  the `PATCH /orders/runtime/{id}` endpoint.
- **`Telescope.of(Order.class).field(Order::customer).field(Customer::email).update(...)`** — deep update through two
  levels of nesting; used in both controllers to lowercase the email pre-write.
- **`@Focus` / `@BeanFocus`** — annotation-driven codegen that emits `<X>Path<R>` navigators and `<X>Telescope` metadata
  holders. Consumed inline in `CodegenOrderMappers`.
- **`Optional<Address>` and `List<LineItem>` cardinality** — recurse through the runtime factory without special-casing.

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
