# telescope — domain context

Names for telescope's domain concepts. (Architecture-review vocabulary — module, seam, depth — lives in the review
skill, not here.)

## Terms

### Path navigator (`<X>Path<R>`)

A generated fluent typed navigator emitted by `@Focus` (records) or `@BeanFocus` (POJOs), one per annotated type. The
class is parameterised by the navigation **root** `R` and wraps a current `Telescope<R, X>`. Static `start()` roots a
navigator at `Telescope.of(X.class)`; instance methods descend per component/property (sub-record/-bean → next
`<Sub>Path<R>`, scalar → terminal `Telescope<R, T>`); `get()` returns the current `Telescope` at any hop.

A path itself is _not_ a `Telescope` — it's a fluent builder. Every leaf method returns a `Telescope<R, X>`, the same
value the reflective DSL would produce, so the navigator and the reflective DSL produce interchangeable terminals.
End-to-end compile-time type-checked; method bodies use only `Telescope.lens(getter, setter)` plus the no-arg `.each()`
— fully reflection-free for the same surface `@Focus`/`@BeanFocus` covered before the navigator.

### Bridge hop (`as<Target>()`)

A generated navigator method emitted on `<X>Path<R>` when the annotated type `X` is also the source of an `@Bridge`
(i.e. `@Bridge(Target.class) X` exists). The method chains `XBridge.BRIDGE` onto the current path, crossing to the
target type in a single fluent hop. Returns `<Target>Path<R>` when the target is itself navigable (carries `@Focus` if
it's a record or `@BeanFocus` if it's a POJO), otherwise terminal `Telescope<R, Target>`. The hop crosses paradigms
naturally — a record source can bridge to a POJO target's Path, and vice-versa — so the navigator becomes a single
compile-checked surface for navigation _and_ conversion. Forward direction only today; the reverse direction (target's
Path getting `.asSource()`) stays expressible via `.then(XBridge.BRIDGE)` or `BRIDGE.reverse()`.

### Container step (`<X><Cap>Step<R>`)

A generated step class emitted alongside `<X>Path<R>` for each collection-shaped component of `X` (List/Set/Iterable,
Map, Optional). The step is shaped as `Telescope<R, ContainerType>` and exposes `.get()` plus the matching
container-traversal method — `.each()` (List/Set/Iterable), `.eachValue()` (Map values; keys preserved), or
`.whenPresent()` (Optional) — which returns the element's `<Elem>Path<R>` when the element is itself annotated, or a
terminal `Telescope<R, Element>` otherwise. The runtime dispatch is `Traversals.eachContainer` — `instanceof`-based, not
reflective.
