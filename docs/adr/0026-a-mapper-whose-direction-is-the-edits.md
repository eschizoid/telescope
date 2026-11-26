# ADR-0026: A mapper whose direction is the edit's

**Status:** Proposed · **Date:** 2026-11-26

## Context

A PATCH writes a partial body into an entity. Today it is declared as a two-way mapper from the entity to the body and
run backward: `Mapper<Order, OrderChanges> EDITS` and `EDITS.patchInto(order, changes)`, where
`patchInto(target, partial)` writes into the mapper's source. A reader has to work out that the edit flows against the
declared direction, and the rows follow the declared direction, not the edit's. Each cell below was checked against the
runtime mapper:

| On the way into the entity                     | `mapper(Order, OrderChanges)`, run backward                      | `mapperForward(OrderChanges, Order)` | `mapper(OrderChanges, Order)`                                |
| ---------------------------------------------- | ---------------------------------------------------------------- | ------------------------------------ | ------------------------------------------------------------ |
| a computed value or a constant                 | refused when built: `compute` and `constant` write the body side | runs on `forward`                    | runs on `forward` and `into`                                 |
| write only what the client sent, in place      | `patchInto`                                                      | no `into` or `patchInto`             | `patchInto` writes into the body                             |
| a property a PATCH may never change            | `drop(Order::getId)`, on the source side                         | left unwritten, silently             | refused when built: nothing fills `id` and no row can say so |
| an entity property added later fails the build | yes                                                              | no, a forward mapper is lenient      | yes, with no row that settles it                             |

So no mapper offers both a value computed on the way in, such as a price taken from the catalog, and a write of only
what the client sent. The README prices a new line through a `via` row naming `LineEdits`, a class written by hand whose
`backward` method is the PATCH path, and the demo does the same. That class costs a second thing: telescope-jpa merges a
managed collection by `@Id` only where its elements convert through a conversion that can write one element into another
in place (ADR-0017, amendment of 2026-11-19), and a hand-written converter class is not one, so the README's lines are
always cleared and refilled. A path read from the partial already works on `patchInto`, but a path written into the
entity is refused there, so no nested property of a held entity can be written in place.

This ADR assumes the rule of #818 is in place: a collection is written through the owner's adder found by type, else the
collection it holds or its setter, and the back-reference is set from `mappedBy` or the one property of the owner's
type. `WriteStrategy.ADDERS` and its row are gone.

## Decision

