# ADR-0024: Typed `before` and `after` edit rows in place of mapping callbacks

**Status:** Proposed · **Date:** 2026-11-05

## Context

MapStruct runs code around a generated mapping through `@BeforeMapping` and `@AfterMapping`: methods it finds by their
signature and calls with the source and an `@MappingTarget` it lets them mutate. Telescope copied the shape as hooks on
a mapper value: `beforeForward(Function)`, `afterForward(Function)`, `afterForward(BiFunction)`, `beforeBackward` and
`afterBackward`.

The hooks have four limits.

1. A hook is an opaque lambda. `explain()` and `trace()` cannot describe what it does, and the compile-time verifier
   cannot check it.
2. The generated path cannot compile one. The processor reads a `@Bridge` field's initializer only when it is the
   `Telescope.mapper(...)` or `mapperForward(...)` call itself, so a hook chained onto that call is not a mapper the
   processor accepts. A hook added to the field's mapper later runs, but outside the generated code.
3. On a record target, a hook that changes one property has to rebuild the whole record by hand, through its canonical
   constructor or a `with` method the user writes.
4. Several hooks run one after another, each rebuilding the target again.

Most callbacks in practice do one of four things, and three of them already have typed primitives here:

| What the callback does                                | The primitive that already exists                   |
| ----------------------------------------------------- | --------------------------------------------------- |
| fills a target property from several source fields    | `compute(target, Fn::of)` (ADR-0022 amendment)      |
| normalises target values: trim, case, clamp, defaults | `Edit.over(path, fn)` folded by `Telescope.all`     |
| normalises source values before mapping               | the same edits, applied to the source               |
| fixes values inside nested objects or list elements   | the same edits, through a nested or traversing path |

`Telescope.all(over(...), over(...))` already turns several edits on one structure into one rebuild, fusing paths that
share a prefix. A mapper has no way to run those edits as part of its own rows.

## Decision

1. **Four row factories hold edits.** `before(Edit<A>...)` and `after(Edit<B>...)` run around `forward`;
   `beforeBackward(Edit<B>...)` and `afterBackward(Edit<A>...)` run around `backward`. Each is a `MapStep` row in the
   mapper's own row list, next to `to`, `compute` and `drop`. An edit is the existing `Edit<S>` that `over(path, fn)`
   builds.

   ```java
   @Bridge
   static final ForwardMapper<Order, OrderDto> DTO = mapperForward(
     Order.class,
     OrderDto.class,
     before(over(Order::email, String::strip)),
     to(Order::customerName, OrderDto::fullName),
     compute(OrderDto::label, Labels::of),
     after(
       over(OrderDto::fullName, String::strip),
       over(path(OrderDto::shipping, Shipping::country), Country::normalize),
       over(each(OrderDto::lines, LineDto::sku), String::toUpperCase)
     )
   );
   ```

2. **Order.** `before` edits rebuild the source before any row reads it. `after` edits run on the target once every row
   has written it, `constant` and `compute` included, so an `after` edit can normalise a computed value. The edits of
   all `after` rows of a mapper fold into one `Telescope.all` normalizer, applied in the order written, so a target is
   rebuilt once. The same holds for each of the other three row kinds.

3. **Direction is explicit.** `before` and `after` apply to `forward` only. An edit such as `String::strip` has no
   inverse, so `backward` never runs one in reverse; a two-way mapper that needs edits on `backward` names them with
   `beforeBackward` and `afterBackward`. `patch` runs the `backward` edits on the source it returns, and `into` runs the
   `after` edits on the target it writes into, through the target's setters.

4. **Edits do not take part in pairing.** A `before` edit does not count as reading a source property, and an `after`
   edit does not count as filling a target property. A property an edit names still pairs by name, or is refused as
   unpaired, exactly as it would without the edit. An edit changes values; it never decides which properties map.

5. **The generated path compiles edits into the bridge.** For a `@Bridge` field, the processor reads each
   `over(path, fn)` and emits a direct, null-safe rewrite of that path: each object on the path read once into a local,
   `fn` called directly, and each object rebuilt through the write strategy the shared `BeanWriteStrategy` order and the
   mapper's write hints pick, as #708's path writes do. Edits whose paths share a prefix share one rebuild of it. The
   function is read under the rules other rows follow: a method reference, or a `static final` field holding one; an
   inline lambda is refused by name, naming the method-reference form.

6. **`explain()` and `trace()` describe edits.** Each edit shows as its own node, with its path and function, under the
   step it runs at. `trace(input)` shows the value at the path before and after.

7. **The verifier checks edits** where it can read them: a path rooted at the wrong side (a target path in `before`, a
   source path in `after`) is refused by name on both paths and at compile time.

8. **The lambda hooks stay** for work that is not an edit of the mapped value, such as audit records or metrics. The
   documentation leads with the edit rows, and the hooks' javadoc points to them.

## What has to land first

These are separate pieces, each with its own PR, in this order:

1. **`over(Accessor, fn)`.** `Edit.over` takes a `Telescope<S, X>` today, so a single field reads
   `over(Telescope.of(OrderDto.class).field(OrderDto::fullName), String::strip)`. An overload taking a method reference
   makes it `over(OrderDto::fullName, String::strip)`. It is small and useful to `Telescope.all` on its own.
2. **A traversing path factory.** `path(...)` takes field steps only, so an edit of every list element still needs the
   long chain `Telescope.of(OrderDto.class).each(OrderDto::lines).field(LineDto::sku)`. A static factory
   `each(OrderDto::lines, LineDto::sku)`, overloaded like `path(...)` and built as `of(root).each(...).field(...)`,
   gives the short form. Its name and whether it also covers `Optional` (`whenPresent`) and map values are decided in
   that PR.
3. **Container steps in generated paths.** #708 and #720 compile field steps of a path into the bridge and refuse
   container steps. An `after` edit through `each(...)` needs the generated path to loop over the container and rebuild
   it, sized and allocated through the shared `ContainerAllocation` decision, as the runtime traversal does.
4. **Generated `into`** (#701). Decision 3 runs `after` edits on `into`, and the generated bridge has no `into` until
   #701 lands. Edit rows can land before it if `into` on a `@Bridge` field keeps delegating to the runtime for a mapper
   that has edit rows, with that limit stated.

## Consequences

- MapStruct's `@BeforeMapping` and `@AfterMapping` map onto typed rows in the cheat sheet, with the same result on both
  paths and compiled code on the generated one.
- A record target can be normalised without a hand-written copy: the edit names one property and the rebuild is
  generated.
- The cross-path corpus gains rows for each edit kind, each direction, a shared prefix, a list traversal and a null
  object on the path.
- A paired benchmark (mantra 6) compares a mapper with `after` edits against the same mapper followed by a hand-written
  fix-up, and against the lambda hook, with the gc profiler.

## Alternatives considered

- **Compile the lambda hooks.** The processor cannot read an arbitrary lambda body into generated code without
  reproducing it as written, which brings back the inline-lambda problem the rows avoid, and it would still describe
  nothing to `explain()`.
- **Callbacks found by signature, as MapStruct does.** That is a string-free version of an untyped contract: the
  callback can change anything, and nothing outside it knows what.
- **Edits that read the source**, `after(over(path, (src, value) -> ...))`. A value built from the source is what
  `compute` is for; letting edits read it would give two rows for one job.
- **Run `after` edits in reverse on `backward`.** Most edits have no inverse, so a guessed reverse would silently do the
  wrong thing; explicit `beforeBackward` and `afterBackward` rows say what runs.
