# telescope vs MapStruct — the canonical head-to-head

A small, runnable, **reproducible** comparison. Same `Order → OrderDto` mapping, written both ways, in one module so you
can run it yourself:

```bash
./gradlew :examples:mapstruct-vs-telescope:test
```

The domain is deliberately ordinary — a nested object and a collection, one field that needs an explicit rename:

```text
Order(id, Customer, List<LineItem>)        OrderDto(id, CustomerDto, List<LineItemDto>)
  Customer(name, email)          ──▶         CustomerDto(name, contactEmail)     // email -> contactEmail
  LineItem(sku, quantity, price)             LineItemDto(sku, quantity, price)   // same-named, auto
```

Both frameworks produce the **identical** `OrderDto` (the first test pins it). This isn't a strawman where MapStruct is
misused — it's configured the normal way, and it works. The difference shows up in two places MapStruct's design can't
reach.

---

## Act 1 — rename a field, and watch the mapping

The one cross-named field has to be spelled out on both sides. Here's the entire difference:

```java
// telescope — a method reference the compiler checks and the IDE refactors
Telescope.mapper(Order.class, OrderDto.class,
    to(Customer::email, CustomerDto::contactEmail));     // recursion handles everything else

// MapStruct — a string the IDE cannot see
@Mapping(source = "email", target = "contactEmail")
CustomerDto toDto(Customer customer);
```

Now do what every codebase does eventually: **rename `Customer.email()` → `Customer.emailAddress()`** with your IDE's
rename refactor.

- **telescope:** `Customer::email` is a real reference. The refactor moves it to `Customer::emailAddress` automatically;
  if you somehow miss it, it's a compile error pointing at the line. Nothing can go stale.
- **MapStruct:** `@Mapping(source = "email", …)` is opaque text. The refactor does not touch it. What happens next is
  the whole point, and it depends on a policy most teams never set:

  **Layer 1 — default config (`unmappedTargetPolicy = WARN`).** The stale string leaves `contactEmail` with no source.
  MapStruct compiles with a _warning_ and the field is **silently `null` at runtime**. A quietly wrong object, no error.
  This module pins that behavior permanently — `SilentDropMapper` has an unmapped `region`, and the test asserts the
  `null`, so CI demonstrates the footgun on every run:

  ```
  warning: Unmapped target property: "region".   // <- compiles anyway; region is null at runtime
  ```

  **Layer 2 — strictest config (`unmappedTargetPolicy = ERROR`).** Now the stale string fails the build instead of
  nulling — safer. But you've only traded a silent bug for manual labor: every `@Mapping` string referencing the renamed
  field, across every mapper in the codebase, must be found and hand-edited. telescope's IDE refactor did all of that in
  one keystroke, and a stale string was never possible.

**Reproduce it:** open `Customer.java`, rename `email` via your IDE, and run
`./gradlew :examples:mapstruct-vs-telescope:build`. Watch the telescope reference follow the rename while the MapStruct
string is left behind — silently (default) or as a compile error you now own (strict).

> This is the documented core of the pitch: **method references over string-keyed `@Mapping`.** Strings don't refactor.

---

## Act 2 — the same typed path also updates the graph

Act 1 showed the _mapping_ is refactor-safe. The deeper point is that it isn't a mapper at all — it's **one typed path
for the whole lifecycle**. The same `Telescope` vocabulary that mapped `Order → OrderDto` also reads, writes, and
updates an `Order`'s interior:

```java
// Multiply every line item's price by a rate and rebuild the whole immutable Order graph — one pass.
Order taxed = Telescope.of(Order.class)
  .each(Order::lines)
  .field(LineItem::price)
  .update(order, (price) -> price.multiply(rate));
// `order` is untouched; `taxed` is a new immutable graph.
```

MapStruct has **no equivalent**, by design: it maps `A → B`. It does not read, write, or update a value's interior, so
this operation simply isn't expressible. The test pins that the original `Order` is unchanged and a new graph is
returned.

One vocabulary mapped the object _and_ updated it. With MapStruct you'd reach for a second tool (hand-written
copy-with-changes, or an optics library) the moment you step past mapping.

---

## Where MapStruct is still the right call

Being fair is the point of a reproducible comparison:

- **Mature ecosystem and IDE tooling** — MapStruct has years of plugins, docs, and Stack Overflow answers.
- **Pure compile-time generation everywhere** — telescope's runtime path uses reflection (its `@Focus` / `@Bridge`
  codegen path is reflection-free, but it's opt-in); MapStruct generates code for every mapping by default.
- **`jakarta` validation accumulates too** — if your only need is "collect all invalid fields," `Validator.validate()`
  already returns the whole set. telescope's edge there is _cohesion_ (validation threaded through the same typed pass),
  not raw capability — so it's intentionally left out of this head-to-head.

What telescope changes is narrower and sharper: your mappings are **refactor-safe by construction**, and the same typed
path keeps working when you step past mapping into reading and updating the immutable graph.

---

_Run `./gradlew :examples:mapstruct-vs-telescope:test` — every claim here is a passing test or a one-command
reproduction._
