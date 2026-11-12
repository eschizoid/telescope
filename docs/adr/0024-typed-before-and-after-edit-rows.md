# ADR-0024: Typed `before` and `after` edit rows in place of mapping callbacks

**Status:** Proposed · **Date:** 2026-11-05

## Context

MapStruct runs code around a generated mapping through `@BeforeMapping` and `@AfterMapping`. It finds those methods by
their signature and calls them with the source and an `@MappingTarget` they are allowed to mutate. Telescope copied the
shape as hooks on a mapper value: `beforeForward(Function)`, `afterForward(Function)`, `afterForward(BiFunction)`,
`beforeBackward` and `afterBackward`.

The hooks have four limits.

1. A hook is an opaque lambda, so `explain()` and `trace()` can't describe what it does, and the compile-time verifier
   can't check it.
2. The generated path can't compile one. The processor reads a `@Bridge` field's initializer only when it is the
   `Telescope.mapper(...)` or `mapperForward(...)` call itself, so a hook chained onto that call is not a mapper the
   processor accepts. A hook added to the field's mapper later does run, but outside the generated code.
3. On a record target, a hook that changes one property has to rebuild the whole record by hand, through its canonical
   constructor or a `with` method the user writes.
4. Several hooks run one after another, and each one rebuilds the target again.

Most callbacks in practice do one of four things, and each already has a typed primitive here:

| What the callback does                                | The primitive that already exists                   |
| ----------------------------------------------------- | --------------------------------------------------- |
| fills a target property from several source fields    | `compute(target, Fn::of)` (ADR-0022 amendment)      |
| normalises target values: trim, case, clamp, defaults | `Edit.over(path, fn)` folded by `Telescope.all`     |
| normalises source values before mapping               | the same edits, applied to the source               |
| fixes values inside nested objects or list elements   | the same edits, through a nested or traversing path |

`Telescope.all(over(...), over(...))` already turns several edits on one structure into one rebuild, and it fuses paths
that share a prefix. A mapper has no way to run those edits as part of its own rows.

## Decision

