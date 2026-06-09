# telescope-examples-springboot

End-to-end demos: **Spring Boot 4.0.1 + Jackson + Hibernate 7 + H2 + telescope**, running on JDK 25. Four standalone
submodules, each one focused on one paradigm or feature. Pick the one that matches what you're evaluating.

## At a glance

| Module                 | Paradigm              | Headline                                                                                  |
| ---------------------- | --------------------- | ----------------------------------------------------------------------------------------- |
| **`product-starter/`** | runtime + auto-config | `telescope-spring-boot-starter` discovers every `Mapper<A, B>` bean into a typed registry |
| **`org-chart/`**       | runtime + JPA cycles  | Self-referencing record↔entity pair with bidirectional Hibernate cycle severance          |
| **`invoicing/`**       | codegen               | `@Bridge`-emitted conversion classes — zero `Telescope.mapper(...)` calls anywhere        |
| **`order-jpa/`**       | mixed (kitchen sink)  | Realistic e-commerce stack — eight endpoints, every telescope angle on one `Order` domain |

All four modules are standalone — each has its own build file, depends on `io.github.eschizoid:telescope*` from Maven
Central, and is intentionally **not** part of the main telescope build. They exercise telescope the way a real
downstream consumer would: as versioned artifacts, not sibling subprojects.

**Where to start:** if you're new, read `product-starter/` first (smallest, most idiomatic). If you're trying to decide
between runtime and codegen, read `org-chart/` (pure runtime) and `invoicing/` (pure codegen) back-to-back — the
contrast is the point. If you want to see telescope across a wide feature surface, `order-jpa/` is the kitchen sink.

---

## Runtime modules

The runtime path uses `Telescope.mapper(...)` and `Telescope.of(Class).field(Lambda)` — the lambda resolves field names
via `SerializedLambda`, then dispatches through a cached `LambdaMetafactory` substrate. First call builds the type-pair
Iso; subsequent calls are O(1). No annotation processors required.

### `product-starter/` — the auto-wired registry

A minimal Spring Boot app demonstrating `telescope-spring-boot-starter`: drop `@Bean Mapper<A, B>` declarations into
your `@Configuration`, and the starter's `TelescopeMapperRegistry` auto-discovers them and indexes by
`(sourceClass, targetClass)`. The controller looks up mappers at request time via `registry.get(Product.class, T.class)`
— one source, many target shapes, dispatched polymorphically.

**Endpoints**

| Path                           | What it shows                                                                |
| ------------------------------ | ---------------------------------------------------------------------------- |
| `POST /products?view=record`   | Source returned as-is (no mapping)                                           |
| `POST /products?view=dto`      | Lombok `@Data` DTO via `writeBeans(SETTERS)`                                 |
| `POST /products?view=manifest` | Immutable POJO via `writeBean(ProductManifest.class, CONSTRUCTOR)`           |
| `GET  /products/{id}?view=...` | Same three shapes, picked by query parameter via the registry                |
| `GET  /products/{id}/manifest` | Dedicated read-only endpoint that always renders the immutable manifest view |

**What lands**

- Spring Boot autoconfig fires, `TelescopeMapperRegistry` indexes all three `@Bean Mapper<Product, ?>` beans
- `registry.get(Source.class, Target.class)` for runtime polymorphic dispatch
- `writeBeans(SETTERS)` global default + `writeBean(Class, CONSTRUCTOR)` per-target override — one mapper can mix
  reconstruction strategies for targets with different shapes (mutable, immutable, builder-driven)
- Lombok `@Data` + Jackson `@JsonProperty` + telescope coexist on the same DTO

This submodule is **intentionally codegen-free**: no `@Focus`, no `@BeanFocus`, no `telescope-lombok` Path emission.
Codegen lives in `invoicing/`.

### `org-chart/` — self-referencing JPA cycles

