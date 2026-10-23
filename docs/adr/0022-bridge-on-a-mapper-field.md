# ADR-0022: `@Bridge` on a runtime mapper field

**Status:** Accepted · **Date:** 2026-10-23

## Context

A generated mapper is declared today with `@Bridge(Target.class)` on the source class, and every override is a per-field
annotation that names fields as strings: `@Rename`, `@Transform`, `@ViaMapper`, `@Compute`, `@Constant` and `@Default`.
The runtime mapper says the same things with typed rows: `to(User::id, UserDto::userId)`, `via(...)`, `drop(...)`,
`constant(...)`. A user who wants both paths writes the mapping twice, in two vocabularies, and the two can drift.

The rows are plain Java expressions in a field initializer. `MapperVerifierProcessor` already reads them at compile time
through the javac tree API, resolving each method reference to the accessor it names. The tree API answers the same
questions during a processing round, when generated sources can still be written: javac attributes the expression on
demand, so `User::id` resolves to `User.id()` and `String::toLowerCase` to the overload a `Function<String, String>`
takes. `BridgeProcessor` builds every bridge from one per-pair configuration, so rows read into that configuration feed
the existing emitter without a second one.

## Decision

1. **`@Bridge` may annotate a `static final` field initialised in place by `Telescope.mapper(...)` or
   `Telescope.mapperForward(...)`.** The processor reads the rows of that call and generates the bridge from them. The
   field form takes no attributes; the rows are the whole declaration. `mapper` gives a strict bridge, `mapperForward` a
   lenient one, as on the runtime path.
2. **The rows the processor compiles** are those whose every argument it can name at compile time, and whose meaning the
   bridge emitter already has:
   - `to` between two accessors, which renames when the names differ;
   - `to` with a forward and a backward converter, and `toOneWay` with a forward one, which convert and may also rename;
   - `via` naming another `@Bridge` field, whose generated bridge converts the property;
   - `drop` of a source property, `constant` of a literal, `toOrElse` with a literal default, and `compute` with a
     supplier.

   Accessors are method references (`User::id`). Converters and suppliers are references to a static method
   (`Conversions::lower`), to an unbound instance method (`String::toLowerCase`) or to a constructor (`UserId::new`);
   the processor writes a small generated class that calls the reference, and the bridge converts through it as it does
   through a `@Transform` class today, so every type check the emitter makes applies unchanged.

   Rows whose meaning the emitter does not have yet are refused by name until it does: a source or target written as a
   telescope path (ADR-0018 step 5), `drop` keyed to a nested pair, `enumTo`, `cases`, and the mapper-wide hints
   `writeBean`, `writeBeans` and `nullSourceValues`. Each one that ADR-0018 brings to the generated path is added to
   this list then.

3. **Everything else is refused by name at compile time, on the row.** An inline lambda, a row built by a helper method,
   a loop or a variable, a row keyed to a nested pair, a non-literal constant, an initializer that is not the factory
   call itself (a `mapperBuilder` chain included), a field that is not `static final`, and the runtime-only rows
   (`when`, `zip`) each get a message naming what the processor needs instead. A refused row never falls back to the
   runtime path silently: the build fails, so the field never claims generated code it does not have.
4. **Dispatch is by the calling class and the pair.** The bridge's generated `BridgeProvider` names the class that
   declares the field. `Telescope.mapper` and `mapperForward` read their caller once, when the mapper is built, with a
   `StackWalker` that shows hidden frames, reflection's among them. Only when the frame directly below the factory is a
   class's static initialiser, which is what initialises a `@Bridge` field, do they look a provider up by that class and
   the pair through `ServiceLoader`. When one is found the factory returns the mapper generated for the field and builds
   nothing reflective; when none is found, because the processor did not run, it builds the runtime mapper from the same
   rows. A call from a method, a lambda, the hidden class behind a method reference or `Method.invoke` puts its own
   frame below the factory and always builds the runtime mapper. At compile time, a class that declares a `@Bridge`
   field builds every other mapper as a static field initialised in place by the factory, naming literal classes of a
   pair no `@Bridge` field of the class declares. Every other call in the class is refused: by name during the round,
   and again once javac has attributed the class, by the method each call or method reference resolves to, whatever its
   receiver. The runtime guard holds every call whose frame below the factory is not a static initialiser; the
   compile-time guard holds every call and reference written in the declaring class, a helper's included. A nested class
   is a class of its own on both sides.
5. **A field-declared bridge is its pair's own bridge,** as a `@Bridge` on a class is today: a mapper that reaches the
   pair as a nested field converts it through that bridge on both paths. One declaration per pair is allowed in a
   compilation, which is the rule carrier bridges already follow at run time. When the processor did not run, such a
   mapper converts the pair by its own rows instead, as with a `@Bridge` on a class; a `via` row naming the field holds
   the same in both cases.
