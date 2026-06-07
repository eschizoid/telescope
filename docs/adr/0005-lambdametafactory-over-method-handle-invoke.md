# ADR-0005: LambdaMetafactory-built functional interfaces, not raw MethodHandle dispatch

**Status:** Accepted (refines [ADR-0003](0003-reflection-over-method-handles.md)) · **Date:** 2026-06-07

## Context

[ADR-0003](0003-reflection-over-method-handles.md) rejected swapping the runtime path from cached `java.lang.reflect`
over to `MethodHandles`. The argument was specifically about **`MethodHandle.invoke` per-call dispatch** — its inflation
behaviour and JPMS lookup friction didn't pay for themselves when codegen was the real hot-path answer.

That argument doesn't cover `LambdaMetafactory`. `LambdaMetafactory.metafactory(...)` synthesizes a small
SAM-implementing class whose `apply` / `accept` / `get` method calls the underlying `MethodHandle` **directly**, not
through `MethodHandle.invoke`. After the metafactory call returns, the cached `Function<P, R>` / `BiConsumer<P, V>` /
`Supplier<T>` is an ordinary functional interface as far as the JIT is concerned — the same kind of object
`Pojo::getName` would yield as a method-reference literal — and the JIT can inline through it.

The reflective discovery substrate (find the `Method` / `Constructor` / `Field` by name + signature) is unchanged. Only
the hot-path dispatch primitive is.

## Decision

Migrate the runtime hot path from `Method#invoke` / `Constructor#newInstance` to **`LambdaMetafactory`-built functional
interfaces**, built once per discovered member and cached alongside the existing reflective metadata. The reflective
discovery substrate stays.

Phased rollout (one PR per phase, all `:core`, all pure substrate swaps with no public API change):

- **Phase 1 (#9):** Record-component readers in `Records.RecordInfo` — one `Function<Object, Object>` per component,
  built from `RecordComponent.getAccessor()`.
- **Phase 2 (#12):** Bean getter invokers in `Beans.GETTER_INVOKERS` — one `Function<Object, Object>` per resolved
  getter.
- **Phase 3 (#11):** Bean setter invokers in `Beans.SetterWriter` — `BiConsumer<Object, Object>` per setter, with
  `instantiatedMethodType` pinned to the wrapper type so LMF inserts the auto-unbox adapter for primitives.
- **Phase 4 (#13):** Builder writers in `Beans.BuilderWriter` — `Supplier` for the factory, `BiFunction` per fluent
  setter (not `BiConsumer`: LMF rejects the `changeReturnType(void.class)` adaptor as non-direct), `Function` for
  `build()`.
- **Phase 5 (#14):** Rebuild paths — canonical record `Constructor` and the no-arg-ctor + `Field` writers, plus
  `Records.construct(...)`.

## Consequences

- **Perf delta is real, not the marginal one ADR-0003 measured.** Cached LMF functional interfaces let the JIT inline
  through `Function.apply` the same way it inlines a method reference. The dispatch primitive itself stops being the
  cost line — only structural rebuild + value-read costs remain. Updated benchmark numbers ship with each phase PR.
- **JPMS opens requirement is the same as before, no worse.** `MethodHandles.privateLookupIn(target, lookup())` carries
  the same access rules as `setAccessible(true)`: fully-public same-module targets need nothing; closed-package targets
  need an `opens` directive. The thrown `IllegalStateException` spells out the exact directive. No fragile
  `IMPL_LOOKUP`-via-`Unsafe` trick.
- **First-call cost shifts to cache warm-up.** Each LMF bind is more expensive than the first `Method.invoke`, but it
  happens once per `(class, member)` pair behind the `ClassValue` / `ConcurrentHashMap` cache. Steady state is
  unambiguously faster.
- **Class-load footprint grows.** One synthetic class per cached member. Acceptable: bounded by the navigated surface,
  and `ClassValue` keeps the cache from pinning classloaders.
- **No public API change.** All five phases are pure substrate swaps inside `internal/`. No `module-info` change.
  Codegen-generated navigators are untouched.
- **Codegen still leads.** `@Focus` / `@BeanFocus` / `@Bridge` emit direct method calls — no metafactory bind, no cache
  lookup, no synthetic class. The runtime path now lives much closer to codegen than to reflective `Method.invoke`, but
  the gap remains and the recommendation stays "use codegen on hot paths."

## Alternatives considered

- **Raw `MethodHandle.invoke` dispatch.** Rejected by ADR-0003 and rejected again here: per-call indirection costs
  without the JIT-inlinable functional-interface shape, plus the same JPMS friction.
- **Pure codegen everywhere, drop the runtime path.** Rejected. The runtime path is the zero-build-config entry point
  and the basis for `.fieldByName(String)`. See [ADR-0004](0004-runtime-and-codegen-strategy-separate.md) for why we
  keep both strategies separate.
- **Do nothing.** Rejected. `Method.invoke` was the dominant hot-path cost for navigators that don't opt into codegen;
  benchmarks pinned it as the lever worth pulling without changing the API surface.