A single `Mapper<Employee, EmployeeEntity>` against a Hibernate-managed bidirectional self-reference: every employee has
a `manager` (`@ManyToOne`) and a list of `reports` (`@OneToMany mappedBy = "manager"`). Together they form a literal
value-level cycle once Hibernate populates both sides — `bob.manager == alice && alice.reports.contains(bob)`.

**Endpoints**

| Path                   | What it shows                                                                          |
| ---------------------- | -------------------------------------------------------------------------------------- |
| `POST /org-chart`      | Forward-map the record to entity, cascade-persist on the manager chain                 |
| `GET  /org-chart/{id}` | Load, touch both directions inside the transaction, backward-map. Cycle severs cleanly |

**What lands**

- **Type-level cycle resolution at construction** — `DeepMap.populateIso` reserves the `(Employee, EmployeeEntity)`
  `TypePair` cache slot before recursing into auto-derived component Isos. The inner recursion finds the parent slot
  already reserved and short-circuits. No stack overflow at `Telescope.mapper(...)` time.
- **Value-level cycle severance at `mapper.backward(...)`** — Hibernate stitches the bidirectional graph on hydration.
  DeepMap's per-traversal `IdentityHashMap` seen-set returns `null` on re-entry into the same instance; `null` lifts
  cleanly to `Optional.empty()` via `Iso.liftOptionalToNullable(...)` using `Optional.ofNullable`. The top-level record
  materialises with first-level associations intact; deeper back-pointers collapse to empty.
- `spring.jpa.open-in-view=false` — production hygiene. The transactional boundary is the controller, not the view.

---

## Codegen module

The codegen path emits direct-call `*Path<R>`, `*Bridge`, and `*Telescope` classes at compile time from `@Focus`,
`@BeanFocus`, and `@Bridge` annotations. Every navigation step is a direct method call on a generated class — no
`SerializedLambda` decode, no runtime field-name probe, no reflective getter/setter dispatch.

### `invoicing/` — `@Bridge`-driven conversion

A pure compile-time-bound demo: zero `Telescope.mapper(...)` calls anywhere. Two record↔bean pairs (`InvoiceLine` /
`InvoiceLineEntity` and `InvoiceHeader` / `InvoiceHeaderEntity`) drive the bridges. The parent (`InvoiceHeader` with
`List<InvoiceLine>`) auto-recurses into the user-declared child bridge — no manual list-lift wiring.

**Endpoints**

| Path                              | Generated machinery                                                                         |
| --------------------------------- | ------------------------------------------------------------------------------------------- |
| `POST /invoices/lines/forward`    | `InvoiceLinePath.start().asInvoiceLineEntity().read(line)` — generated navigator hop        |
| `POST /invoices/lines/backward`   | `InvoiceLineBridge.backward(entity)` — generated static method                              |
| `POST /invoices/headers/forward`  | `InvoiceHeaderBridge.forward(header)` — auto-recurses into `InvoiceLineBridge` for the list |
| `POST /invoices/headers/backward` | `InvoiceHeaderBridge.backward(entity)` — same in reverse                                    |

**What lands**

- **`@Bridge(Target.class)`** emits `<Source>Bridge` (`BRIDGE` Telescope constant + static `forward`/`backward`). The
  bijection rule requires source and target expose the same field-name set.
- **Bridge hop on the navigator** — when a `@Focus`-annotated record also carries `@Bridge`, its emitted
  `<Source>Path<R>` gains an `as<TargetSimpleName>()` method that returns a typed continuation (`<Target>EntityPath<R>`
  when the target is `@BeanFocus`-navigable). Navigation keeps reading like a sentence after the paradigm hop.
- **Deep recursion through user-declared bridges** — `InvoiceHeader` carries `List<InvoiceLine>`. The parent
  `InvoiceHeaderBridge` auto-emits a list-lift that delegates per-element to the user-declared `InvoiceLineBridge`
  rather than synthesising its own anonymous Iso.
- **Zero `Telescope.mapper(...)` at runtime** — every conversion is a direct method call. Compile-time bound,
  IDE-navigable, no reflection in the hot path.

---

## Mixed module

