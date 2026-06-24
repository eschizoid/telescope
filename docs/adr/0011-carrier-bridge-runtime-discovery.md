# ADR-0011: Runtime discovery of carrier-form `@Bridge` via a `ServiceLoader` SPI

**Status:** Accepted · **Date:** 2026-06-24

## Context

Two features shipped independently and were never connected:

- **Carrier-form `@Bridge`** (ADR-0007): `@Bridge(source = A.class, target = B.class, …)` on a third "carrier" class,
  emitting `<Carrier>Bridge.BRIDGE` in the **carrier's** package. ADR-0007 explicitly rejected emitting into the
  source's package — a JSR-269 processor in the carrier's module can't write source files into a package the source's
  module owns without `--add-modules` gymnastics.
- **`mapperForward(A, B)` auto-discovery**: zero-row `mapperForward` probes for a sibling `<Source>Bridge` /
  `<Source>To<Target>Bridge` constant and routes through it when present. The probe (`BridgeHolderProbe`) derives those
  names purely from the source class, so it only ever looks **in the source's package**.

The two disagree on both the name (`<Carrier>Bridge` vs `<Source>[To<Target>]Bridge`) and the package (carrier's vs
source's). So a carrier-form bridge consumed via zero-row `mapperForward` was never found: the probe missed, and
`mapperForward` — lenient by default — silently fell through to same-name mapping, dropping the carrier's `@Rename`s. A
renamed field came back null with no error.

A name-only fix (rename the carrier bridge to the long-form convention) doesn't work: the probe still does
`Class.forName` in the source's package, and ADR-0007 forbids emitting the carrier bridge there. A source-package-keyed
probe fundamentally cannot locate a class in the carrier's package by name.

## Decision

Add a package-agnostic discovery path: a `BridgeProvider` service-provider interface in
`io.github.eschizoid.telescope.conversion`, and a `BridgeRegistry` lookup keyed by `(source, target)` over
`ServiceLoader`.

- The `BridgeProcessor` carrier path emits a sibling `<Carrier>BridgeProvider implements BridgeProvider` (public no-arg
  constructor for `ServiceLoader`; `sourceType()` / `targetType()` / `bridge()` returning `<Carrier>Bridge.BRIDGE`) and
  registers it in `META-INF/services/…BridgeProvider`, accumulated across rounds and written once in `processingOver()`.
- `mapperForward(A, B)` with zero rows consults `BridgeRegistry.find(source, target, loader)` **after** the name probe
  misses and **before** the lenient `DeepMap` default. Emission stays in the carrier's package (ADR-0007 unchanged); the
  registry finds it by pair, not by name.
- The model-anchored form is untouched — it's already name-discoverable in the source's package, so it emits no provider
  and no services file. Only carrier bridges register.

Loud, never silent: `BridgeRegistry.find` throws on a provider with a null bridge constant, and on two providers
claiming the same pair. It does **not** throw when no provider exists — that is the legitimate "no bridge declared"
case, where `mapperForward`'s lenient-by-default same-name mapping (Enh 9) is the intended behavior. A _declared_
carrier bridge can no longer be silently skipped, because it is registered and found; an _absent_ bridge keeps the
documented lenient default. This is the distinction that lets the fix close the silent-failure hole without regressing
Enh 9.

## Consequences

- **Carrier-form `@Bridge` is now auto-discoverable by zero-row `mapperForward`**, across packages and modules. This
  supersedes ADR-0007's stated limitation that adopters must consume carrier bridges via the explicit
  `<Carrier>Bridge.BRIDGE` constant — that path still works, but is no longer the only one.
- **JPMS-clean.** Discovery is `ServiceLoader`-based; `:core`'s `module-info` declares `uses BridgeProvider`. Class-path
  consumers register through `META-INF/services` (the generated file) with no module-info edits. Module-path consumers
  whose generated providers live in a named module add `provides … with …` to that module's descriptor — the standard
  service-registration step, documented as the modular caveat.
- **The codegen test harness now captures resource outputs in memory.** `ProcessorHarness` intercepts `getFileForOutput`
  so the generated `META-INF/services` file is assertable and never escapes to disk under `-proc:only`.
- **One extra generated class per carrier bridge.** A small, self-contained provider; the model-anchored form pays
  nothing.

## Alternatives considered

- **Rename the carrier bridge to the long-form convention, keep the carrier package.** Rejected — closes the name gap
  but not the package gap; the probe still looks in the source's package, so a cross-package carrier (the whole point of
  the form) is still a miss. Only appears to work when the carrier shares the source's package, re-arming the
  silent-fallback footgun.
- **Emit a long-form alias in the source's package delegating to the carrier bridge.** Rejected — same package problem,
  and ADR-0007 already rejected source-package emission for the carrier form.
- **Loud-fail whenever zero-row `mapperForward` finds no bridge.** Rejected — would regress Enh 9's lenient-by-default
  contract, where same-name mapping with no bridge is intended. The registry makes the loud case unnecessary for
  declared carriers and scopes the remaining loud-fail to malformed/ambiguous providers.