6. **The generated mapper costs what today's `@Bridge` costs per call.** The factory returns an instance of a class the
   processor generates for the field, a subclass of `Mapper` or `ForwardMapper` whose `forward`, `backward` and `patch`
   each make one call through the bridge's `BRIDGE_FN` constant. A call through the field's `static final` constant
   resolves to the bridge as a call to `BRIDGE_FN` does, and the JIT inlines it. The lookup happens once, when the field
   is initialised. Every other method, `explain()`, `trace()` and the hooks among them, is the base class's own, and a
   hook returns a mapper of the base class around the same bridge.
7. **The per-field annotations are retired** once the field form compiles what they express: `@Rename`, `@Transform`,
   `@ViaMapper`, `@Constant`, `@Default` and `@Compute`, together with the `@Bridge` attributes that hold them and
   `drops`, with no deprecated aliases. The class form of `@Bridge` keeps `value`, `writeStrategy` and `lenient` for a
   pair declared with no rows.
8. **Only javac reads the rows.** On a compiler without the tree API the processor warns on each `@Bridge` field and
   generates nothing for it, and the runtime mapper runs, which the cross-path corpus holds to the same result.
9. **Which path runs is reported.** `explain()` and `trace()` on a mapper a field declares carry an `OpticNode.Declared`
   naming the generated bridge, or saying why the runtime path runs: the class was compiled without the tree API, or the
   bridge on the class path was generated from other rows. A class the processor never saw leaves no trace at run time,
   since `@Bridge` is retained only in source, so its mapper carries no such node.

> **Amendment (2026-10-23).** A provider whose recorded rows differ from the call's, in kind or in the accessors they
> name, is passed over rather than refused: the factory builds the runtime mapper from the rows it was given, logs a
> warning, and `explain()` says why. The rows are what the author wrote, so they are the declaration to honour. The note
> on a compiler without the tree API is a warning on each field, so it is seen where the field is. A named module that
> does not `provides` the generated bridges gets a mandatory warning naming the line to add, since `ServiceLoader`
> ignores `META-INF/services` there. A `to` row also takes one two-way conversion, a `BridgeFn` instance or its class,
> on both paths; a class is built through its no-argument constructor when the row is, as the generated bridge builds it
> when the bridge is initialised.

### Amendments to ADR-0018

- **Step 3** (conversion on a renamed field) is answered by a converting `to` row: the row names both properties and the
  converter, so no `target` attribute is added to `@Transform` or `@ViaMapper`.
- **Step 4** (values computed from the source) becomes a `compute` row that takes a function of the source, added to
  both paths together.
- **Step 5** (nested paths) is a row whose source is a telescope path written inline. Reading through a path is compiled
  first; writing through one follows.
- **Step 6** (update in place) is `Mapper.into` on the mapper a field declares, generated rather than written through
  reflection when that step lands.

## Consequences

- One declaration runs on both paths, so the two cannot drift. The cross-path corpus checks every shape it covers with a
  field-declared bridge against the runtime mapper built from the same rows.
- Every field name in a mapping is a method reference the compiler checks.
- A call site keeps working unchanged when the processor is removed; it runs the runtime mapper instead.
- Building a mapper costs one stack walk and one `ServiceLoader` scan more than today, once per mapper.
- The generated path needs no reflection configuration inside a native image: `ServiceLoader` providers are registered
  by the image builder, `StackWalker` is supported there, and the rows are built but not decoded, except a `constant` or
  `compute` row, which decodes its target accessor. The `NativeVerify` binary covers a field-declared mapper.
- A provider left on the class path by an IDE build that no longer runs the processor would answer for rows it was not
  generated from. The provider records the kind of each row and the accessors it names; on the JVM the factory compares
  both with the rows it is given, and builds the runtime mapper from those rows when they differ. What the comparison
  cannot see is a converter, supplier or constant replaced by another while the accessors stay the same, since a
  `Function` carries no name to compare. Inside a native image only the kinds are compared, because reading the
  accessors decodes each method reference; an image is built from one compilation, where the provider and the rows
  cannot disagree.

## Alternatives considered

- **Key the generated bridge by a fingerprint of the rows.** Rejected: computing the fingerprint at run time means
  decoding every accessor's `SerializedLambda`, which needs reflection configuration in a native image, and a converter
  is a plain `Function` with no identity to fingerprint, so two mappers that differ only in a converter would collide.
- **Have the user reference a generated constant (`Mappers_Generated.USER`).** Rejected as the default: it gives up the
  point of the design, one expression that runs either way. It stays possible, because the generated bridge class is
  public, for code that must fail to compile when the processor did not run.
- **Key the bridge by the line of the call.** Rejected: it depends on line tables, which a build may strip, and breaks
  for two calls on one line.
- **Rewrite the field initializer at compile time.** Rejected: rewriting another class's syntax tree is outside JSR-269
  and is what makes Lombok fragile.