1. **Four row factories hold edits.** `before(Edit<A>...)` and `after(Edit<B>...)` run around `forward`, and
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
       over(traverse(OrderDto::lines, LineDto::sku), String::toUpperCase)
     )
   );
   ```

2. **A `before` edit changes what rows read, and never rebuilds the source.** Some sources can be read but not rebuilt,
   such as a bean with getters and no setters, or an entity proxy, so a rebuild would make `forward` fail on a mapper
   that works today. The edited value is applied where a row reads the edited path instead. A row that reads the edited
   property gets the edited value, and the source object the caller passed is left as it was. When a row reads an object
   above the edited path as a whole, for example a nested pair that converts `shipping` while an edit changes
   `shipping.country`, that object has to be rebuilt with the edit applied. If its class can't be rebuilt, the mapper is
   refused by name when it is built and at compile time, naming the edit and the row.

3. **Order.** `forward` runs in this order on both paths, with the lambda hooks outermost:
   - First, a `beforeForward` hook, if the mapper has one.
   - Second, the `before` edits, as described in decision 2.
   - Third, every row, `constant` and `compute` included.
   - Fourth, the `after` edits, so an `after` edit can normalise a computed value.
   - Fifth, an `afterForward` hook, if the mapper has one.

   `backward` runs the same order with `beforeBackward` and `afterBackward`. Within one row kind, the edits of all rows
   of that kind fold into one `Telescope.all` normalizer and run in the order written.

4. **Direction is explicit.** `before` and `after` apply to `forward` only. An edit such as `String::strip` has no
   inverse, so `backward` never runs one in reverse. A two-way mapper that needs edits on `backward` names them with
   `beforeBackward` and `afterBackward`. `into` runs the `after` edits on the values it writes.

5. **`patch` touches only what the partial sets.** `patch(base, partial)` runs the `beforeBackward` edits on the
   partial, and it runs an `afterBackward` edit only on a path the partial sets. An edit on a path the partial leaves
   unset does not run, so the base keeps its value there, including a base value that is null.

6. **Edits follow `Telescope.all` exactly, null values included.** An edit row runs the same `Edit` the same way
   `Telescope.all` runs it, both for a null object partway along the path and for a null value at the end of it. The
   generated code reproduces that behavior. It doesn't add its own null checks or skip a call the runtime makes, so the
   two paths can't differ there. `CrossPathCorpusTest` gets a row for each case. A user who wants a null value left
   alone writes a function that accepts null.

7. **Edits take no part in pairing.** A `before` edit doesn't count as reading a source property, and an `after` edit
   doesn't count as filling a target property. A property an edit names still pairs by name, or is refused as unpaired,
   exactly as it would without the edit.

8. **The generated path applies edits inline.** For a `@Bridge` field, the processor reads each `over(path, fn)` and
   applies `fn` to the value where it is produced. A `before` edit is applied where a row reads the value, and an
   `after` edit is applied to the value before it is passed to the constructor, builder or setter. The bridge doesn't
   build the target and then rebuild it, so an edit adds no allocation. The gc profiler checks that in the benchmark
   below. The function is read under the rules other rows follow: a method reference, or a `static final` field holding
   one. An inline lambda is refused by name, and the message names the method-reference form.

9. **A path may be held in a `static final` field.** The processor accepts a `static final Telescope` field whose
   initializer is a path written in place, the same rule it applies to converters. A path held in any other variable, or
   built by a call the processor can't read, is refused by name on the row, and the runtime accepts it. That is the same
   split the existing path rows have.

10. **`explain()` and `trace()` describe edits.** Each edit shows as its own node, with its path and function, under the
    step it runs at. `trace(input)` shows the value at the path before and after the edit.

11. **The lambda hooks stay** for work that is not an edit of the mapped value, such as audit records or metrics. The
    documentation leads with the edit rows, and the hooks' javadoc points to them.

The verifier doesn't check which side a path is rooted at. When the source and target are different types, `javac`
already rejects a path rooted at the wrong one through the row's generic types. When they are the same type, a source
path and a target path are indistinguishable.

## What has to land first

These are separate pieces, each with its own PR, in this order:

1. **`over(Accessor, fn)`.** `Edit.over` takes a `Telescope<S, X>` today, so a single field reads
   `over(Telescope.of(OrderDto.class).field(OrderDto::fullName), String::strip)`. An overload taking a method reference
   makes it `over(OrderDto::fullName, String::strip)`. It is small, and `Telescope.all` can use it on its own.
2. **A traversing path factory, `traverse(...)`.** `path(...)` takes field steps only, so an edit of every list element
   still needs the long chain `Telescope.of(OrderDto.class).each(OrderDto::lines).field(LineDto::sku)`. A static factory
   `traverse(OrderDto::lines, LineDto::sku)`, overloaded like `path(...)` and built as `of(root).each(...).field(...)`,
   gives the short form. It is not named `each`, because `Telescope` already has an instance method `each(Accessor)`,
   and a static `each` called through an instance would compile and silently drop the path the instance holds. Whether
   it also covers `Optional` (`whenPresent`) and map values is decided in its PR.
3. **Container steps in generated paths.** The generated path compiles field steps today and refuses container steps. An
   `after` edit through `traverse(...)` needs the generated path to loop over the container and apply the edit to each
   element as it builds the new container, sized and allocated through the shared `ContainerAllocation` decision, as the
   runtime traversal does.
4. **Generated `into`** (#701). Decision 4 runs `after` edits on `into`. The generated `into` has shipped, so this step
   only needs it to apply `after` edits to the values it writes.

## Consequences

- MapStruct's `@BeforeMapping` and `@AfterMapping` map onto typed rows in the cheat sheet, with the same result on both
  paths and compiled code on the generated one.
- A record target can be normalised without a hand-written copy, because the edit names one property and the generated
  code applies it before the constructor call.
- The cross-path corpus gains rows for each edit kind and each direction. It also gains rows for a shared prefix, a list
  traversal, a null object on the path, a null value at the end, a source that can't be rebuilt, `patch` with a sparse
  partial, and the order against the hooks.
- A paired benchmark (mantra 6) compares a mapper with `after` edits against the same mapper followed by a hand-written
  fix-up, and against the lambda hook. It runs with the gc profiler, and the generated row must allocate no more than
  the mapper without edits.

## Alternatives considered

- **Compile the lambda hooks.** The processor can't read an arbitrary lambda body into generated code without
  reproducing it as written, which brings back the inline-lambda problem the rows avoid. It would still describe nothing
  to `explain()`.
- **Callbacks found by signature, as MapStruct does.** That is a version of the same untyped contract without strings,
  because the callback can change anything and nothing outside it knows what.
- **Edits that read the source**, `after(over(path, (src, value) -> ...))`. A value built from the source is what
  `compute` is for, and letting edits read it would give two rows for one job.
- **Run `after` edits in reverse on `backward`.** Most edits have no inverse, so a guessed reverse would silently do the
  wrong thing. Explicit `beforeBackward` and `afterBackward` rows say what runs.
- **Rebuild the source for `before` edits.** It is simpler, but it fails on sources that can be read and not rebuilt,
  which `forward` handles today.
- **Rebuild the target for generated `after` edits.** It is simpler, but it adds an object per call on the path that is
  meant to match MapStruct's speed.
- **A wrong-side check in the verifier.** `javac` already covers the case where it can be decided, and it can't be
  decided when the source and target are the same type.
