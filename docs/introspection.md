# Introspection — see what a mapper does (`explain()` / `trace()`)

Every mapper and navigator can describe its own structure statically, or narrate one conversion with values filled in.
[← back to README](../README.md)

Every mapper — and every `Telescope` navigator — can describe itself. Where MapStruct's only record of a mapping is the
generated source, telescope makes the mapping a first-class value that answers two questions:

- **`explain()`** — the _static_ structure: which fields correspond, which were skipped (and why), which change type. No
  input needed.
- **`trace(input)`** — the same rows with the _values_ for one conversion filled in. `trace(input)` executes the
  conversion — pre/post hooks run.

```java
final Mapper<UserDto, User> mapper = Telescope.mapper(UserDto.class, User.class,
    Mapping.to(UserDto::firstName, User::givenName),
    Mapping.to(UserDto::birthDate, User::birthDate, LocalDate::parse, LocalDate::toString),
    Mapping.drop(UserDto::id));

System.out.println(mapper.explain());
// Mapped:
//   ✓ firstName         → givenName
//
// Skipped:
//   • id                (ignored)
//
// Transformations:
//   • birthDate(String) → LocalDate

System.out.println(mapper.trace(new UserDto("Ada", "2020-01-02", 7L)));
//   ✓ firstName  "Ada"         → givenName "Ada"
//   • birthDate  "2020-01-02"  → birthDate LocalDate[2020-01-02]
//   • id                       → (ignored)
```

The left column is aligned across every section (the widest cell sets the width), so markers, fields, and each `→` line
up as one table.

**The render is a view; the data is the API.** `explain()` returns an `OpticReport` you assert on directly — pull a
typed slice instead of scraping text:

```java
// completeness test — every field on both sides accounted for
assertThat(mapper.explain().skipped()).isEmpty();
assertThat(mapper.explain().unusedSources()).isEmpty();
assertThat(mapper.explain().mapped()).contains(new Mapped("firstName", "givenName"));
```

For a strict bidirectional mapper, `explain().skipped().isEmpty() && explain().unusedSources().isEmpty()` means every
field on both sides is accounted for. Constant/computed target slots are populated, not skipped — they don't appear as
rows; container-typed fields report as one row, not per element; forward-only transforms render as `Transformed` without
a direction marker.

Slices: `mapped()`, `transformations()`, `skipped()`, `unusedSources()`, and `hops()` (for a navigator's path).

## Auto-logging — flip a level, see every mapping

You don't have to call `explain()` / `trace()` by hand. Each mapper logs its own introspection through
`java.lang.System.Logger` (java.base — zero dependency, routes to whatever backend your app already runs):

- **`DEBUG`** — `explain()` once, when the mapper is built.
- **`TRACE`** — `trace(input)` on every `forward()`.

Loggers are named by type pair, so you enable one mapper or the whole library from your existing config — no code
change. In Spring Boot `application.properties`:

```properties
logging.level.io.github.eschizoid.telescope.mapper.UserDto.User=TRACE   # one mapper, values per conversion
logging.level.io.github.eschizoid.telescope.mapper=DEBUG                 # every mapper's structure at build
```

or directly in `logback.xml`:

```xml
<logger name="io.github.eschizoid.telescope.mapper.UserDto.User" level="TRACE"/>
<logger name="io.github.eschizoid.telescope.mapper" level="DEBUG"/>
```

The log calls are always present and gated purely by level, so they cost nothing when off (guarded before the message is
ever built). `<Source>` / `<Target>` are simple class names. One backend nuance: through Spring Boot's default
`jul-to-slf4j` bridge both lines render at `DEBUG` (the bridge maps `System.Logger.TRACE` onto SLF4J `DEBUG`); the level
threshold still separates them — `DEBUG` shows structure, `TRACE` adds the per-conversion values.
