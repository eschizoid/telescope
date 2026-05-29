# telescope — domain context

Names for telescope's domain concepts. (Architecture-review vocabulary — module, seam, depth — lives in the review
skill, not here.)

## Terms

### Focus constant

A generated `public static final Telescope<Owner, Field>` produced by `@Focus` (records) or `@BeanFocus` (POJOs) — one
per component/property. Reflection-free single-field navigation, with the field name checked at compile time. Deep field
paths compose compile-checked via `.then(...)`.

### Traversal constant (`each<Component>`)

A generated `public static final Telescope<Owner, Element>` emitted next to the Focus constant for a collection-shaped
component (`List` / `Set` / `Iterable`), defined as `<lens>.<Element>each()`. The element type is baked in by the
generator, so descending into a collection's elements is **compile-time checked and reflection-free**, and composes via
`.then(...)` like any Focus constant. This is the substrate a future fluent path navigator would build on. `Map` values
and `Optional` are planned follow-ons.