### `order-jpa/` — the e-commerce kitchen sink

The widest surface of any submodule. **Headline: "pick your trade-off per call site."** The same `Order` domain backs
eight endpoints that each demonstrate a different telescope angle. Both the runtime DSL
(`Telescope.of(Order.class).field(...)`) and the codegen path navigators (`OrderPath.start().x()`) live side by side —
the choice between them happens at the controller, not at the domain. Adding `@Focus` / `@BeanFocus` is purely opt-in:
the runtime mapper transparently uses the codegen-emitted holder constants when present (holder-probe fast path,
post-ADR-0006), but doesn't require them.

**Endpoints**

| Path                              | Telescope angle                                                                                  |
| --------------------------------- | ------------------------------------------------------------------------------------------------ |
| `POST /orders`                    | Runtime DSL — basic CRUD with deep-update email normalisation pre-save                           |
| `POST /orders/path`               | Codegen navigator — `OrderPath.start().lineItems().each().unitPrice().update(...)`               |
| `POST /orders/validated`          | `updateValidated` accumulates per-line-item errors into one 400 payload (not first-failure-wins) |
| `POST /orders/{id}/bulk-update`   | `Telescope.all(overIfPresent(...), mapIfPresent(...))` — sparse-PATCH composition, no if-ladder  |
| `POST /orders/{id}/inspect`       | `read` / `find` / `count` / `exists` terminals — describe a path in the request body             |
| `GET  /orders/{id}/redacted`      | `Telescope.from(...).to(...).using(forward, backward)` — lossy one-way projection                |
| `GET  /orders/{id}/partner-label` | `Mapper<Order, PartnerShippingLabel>.forward(...)` — full mapper-driven partner DTO              |
| `PATCH /orders/{id}/from-partner` | `Mapper.patch(existing, partial)` — sparse overlay from partner side                             |

**Domain shape**

```
Order (record)                       OrderEntity (@Entity)
├── id: Long                         ├── id: @Id @GeneratedValue Long
├── orderNumber: String              ├── referenceCode: @Convert(UppercaseConverter)
│                                    │              (typed rename via Mapping.to)
├── customer: Customer  ───────────► ├── customer: @ManyToOne(fetch = LAZY) CustomerEntity
├── shippingAddress: Address         ├── shippingAddress: @Embedded AddressEmbeddable
├── billingAddress: Address          ├── billingAddress: @Embedded AddressEmbeddable
├── lineItems: List<LineItem>        ├── lineItems: @OneToMany List<LineItemEntity>
├── giftWrap: Optional<Address>      ├── giftWrap: @Embedded AddressEmbeddable (nullable)
├── metadata: Map<String, String>    ├── metadata: @ElementCollection Map<String, String>
└── payment: sealed Payment              (no payment column — partner processor owns it;
    (CreditCard | PayPal |             drop(Order::payment) on the runtime mapper)
     BankTransfer)

Customer (record)                    CustomerEntity (@Entity)
├── id: Long                         ├── id: @Id @GeneratedValue Long
├── name: String                     ├── name: String
├── email: String                    ├── email: String
└── tags: Set<String>                └── tags: @ElementCollection Set<String>

LineItem (record)                    LineItemEntity (@Entity)
├── sku: String                      ├── sku: String
├── quantity: int                    ├── quantity: int
└── unitPrice: BigDecimal ─────────► └── unitPriceCents: long
                                                  (typed transform — 19.99 ↔ 1999)
```

**Telescope capabilities demonstrated** (full surface — the kitchen sink)

- **`Telescope.mapper(A, B, Mapping... rows)`** — the runtime factory.
- **`Mapping.to(srcAcc, tgtAcc)`** — same-typed correspondence (mostly inferred via auto-mapping).
- **`Mapping.to(srcAcc, tgtAcc, fwd, bwd)`** — typed transform for `BigDecimal ↔ long-cents`.
- **`Mapping.via(srcAcc, tgtAcc, nestedMapper)`** — compose sub-mappers (Customer, Address, LineItem) into the top-level
  Order mapper.
