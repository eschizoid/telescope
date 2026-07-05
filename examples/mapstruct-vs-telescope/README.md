# telescope vs MapStruct — the canonical head-to-head

A small, runnable, **reproducible** comparison. Same `Order → OrderDto` mapping, written both ways, in one module so you
can run it yourself:

```bash
./gradlew :examples:mapstruct-vs-telescope:test
```

The domain is deliberately ordinary — a nested object and a collection, one field that needs an explicit rename.
Immutable records in, mutable JavaBean DTOs out — the shape JPA and serialization frameworks impose — and both
frameworks handle the paradigm hop:

```text
immutable records                          mutable JavaBeans (no-arg ctor + setters)
Order(id, Customer, List<LineItem>)        OrderDto(getId, getCustomer, getLines)
  Customer(name, email)          ──▶         CustomerDto(getName, getContactEmail)      // email -> contactEmail
  LineItem(sku, quantity, price)             LineItemDto(getSku, getQuantity, getPrice) // same-named, auto
```

Both frameworks produce the **identical** `OrderDto` (the first test pins it). This isn't a strawman where MapStruct is
misused — it's configured the normal way, and it works. The difference shows up the moment the code changes underneath
it: renaming a field, leaving a target unmapped, or needing to do anything past mapping.

---

## Act 1 — rename a field: the mapping string can't refactor

The one cross-named field has to be spelled out on both sides. Here's the entire difference:

```java
// telescope — a method reference the compiler checks and the IDE refactors
Telescope.mapper(Order.class, OrderDto.class,
    to(Customer::email, CustomerDto::getContactEmail));  // recursion handles everything else

// MapStruct — a string the IDE cannot see
@Mapping(source = "email", target = "contactEmail")
CustomerDto toDto(Customer customer);
```

Now do what every codebase does eventually: **rename `Customer.email()` → `Customer.emailAddress()`** with your IDE's
rename refactor. Both frameworks catch the change at compile time — credit where due, MapStruct is loud here, not
silent. The difference is **who does the fixing**.

- **telescope:** `Customer::email` is a real reference. The refactor updates it to `Customer::emailAddress`
  automatically; if anything is missed it's a compile error at the line. Zero edits, and the mapping keeps working.
- **MapStruct:** `@Mapping(source = "email", …)` is opaque text the refactor cannot touch. Left pointing at a property
  that no longer exists, MapStruct **fails the build** — this is the actual error, captured from this module:

  ```
  error: No property named "email" exists in source parameter(s). Did you mean "emailAddress"?
  ```

  That's the _good_ outcome: caught at compile time, not a silent runtime bug. But the fix is **manual** — you hand-edit
  that string, and every other `@Mapping` across every mapper that named the renamed field. telescope's refactor did all
  of it in one keystroke.

So both are compile-safe on a field they map explicitly; telescope is **refactor-safe**. The string isn't unsafe — it's
_un-refactorable_, which turns every rename into a string-chase across your mappers.

> The documented core of the pitch: **method references over string-keyed `@Mapping`.** Strings don't refactor.

**Reproduce it:** rename `Customer.email` via your IDE (let it update telescope's `Customer::email`), leave the
`@Mapping("email")` string as-is, and run `./gradlew :examples:mapstruct-vs-telescope:build`. telescope compiles;
MapStruct prints the error above.

---

## A separate footgun — unmapped targets go silently null

MapStruct's _other_ hazard is unrelated to renames, and it really is silent. A target field with **no source at all** —
a newly added DTO field, or one whose source quietly drifted away — is, under MapStruct's **default**
`unmappedTargetPolicy` (`WARN`), compiled with only a warning and left **`null` at runtime**:

```
warning: Unmapped target property: "region".   // <- compiles anyway; region is null at runtime
```

This module pins it permanently: `SilentDropMapper` maps to a `CustomerContactDto` whose `region` has no source, and the
test asserts the `null`, so CI demonstrates the footgun on every run. Setting `unmappedTargetPolicy = ERROR` turns it
into a build failure (the recommended hardening) — but it's off by default. telescope closes this hole twice over: the
strict `mapper(...)` refuses an unmapped field at construction rather than nulling it, and with `telescope-codegen` on
the annotation-processor path the same refusal fires at **compile time** — the verifier replays the pairing decisions
over every statically-visible `mapper(...)` call and anchors the error on the offending call site, with the identical
diagnostic text, no annotation or policy flag required.

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
this operation simply isn't expressible. (MapStruct's `@MappingTarget` update methods mutate an existing _mutable_ bean
in place — they can't rebuild an immutable record graph and hand you a new value with the original untouched.) The test
pins that the original `Order` is unchanged and a new graph is returned.

One vocabulary mapped the object _and_ updated it. With MapStruct you'd reach for a second tool (hand-written
copy-with-changes, or an optics library) the moment you step past mapping.

---

## Act 3 — telescope explains and traces itself; MapStruct is a black box

Acts 1 and 2 were about _writing_ the mapping. Act 3 is about **seeing it**. Every telescope mapper answers two
questions MapStruct structurally cannot: its structure lives only in generated `…MapperImpl.java` you go read, and its
runtime behaviour is whatever you hand-instrument.

**`explain()` — the static structure, as data.** The Act 1 rename isn't a string buried in generated code; it's a row
you can print or assert on:

```java
TelescopeMappings.CUSTOMER_MAPPER.explain();
// Mapped:
//   ✓ email → contactEmail
//   ✓ name  → name
```

That `✓ email → contactEmail` is the exact override from Act 1, now a first-class correspondence. The test asserts on it
directly — `explain().mapped()` contains `("email", "contactEmail")` — a completeness check MapStruct offers no surface
for.

**`trace(input)` — the same rows with real values, whole nested graph.** For one `Order`:

```java
TelescopeMappings.ORDER_MAPPER.trace(order);
// ✓ id        "o-1"                                      → id "o-1"
// • customer  Customer[name=Ada, email=ada@example.com]  → customer CustomerDto[name=Ada, contactEmail=ada@example.com]
// • lines     [LineItem[sku=sku-1, …], …]                → lines [LineItemDto[sku=sku-1, …], …]
```

**Auto-logging — flip a level, no code change.** telescope logs its own `explain()` at `DEBUG` and every conversion's
`trace()` at `TRACE` through `java.lang.System.Logger` (java.base, zero dependency). Name the type-pair logger and every
mapping narrates itself:

```xml
<logger name="io.github.eschizoid.telescope.mapper.Order.OrderDto" level="TRACE"/>
```

MapStruct's generated `OrderMapStructMapperImpl` is opaque: to see what it mapped you read generated source; to see
values at runtime you instrument it by hand. telescope makes both first-class — structure you can assert on, values you
can flip on.

> This slice's own tests prove the point: every act narrates what it proves through `System.Logger`. Run
> `./gradlew :examples:mapstruct-vs-telescope:test` and read the walkthrough, not just the green ticks.

---

## Where MapStruct is still the right call

Being fair is the point of a reproducible comparison:

- **Mature ecosystem and IDE tooling** — MapStruct has years of plugins, docs, and community answers behind it.
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
