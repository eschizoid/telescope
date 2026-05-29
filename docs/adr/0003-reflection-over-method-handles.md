# ADR-0003: Stay on `java.lang.reflect`, not `MethodHandles`, on the runtime path

**Status:** Accepted · **Date:** 2026-05-29

## Context

The reflective runtime path — `Records` (cached `RecordComponent.getAccessor()` + canonical `Constructor`) and `Beans`
(cached getter `Method`s, plus the four `BeanWriter` strategies) — uses `Method.invoke` / `Constructor.newInstance` /
`Field.setAccessible`. Periodically someone suggests swapping to `MethodHandles` ("MH invoke is faster than
Method.invoke; modern JIT can fold it").

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
