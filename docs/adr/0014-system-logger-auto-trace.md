# ADR-0014: Auto-logging explain()/trace() via `System.Logger`

**Status:** Accepted · **Date:** 2026-07-05

## Context

ADR-0013 shipped `explain()` (static structure) and `trace(input)` (executed, with a value column). Both are _explicit_
— to see what a mapper does you must edit code (`System.out.println(mapper.trace(input))`). The common debugging need is
the opposite: **see what every mapper does without touching code**, by flipping a log level.

The blocker was always "core has no logging dependency." But `java.lang.System.Logger` (JDK 9+) is in **java.base** —
zero dependency, no `module-info` change — and delegates to whatever backend the application already runs (JUL by
default, or Logback / Log4j2 / JBoss Logging via a `System.LoggerFinder`). It is the correct facade for a lean JPMS
library, and its `log(Level, Supplier<String>)` overload only evaluates the message when the level is enabled.

## Decision

`Mapper` and `ForwardMapper` auto-log their own introspection through a `System.Logger`, mapping 1:1 onto log levels:

- **DEBUG — `explain()` once, at construction.** The static structure (fields + types). Skipped for trail-less lifted
  shells (list/set/optional/map-value), which would only log `(empty optic)`.
- **TRACE — `trace(input)` per `forward()`.** The value column for that conversion, built from the **already-computed
  result** (never re-running `forward`, which would recurse through `trace`).

Both calls are guarded by an explicit `isLoggable` check (plus a non-empty-trail check) _before_ the `Supplier` is
built, then use the lazy `log(Level, Supplier)` overload. So when the level is off the hot path is fully allocation-free
— not even the capturing lambda is created — and the render never runs. This is the "off the hot path" guarantee
ADR-0013 wanted, now automatic.

**Always-on, level-gated (not opt-in).** The log calls are always present; the log _level_ is the switch. This is what
"flip a level, see it" demands, and it is free when off. `forward()` gains a benign, level-gated log side-effect — the
Hibernate-logs-SQL-at-DEBUG posture — which we accept over an opt-in `mapper.logged()` view.

**Hierarchical, type-pair logger names.** `io.github.eschizoid.telescope.mapper.<Source>.<Target>` (both `Mapper` and
`ForwardMapper` share the `.mapper.` namespace). A user enables one mapper, or the whole library by prefix, from their
existing logging config — no telescope-specific configuration and no code change. `<Source>` / `<Target>` are the
**simple** class names (`Class#getSimpleName()`) — chosen for readable, greppable logger names over verbose FQNs. The
trade-off: two mappers whose source (or target) types share a simple name across different packages land on the same
logger, so they can't be level-controlled independently. Distinct simple names — the common case — get independent
control; on a genuine clash, enable the shared logger (both fire) or the `.mapper.` prefix.

**Navigation (`Telescope`) is out of scope for v1.** A navigator is built fluently (no single construction point for a
DEBUG `explain`, and every intermediate `.field(...)` would log) and does not retain `Class<S>`, so there is no clean
logger name to hang it on. `Telescope.trace(input)` / `explain()` stay explicit-only. Auto-logging navigation is a
deferred follow-up if a good naming story appears.

## What it looks like

```properties
# logback.xml / logging.properties — no code change:
io.github.eschizoid.telescope.mapper.UserDto.User = TRACE   # one mapper, values per conversion
io.github.eschizoid.telescope.mapper               = DEBUG   # every mapper's structure at build
```

```
DEBUG io...mapper.UserDto.User - map UserDto → User
Mapped:
  ✓ firstName → givenName
...
TRACE io...mapper.UserDto.User - ✓ firstName "Ada" → givenName "Ada"
  • birthDate "2020-01-02" → birthDate LocalDate[2020-01-02]
```

## Consequences

- **Zero new dependency.** `System.Logger` is java.base; no `module-info` change; routes to the app's backend.
- **Benign side-effect in `forward()`.** Guarded and free when off. `forward()` is no longer strictly pure, by
  deliberate choice — the level is the opt-in.
- **Verbosity is bounded.** A mapper in a tight loop at TRACE emits one trace per call; `TraceLimits` defaults already
  cap the per-fan-out breadth/depth of each render.
- **Extends ADR-0013**, does not change its API — `explain()` / `trace()` keep their exact signatures; logging is purely
  additive.
- **SLF4J-via-`jul-to-slf4j` renders both lines at DEBUG.** With no `System.LoggerFinder` on the path (the common Spring
  Boot setup), `System.Logger` routes through `java.util.logging`, and the `jul-to-slf4j` bridge maps `TRACE` (JUL
  `FINER`) onto SLF4J/Logback **DEBUG** — only JUL `FINEST` becomes SLF4J `TRACE`, and `System.Logger` cannot emit below
  `TRACE`. So through that bridge both lines render at `DEBUG`. The **configured threshold still separates them** —
  that's the adopter-facing knob: at `DEBUG` only the structure fires (`isLoggable(TRACE)` is false), and raising the
  logger to `TRACE` additionally fires the per-forward values. An adopter who wants the two lines to carry distinct
  SLF4J levels installs the direct `slf4j-jdk-platform-logging` provider (a `System.LoggerFinder`), bypassing JUL.
  Pinned end-to-end by `MapperLoggingRoutingTest` in the `org-chart` demo.