- **`Mapping.drop(srcAcc)` / `Mapping.drop(srcAcc, targetClass)`** — declare a source field intentionally NOT mapped.
  `partnerLabelMapper` uses both: top-level `drop(Order::metadata)` keeps internal metadata off the partner DTO, and
  nested `drop(Customer::tags, PartnerCustomer.class)` keeps Customer's internal tag set off the partner-facing customer
  shape.
- **`WriteHint.writeBeans(SETTERS)`** — global default for Hibernate-managed identity assignment.
- **`Mapper.forward` / `Mapper.backward`** — both directions from one definition.
- **`Mapper.patch(existing, partial)`** — sparse overlay; partner-PATCH endpoint.
- **`Mapper.asTelescope()`** — promote a mapper into a `Telescope<A, B>` so it composes via `.then(...)` into a typed
  chain that bridges record-side and entity-side leaf types.
- **`Telescope.of(...).field(...).field(...).update(...)`** — deep update through nested field levels.
- **`Telescope.all(overIfPresent(...), mapIfPresent(...))`** — sparse-PATCH composition with no if-ladder.
- **`Telescope.from(...).to(...).using(forward, backward)`** — hand-rolled bridge for sealed-type or lossy projection
  cases.
- **`@Focus` / `@BeanFocus`** — annotation-driven codegen that emits `<X>Path<R>` navigators and `<X>Telescope` metadata
  holders. Consumed inline in `OrderPathController`.
- **`Optional<Address>`, `List<LineItem>`, `Map<String, String>`, `Set<String>` cardinality** — recurse through the
  runtime factory without special-casing.
- **Hibernate LAZY-proxy unwrap** — `OrderEntity.customer` is `@ManyToOne(fetch = LAZY)`. `Beans.persistentClassOf`
  forces a single initialisation fetch (counted via `Statistics.getEntityFetchCount()`).
- **Sealed-narrow after paradigm hop** —
  `Telescope.of(Order.class).field(Order::payment).then(paymentBridge()).as(CreditCardEntity.class).field(CreditCardEntity::getCardNumber)`
  crosses records → sealed bridge → prism narrow → bean-getter field in one expression.

---

## Running each module

Each module is standalone — `cd` into it and use the Gradle wrapper at the repo root:

```bash
# pick one:
./gradlew :examples:springboot:product-starter:bootRun
./gradlew :examples:springboot:org-chart:bootRun
./gradlew :examples:springboot:invoicing:bootRun
./gradlew :examples:springboot:order-jpa:bootRun
```

Then send requests with `curl` or any HTTP client. Each module's endpoints are listed in its section above.

**Run the integration tests** for any module:

```bash
./gradlew :examples:springboot:<module>:test
```

Each module's tests drive the full Spring Boot context against an H2 in-memory DB (where applicable) and assert
end-to-end behaviour.

## Key takeaways

1. **Runtime and codegen aren't competing paradigms** — codegen _enhances_ the runtime path. `@Focus`-emitted holder
   constants are picked up transparently by the runtime mapper via the holder-probe fast path (ADR-0006). Adding the
   annotations is opt-in; removing them doesn't change behaviour, only performance.
2. **Bidirectional mapping is one definition.** One `Telescope.mapper(A, B, ...)` call feeds both directions —
   `forward(a) → B`, `backward(b) → A`, `patch(base, partial) → A`. With MapStruct you'd write two `@Mapper` interfaces
   or use `@InheritInverseConfiguration`.
3. **You can pick your trade-off per call site.** The kitchen-sink `order-jpa/` module shows this explicitly — eight
   endpoints, one domain, different telescope angles per controller.
4. **Hibernate doesn't fight telescope.** The bean side reads `@Entity` POJOs through standard `getX`/`setX`
   conventions. `@Embeddable`, `@OneToMany`, `@ElementCollection`, `@Convert`, LAZY proxies, and bidirectional
   self-references all work without library-specific awareness.