1. **`EditMapper<P, E>`, built by `Telescope.edits(P.class, E.class, rows...)`.** `P` is what an edit sends: a partial
   body, a create request, the changes a service works out. `E` is what it writes into, usually an entity. The classes
   are the head arguments, as for every factory, and the rows are written in the direction the edit flows, from `P` to
   `E`. The mapper offers four operations:
   - `forward(source)` builds a new `E`;
   - `into(target, source)` writes into `target` every property the mapper accounts for, a null included, which is a
     PUT;
   - `patchInto(target, partial)` writes into `target` only what the partial sends, which is a PATCH;
   - `patch(base, partial)` lays the same overlay over a copy of `base` and returns the copy, for a record or another
     `E` that cannot be written in place.

   It also has `explain()`, `trace()` and `toForwardMapper()`. It has no `backward` and no reverse view from `E` to `P`:
   `compute`, `constant` and `keep` have no inverse, and what a client sees of an entity is a mapper of its own, which
   differs from the edit in practice (the README's response adds a customer name and a total). `EditMapper` is neither a
   `Mapper` nor a `ForwardMapper`, so it inherits no `backward`, no `then` and no leniency. The factories then divide
   the work this way:

   | The job                                                                   | The factory     |
   | ------------------------------------------------------------------------- | --------------- |
   | what a client sees of an entity or a service value                        | `mapperForward` |
   | two types that convert both ways, where the round trip is the point       | `mapper`        |
   | writing into an entity or another target, new or held: create, PUT, PATCH | `edits`         |

2. **Rows read from `P` and write to `E`.** Every row keeps the meaning it has today, with `P` as the source and `E` as
   the target: `to` and its converting forms, `via`, path rows, `toOrElse`, `when`, `compute` and `constant`. A `via`
   row also takes an `EditMapper` for a nested pair. Two rows say what is never mapped:
   - `drop(P::x)` is a property of the partial that nothing reads, as it is today;
   - `keep(E::x)` is a property of `E` that no operation writes. `into` and `patchInto` leave the value `target` holds,
     `patch` the value `base` holds, and `forward` the value a new `E` is built with.

   `keep` is the name because it states what an edit does to the property, and because it names the target side without
   depending on the classes: `drop` on the target side would be ambiguous when `P` and `E` are one class, as in
   `edits(Order.class, Order.class)`. `edits` alone accepts `keep`; every other factory refuses it by name.

   The mapper is strict on both sides, decided by the shared `Unpaired` rule with kept properties counted as filled.
   When it is built, and at compile time on the field, every property of `E` that a member can write must be filled by a
   row, by the property of its name on `P`, or by the collection write as a back-reference (decision 4), or be kept.
   Every property of `P` must be read by a row or by the property of its name on `E`, or be dropped. So a property added
   to the entity later fails the build until someone writes a row for it, adds it to the partial or keeps it, and a
   property added to the partial that reaches nothing fails too instead of being ignored. A `keep` beside a row that
   writes the same property, and a `drop` beside one that reads it, are refused by name.

   `into` and `forward` write what each row gives, a null included; `toOrElse` writes its default for a null. `patch`
   and `patchInto` never write a null. Row by row:

   | Row                                     | In `patch` and `patchInto`                                                                                  |
   | --------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
   | by name, `to`, a converting `to`, `via` | written when the partial's value is not null; a primitive is never null, so it is always sent               |
   | a path read from the partial            | written when neither the leaf nor an object on the path is null                                             |
   | `toOrElse`                              | as `to`: a null is not sent, and the default is not written                                                 |
   | `compute` and `constant`                | run on every call, since neither reads one property the partial can leave out; a null result is not written |
   | `when(predicate, row)`                  | the predicate is tested on the partial, and the inner row then follows its own line                         |
   | `drop` and `keep`                       | a dropped property is never read and a kept one never written                                               |

   A container the partial sends is sent whole: an empty list empties the collection, and a null one leaves it alone. A
   path written into `E` is written in place on `into` and `patchInto`, into the objects the target holds along it, and
   builds an object only where the target holds null; on `forward` and `patch` it builds as ADR-0022's amendment on
   paths sets out. Replacing an object on the path would hand a managed entity a new instance in place of the one it
   references.

3. **Values from outside the partial.** A `compute` function is handed the source of the pair its row is keyed to, which
   is the partial for a row of the mapper and the partial's element for a row of the element's own edit mapper, as
   `compute` is handed the source on every factory today. The catalog price is
   `compute(OrderLine::getUnitPrice, Catalog::price)` with `Catalog.price(LineDto)`. The function is a method reference
   or a `static final` field holding one, under ADR-0022's rules, so a static service such as the catalog is reached
   that way. It is not handed the `E` being written, because on `forward` the generated code builds every value before
   it calls a constructor or a builder, so that `E` does not exist yet. It is not handed the value the target holds,
   which only `into` and `patchInto` have and which makes the row an edit of the target, ADR-0024's job. It is not
   handed the owner of an element: the one value an element takes from its owner is the back-reference, which decision 4
   sets. A service the container injects is a context parameter, which ADR-0018 leaves to its own ADR, and this one does
   not decide it.

4. **Collections follow #818, with no row.** `forward`, `into`, `patch` and `patchInto` write a collection property of
   `E` through the owner's adder found by type, else the collection the target holds, else its setter, and set the
   back-reference by #818's rule. The back-reference can be read from the element's class alone: the property a
   `mappedBy` on a collection of that class names, or else its one property whose class holds a collection of it. It
   counts as filled when the element's own mapper is built, so an element mapper needs no row for it. Elements converted
   through a nested pair the mapper walks, or through an `EditMapper` a `via` row names, are converted by a conversion
   that can write in place, so with telescope-jpa a managed collection is merged by `@Id` as ADR-0017's amendment sets
   out; elements a hand-written class converts are still cleared and refilled.

5. **The generated path compiles it like every `@Bridge` field.** `FieldRows` reads `Telescope.edits(...)` written in
   place on a `static final` field, as it reads `mapper` and `mapperForward`, and `keep` joins the rows it compiles.
   `BridgeProcessor` writes `<P>To<E>Bridge` with `forward`, `into`, `patch` and `patchInto` and no `backward`, and the
   class it writes for the field extends `EditMapper` through the `BridgeGrant` constructor, overriding each operation
   with one call into the bridge. `into` and `patchInto` are generated where `E` is a bean that can be written in place
   under ADR-0018's amendment on update in place, with collections written as decision 4 says; elsewhere the field's
   mapper runs the runtime operation, which refuses by name in the same words. The strictness of decision 2 and the null
   rules are decided once in `internal/pairing`, for the runtime mapper, the processor and `MapperVerifierProcessor`. A
   row the generated path cannot express is refused by name on the row, as on every `@Bridge` field, a row keyed to a
   nested pair among them, which is why the README gives its lines an edit mapper of their own. `CrossPathCorpusTest`
   gains rows for each row kind under each of the four operations with a null and a non-null partial value, a property
   neither side accounts for, a kept property, a computed element property, a back-reference counted as filled and a
   merge by `@Id` through a `via` row. No difference between the paths is introduced, so nothing is added to ADR-0019's
   registers.

6. **ADR-0024's edit rows apply in the one direction there is.** An edit mapper takes `before(Edit<P>...)`, which
   changes what its rows read from the partial, and `after(Edit<E>...)`, which normalises the values it writes, in
   ADR-0024's order: `before` edits, then every row, `compute` and `constant` included, then `after` edits, then the
   write into the target. `beforeBackward` and `afterBackward` are refused by name, since there is no backward. In
   `patch` and `patchInto` an `after` edit runs only on a value the call writes, as ADR-0024's decision 5 has it for a
   path the partial sets. Neither ADR subsumes the other: ADR-0024 changes a value that is already there, and this one
   decides where values go and fills a property from the source. This one does narrow ADR-0024's backward rows: a PATCH
   was the main reason to run edits on `backward`, and an edit mapper runs it forward, so `beforeBackward` and
   `afterBackward` remain for a two-way mapper's `backward` and `patch` only. Neither depends on the other, so either
   can land first.

7. **Migration.** One `feat!` pull request with a `BREAKING CHANGE:` footer, with no deprecated alias.
   - `Mapper.patchInto` is removed. Its one job, writing a partial target into a held source, is an edit mapper's
     `patchInto`, in the direction its rows run. The seams only that backward write uses go with it, among them
     `PATCH_INTO_ELEMENTS`, `writeConvertedBack`, `checkConvertedBack` and `Mapper.patchIntoElements()`.
   - `Mapper.patch` stays: it is the pure overlay of a two-way pair that returns a new value, which ADR-0017 kept.
     `Mapper.into` stays: it writes the mapper's own forward direction, so its rows already run there.
   - `mapperForward` stays the factory for what a client sees. A `mapperForward` that builds an entity, such as the
     demo's `PLACE`, keeps working, and the documentation steers it to `edits` for the strict target.
   - `docs/type-conversion.md` explains which factory to pick with the table in decision 1. The demo's `LineEdits` and
     `CatalogLines` give way to one edit mapper for its line pair, which the PATCH and the create request both name in a
     `via` row.

8. **The README's opening example after #818 and this ADR.** The entities, `OrderChanges`, `OrderDto` and the PATCH
   handler are unchanged, and `EDITS.patchInto(order, changes)` reads in the direction it writes. `LineEdits` and the
   `WriteHint` imports are gone, and every row comes from one static import. `Catalog.price(LineDto)` and
   `Catalog.cost(LineDto)` look the product up by the line's SKU.

   ```java
   import static io.github.eschizoid.telescope.Telescope.*; // edits, mapperForward, path
   import static io.github.eschizoid.telescope.mapping.Mapping.*; // every row: to, via, compute, drop, keep

   public final class OrderMappers {

     // A line as a PATCH sends it. The price and the cost come from the catalog.
     @Bridge
     public static final EditMapper<LineDto, OrderLine> LINE = edits(
       LineDto.class,
       OrderLine.class,
       compute(OrderLine::getUnitPrice, Catalog::price),
       compute(OrderLine::getCostPrice, Catalog::cost),
       drop(LineDto::unitPrice), // a price the client sends is ignored
       keep(OrderLine::getId)
     );

     // A PATCH: how an OrderChanges is written into an Order.
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

     // The response: what a client sees of an Order.
     @Bridge
     public static final ForwardMapper<Order, OrderDto> DTO = mapperForward(
       Order.class,
       OrderDto.class,
       to(path(Order::getCustomer, Customer::getName), OrderDto::customerName),
       compute(OrderDto::total, Pricing::total) // Pricing.total(order) sums the lines
     );
   }
   ```

   `LINE` is declared before `EDITS` because a static initializer cannot name a field declared after it. The new lines
   go through `order.addLine(...)`, found by its type, and `OrderLine.order` is the back-reference `mappedBy` names, so
   `LINE` needs no row for it. A property added to `Order`, `OrderLine`, `OrderChanges` or `LineDto` later fails the
   build until someone decides what an edit does with it.

## Consequences

- A PATCH, a PUT and a create are each one call on a mapper whose head reads in the direction the data moves, and
  computed values and constants run on the way in on both paths, so the README and the demo need no converter class.
- The element pair of a PATCH converts through a mapper that can write in place, so with telescope-jpa and identifiers
  on the wire a changed line is updated where it is instead of deleted and inserted again. A `compute` row runs on every
  element written, a matched one included, so a line the client sends again is priced again from the catalog.
- One relation that was one two-way field becomes two fields when both a response and a PATCH are needed. The README
  already has two, since the response and the edit differ.
- Code calling `Mapper.patchInto` stops compiling and moves to an `edits` field, whose rows read the other way round:
  each `drop` of an entity property becomes a `keep`, and the `via` converter's `backward` becomes rows.

## Alternatives considered

- **Run `compute` and `constant` backward on a two-way mapper**, through `computeBackward` and the like. Rejected: it
  doubles the row vocabulary, as ADR-0024's backward edits do, and leaves the PATCH declared against its direction.
- **Give `ForwardMapper` an `into` and a `patchInto`.** Rejected: a forward mapper leaves a target property nothing
  fills unwritten without a word, which is right for a response and loses the safety a write into an entity needs, and
  it has no row to say a property is never written.
- **`drop` on the target side in place of `keep`.** Rejected: when the partial and the target are one class, a row
  cannot tell which side it names.
- **Hand `compute` the `E` being written.** Rejected: on `forward` it does not exist until every value has been built,
  and the generated code builds values before the constructor call.
- **A `compute` that reads one property of the partial without claiming it**, as
  `compute(OrderLine::getUnitPrice, LineDto::sku, Catalog::price)`. Deferred: it would be a second row for `compute`'s
  job, and a static method taking the element does the same in one line.
- **The line rows in `EDITS` itself, keyed to the nested pair.** Rejected for now: the generated path has no form for a
  row keyed to a nested pair, and an edit mapper of the element's own is its pair's bridge, which the create request can
  name too.
- **Keep `Mapper.patchInto` beside `edits`.** Rejected: two ways to PATCH an entity, one of which cannot compute on the
  way in, would need a page explaining when each is right, and the backward in-place write carries seams on every
  generated bridge that nothing else needs.
