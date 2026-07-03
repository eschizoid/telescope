# ADR-0012: Compile-time mapper verification via a shared pairing spec

**Status:** Accepted · **Date:** 2026-06-30

## Context

Telescope's runtime conversion factories — `Telescope.map`, `Telescope.mapper`, `Telescope.mapperForward`,
`Telescope.fromMap` — validate their field pairing **at construction time**: the strict bijection guard, the
incompatible-shapes rejection, and the eager `WriteHint` validation all throw a precise `IllegalStateException` /
`IllegalArgumentException` when the mapper value is first built. Loud and early, but still runtime. MapStruct's core
trust contract is stronger: _if it compiles, every mapping is complete._ Telescope only delivers that on the opt-in
`@Bridge` codegen path — the ergonomic API everyone actually reaches for is compile-silent about a drifted field. That
remains the one dimension where MapStruct's architecture is safer by default. It is the last such concession.

Closing it requires checking **call sites** (`Telescope.mapper(A.class, B.class, rows…)` inside method bodies), which
plain JSR-269 cannot see — annotation processors receive declared elements, never expression trees. And any checker
re-encodes `DeepMap`'s pairing rules (same-name matching, primitive↔wrapper, container-shape compatibility, nested
recursion, write-strategy resolution) in `javax.lang.model` terms; two hand-maintained copies of those rules would
drift, and with error-severity diagnostics a drifted rule is a user's build broken by a false positive.

## Decision

Two coupled decisions, one feature.

**1. A call-site verifier in `:codegen`, built on JSR-269 + the Compiler Tree API.** `MapperVerifierProcessor` claims
`"*"`, obtains `Trees.instance(processingEnv)`, and walks every root element's tree for invocations of the four
factories. From class-literal arguments and statically-recognizable `Mapping` / `WriteHint` row factories (method
references resolve to real `ExecutableElement`s — the compile-time twin of the `SerializedLambda` decode), it replays
the construction-time pairing decisions and reports violations as **compile errors anchored on the offending row
expression**, with the same diagnostic text the runtime throws. On a non-javac compiler (`Trees` unavailable) the
processor prints one NOTE and no-ops: verification is additive, never load-bearing — the construction-time check remains
the backstop, so a skipped site is exactly as safe as today.

**2. One rule set, shared: `PairingRules<P>` in `:internal`.** The pairing decision rules are extracted from `DeepMap`
into a property-model-parameterized spec — `ReflectionProps` adapts `Class`/reflection for the runtime, `MirrorProps`
adapts `TypeMirror`/`Elements` for the verifier. `DeepMap` delegates its decisions to the shared spec with zero behavior
change (the existing conversion test suite is the parity pin); the verifier consumes the same spec, so drift between
"what compiles" and "what constructs" is impossible by construction. This **refines ADR-0004**: runtime and codegen
remain separate _strategies_ (separate rebuild paths, separate dispatch), but they now share one _decision-rule spec_.
The rules run at build/construction time only — never on the per-`forward()` hot path — so the abstraction costs the
runtime nothing where it matters.

Behavioral contract:

- **Zero ceremony, on by default.** Adding `telescope-codegen` as an annotation processor verifies every call site in
  the module. No new annotation required to opt in. `-Atelescope.verify=warn|off` globally; `@UncheckedMapping(reason)`
  exempts a site.
- **Error severity by default.** The verifier only fires on shapes the runtime would reject at construction — it can add
  errors for provably-broken code, never make broken code falsely pass.
- **Verify what is visible; never guess.** Non-literal class arguments skip the site. A dynamic row (helper call, spread
  array) disables only the completeness check; every statically-visible row is still shape-checked. Skips are silent
  (NOTE under `-Atelescope.verify.verbose`).
- **Compositional `via(…)`.** A `via` row claims its field pair and is checked for accessor/mapper type compatibility at
  the use site; the referenced mapper's own completeness is verified where _it_ is constructed.

## Consequences

- **"If it compiles, the mapping is complete" now holds on the ergonomic API** — for statically-analyzable sites, which
  is the overwhelmingly common shape (class literals + inline rows). The last "MapStruct is safer by default" gap closes
  without a new user-facing API.
- **JPMS graph change:** `:internal` adds a qualified export of the pairing package to `:codegen`
  (`exports …internal.pairing to …core, …codegen`), and `:codegen` gains a `compileOnly` dependency on `:internal`. The
  lattice packages stay unexported; classpath consumers still cannot reach the pairing spec from their own code.
- **ADR-0004 refined, not reversed:** rebuild strategies stay separate; only the pairing _decisions_ unify. Any future
  change to pairing behavior lands in one place and both worlds pick it up.
- **`"*"` processing makes the processor aggregating** under Gradle incremental annotation processing. The `:codegen`
  processors are not registered as incremental today, so consumers already forfeit incremental AP; this makes the
  existing posture explicit rather than regressing it. Registering the suite as incremental is future work that would
  need the verifier to become annotation-scoped or tree-cache-aware.
- **javac-only depth.** ECJ/Eclipse users compile fine but get construction-time checking only (one NOTE says so).
  In-IDE red squiggles appear on build (processor-driven), matching MapStruct's own IDE story.

## Alternatives considered

- **Parity test corpus instead of extraction** — keep two rule copies, pin agreement with twin tests asserting identical
  diagnostics. Cheaper up front, but drift is prevented by test discipline rather than by construction, and with
  error-severity diagnostics the cost of a missed twin is a falsely-broken user build. Rejected in favor of the
  structural guarantee.
- **ErrorProne `BugChecker`.** Sees call sites cleanly, but adds a third-party compiler dependency against the
  no-new-deps posture, and fragments the processor story (`:codegen` already owns compile-time telescope).
- **Opt-in `@VerifiedMappings` trigger.** Incremental-friendly and cheap, but reintroduces exactly the ceremony the
  feature exists to remove — "annotate to get what MapStruct gives by default" concedes the headline.
- **Generation instead of verification** (emit the mapper body at compile time from the call site). A far bigger lift
  that collides with ADR-0004's separation for real this time, and unnecessary: construction-time resolution already
  performs well (ADR-0005/0006); the missing piece was only the compile-time _check_.
