# ADR-0003: Stay on `java.lang.reflect`, not `MethodHandles`, on the runtime path

**Status:** Accepted · **Date:** 2026-05-29

## Context

The reflective runtime path — `Records` (cached `RecordComponent.getAccessor()` + canonical `Constructor`) and `Beans`
(cached getter `Method`s, plus the four `BeanWriter` strategies) — uses `Method.invoke` / `Constructor.newInstance` /
`Field.setAccessible`. Periodically someone suggests swapping to `MethodHandles` ("MH invoke is faster than
Method.invoke; modern JIT can fold it").

> **Amendment (2026-09-10, after ADR-0005 and ADR-0015).** The figures below predate both. ADR-0005 narrowed this
> decision — reflection stays for discovery, while the hot-path dispatch primitive became a `LambdaMetafactory`-built
> functional interface — and ADR-0015 qualified it under AOT, where `MhAccessors` reaches record components, bean
> properties and constructors through `MethodHandle.invokeExact` because LMF cannot define a class inside an image. The
> exception is not AOT-only: on a stock JVM, field writes (`putField`) and every canonical-constructor rebuild — records
> and the all-args bean constructor alike — also go through cached `MethodHandle`s, because LMF binds only direct method
> or constructor handles. A field setter is a direct handle of the wrong kind; the spread adapter is not a direct handle
> at all. The rule underneath is that LMF is the dispatch primitive wherever it can bind, cached `MethodHandle`s cover
> the rest on every runtime, and AOT only widens which cases fall into "cannot bind". What still binds from this ADR is
> its rejection of raw per-call `MethodHandle.invoke` as the dispatch primitive, which ADR-0005 rejects again, on the
> added ground that raw dispatch lacks the JIT-inlinable functional-interface shape. Read the numbers here as the state
> before that work.

## Decision

Stay on cached `java.lang.reflect`. Do not migrate to `MethodHandles` for runtime field/property/constructor access.

## Reasons

1. **The speed delta is marginal in practice.** Modern HotSpot inlines reflective access through the per-call inflation
   path almost as well as MH; the measured per-field overhead is dominated by _value_ read + structural rebuild, not by
   the dispatch primitive.
2. **`MethodHandles.Lookup` interacts with JPMS.** `Lookup.unreflect`/`findVirtual` needs a `Lookup` with the right
   privileges for the target's module. The trick that makes this portable (`Lookup.IMPL_LOOKUP` via deep reflection on
   `Unsafe`) is fragile across JVMs and may break under future JEPs. Plain reflection only needs `setAccessible` plus
   the standard `opens` directive, which we already document.
3. **The actual hot-path win comes from codegen** (`@Focus` / `@BeanFocus` / `@Bridge`), which compiles direct calls and
   sidesteps reflection entirely — see the benchmarks: generated `@Bridge` is ~14.9 ns vs runtime `mapBean` ~142 ns
   (~9.5x). Swapping the runtime reflection primitive would not approach that.

## Consequences

- The runtime path stays simple and stable across JPMS configurations.
- Performance work is invested in **codegen breadth** (more annotations, more shapes) rather than in optimising the
  reflective fallback.
