# Effects

How one path lifts through async, all-or-nothing, short-circuit, and error-accumulating updates with a single method
change. [← back to README](../README.md)

The same path that powers `.update(...)` lifts through four effects with one method change: **async**,
**all-or-nothing**, **short-circuit**, and **error-accumulating**. Validate every email in a `Batch` and report all the
bad ones in one call? Two lines. Run an HTTP normalization call for every focused element with bounded concurrency? Pass
an `Executor`. The DSL writes the structural plumbing; you supply the per-element function.

Pick the method by the function you have — the type system picks the applicative. Chaining stages of different effects
is handled by the bridge methods on `Either` / `Validated`; see [Chaining stages](#chaining-stages).

## Picking the method

| Your function returns  | Call this              | You get back           | Semantics                        |
| ---------------------- | ---------------------- | ---------------------- | -------------------------------- |
| `A → A` (pure)         | `update(...)`          | `S`                    | total, synchronous               |
| `CompletableFuture<A>` | `updateAsync(...)`     | `CompletableFuture<S>` | sequence; any failure propagates |
| `Optional<A>`          | `updateOptional(...)`  | `Optional<S>`          | any empty propagates             |
| `Either<E, A>`         | `updateEither(...)`    | `Either<E, S>`         | short-circuit on first `Left`    |
| `Validated<E, A>`      | `updateValidated(...)` | `Validated<E, S>`      | accumulate every error           |

**Picking between `updateEither` and `updateValidated`:**

- Use **`updateEither`** when failures should _halt work_: parsers where a malformed root makes children meaningless,
  dependent stages, expensive per-element calls. Subsequent elements are never even called.
- Use **`updateValidated`** when you want _every_ problem reported: form validation (show the user every wrong field at
  once), batch quality reports, lightweight predicates over many elements. Every element is processed; failures are
  collected.

The difference is control flow, not just result shape. You can't recover short-circuit behavior by post-converting a
Validated result, and you can't recover all-errors reporting from an Either that stopped after the first failure.

**Exceptions vs typed failure.** `updateEither` / `updateValidated` do **not** catch exceptions. Typed failure is for
values your `fn` returns — `Either.left(...)`, `Validated.invalid(...)`; a thrown exception propagates raw to the
caller, exactly as it would from a plain `update`. And the short-circuit claims are literal control flow: `Either` and
`Optional` genuinely stop — `fn` is not invoked for any element after the first `Left` / `empty` — while `Validated` and
`CompletableFuture` process every element.

## The four effects, one at a time

Each effectful method works on its own. Pick the one that matches the function you have. The examples below share this
tiny domain:

```java
record Order(String id, String email) {}

record Batch(List<Order> orders) {}

// Reusable path declared once, used by every example below.
static final Telescope<Batch, String> ALL_EMAILS = Telescope.of(Batch.class).each(Batch::orders).field(Order::email);
```

**`updateAsync` — fan out, gather back.**

```java
// Hit an HTTP service to normalize every email in parallel. The future completes
// when every per-element future has completed; failures propagate.
final CompletableFuture<Batch> done = ALL_EMAILS.updateAsync(batch, normalizer::normalizeAsync);
```

The path navigation, the per-element future creation, and the structural rebuild collapse into one method call. The
naive alternative — `stream().map(CompletableFuture::supplyAsync).collect(toList())` followed by
`CompletableFuture.allOf(...)` followed by manual reconstruction of the `Batch` — is the boilerplate this replaces.

**`updateValidated` — collect every error.**

```java
record EmailError(String email, String reason) {}

final Validated<EmailError, Batch> result = ALL_EMAILS.updateValidated(batch, this::checkEmail);

return result.fold(this::respondBadRequest, this::save);

// The per-element predicate lives in a named method — easier to read, easier to test:
private Validated<EmailError, String> checkEmail(final String email) {
  if (!email.contains("@")) return Validated.invalid(new EmailError(email, "missing @"));
  return Validated.valid(email.toLowerCase());
}
```

Every bad email across the entire batch is reported, not just the first one. The applicative does the accumulation. The
user code never touches an error list directly.

**`updateEither` — short-circuit on the first failure.**

```java
record ParseError(String input, String message) {}

final Either<ParseError, Batch> result = ALL_EMAILS.updateEither(batch, EmailParser::tryParse);

return result.fold(this::respondError, this::save);
```

The first email that fails to parse wins; later emails aren't even called. Use this when the first failure is enough —
it's strictly cheaper than `updateValidated` because there's no accumulation.

**`updateOptional` — all-or-nothing.**

```java
// If any single email fails to mask (returns Optional.empty), the whole batch becomes empty —
// partial state is impossible.
final Optional<Batch> masked = ALL_EMAILS.updateOptional(batch, this::tryMask);
```

This is the right tool when a partially-updated structure would be a bug, not a feature.

## Bounded async

By default `updateAsync` invokes `fn` synchronously per focused element; concurrency is whatever the futures returned by
`fn` already had. To cap concurrent invocations, pass an `Executor`:

```java
try (final var pool = Executors.newFixedThreadPool(10)) {  // ≤10 concurrent fn invocations
  final CompletableFuture<Batch> done = path.updateAsync(batch, this::fetchAsync, pool);
  done.join();
}
```

`fn` is wrapped in `CompletableFuture.supplyAsync(..., pool)`, so the executor caps how many `fn` invocations run
concurrently — it does not cap operations in flight behind futures `fn` returns. If `fn` merely starts an async call
(`HttpClient.sendAsync`), the pool thread is released as soon as the future is returned and every element's call may be
in flight at once. To make the pool size a true in-flight bound, do the blocking work inside `fn` and return a completed
future — or apply back-pressure inside `fn` (a `Semaphore`, a rate limiter). `EffectsConcurrencyTest` pins both
behaviors.

## Working with `Either` and `Validated`

`Either<L, R>` and `Validated<E, A>` are sealed records shipped with the library, no Vavr/Arrow dependency. The typical
handler is `.fold(...)`:

```java
return parsed.fold(this::respondError, this::save);
```

Pattern matching also works when you need to destructure the value, but Java's inference can't elide the type parameters
in switch arms, so `.fold(...)` is usually less noisy:

```java
return switch (parsed) {
  case Either.Right<ParseError, Company>(var c) -> save(c);
  case Either.Left<ParseError, Company>(var err) -> respondError(err);
};
```

Both `Either` and `Validated` expose the same compact handler API:

| Method                                           | Notes                                                                                               |
| ------------------------------------------------ | --------------------------------------------------------------------------------------------------- |
| `fold(onLeft, onRight)`                          | Collapse both sides into a single value. Usually what you want.                                     |
| `map(f)`                                         | Transform the success side; failure passes through.                                                 |
| `isLeft()` / `isRight()`                         | Boolean tests, when a `switch` would be overkill.                                                   |
| `mapLeft(f)` (Either)                            | Transform the failure side; useful for normalizing error types at a boundary.                       |
| `mapErrors(f)` (Validated)                       | Same idea as `mapLeft`, applied to every accumulated error.                                         |
| `swap()` (Either)                                | Flip left and right.                                                                                |
| `flatMap(f)` (Either)                            | Sequence two Eithers; short-circuits on the first `Left`.                                           |
| `andThen(f)` (Validated)                         | Sequence two Validateds; short-circuits on `Invalid` (use `combine` to accumulate).                 |
| `Validated.combine(a, b, f)` (Validated, static) | Combine two Validateds; accumulates errors across both branches.                                    |
| `toValidated()` (Either)                         | Bridge to `Validated`: `Left(e)` becomes a single-element `Invalid([e])`.                           |
| `toEither()` (Validated)                         | Bridge to `Either`: `Invalid(errs)` becomes `Left(errs)`.                                           |
| `flatMapAsync(f)` (both)                         | Sequence an async stage; failures stay in the result, only success runs.                            |
| `toOptional()` (both)                            | Drop the error and bridge to JDK `Optional`. Use when downstream only cares about the success path. |
| `getOrElse(default)` (both)                      | Return the success value, or `default` on failure.                                                  |
| `getOrElseGet(supplier)` (both)                  | Same, with a lazy default for expensive cases.                                                      |
| `combineAll(List<…>)` (Validated, static)        | Combine a list of validations into a `Validated<E, List<A>>`; accumulates every error.              |

## Chaining stages

Multi-stage flows use the bridge methods on `Either` / `Validated` to keep the error channel consistent across different
effects. The pattern is: normalize each stage's error type with `mapErrors` / `mapLeft`, bridge between accumulating and
short-circuiting with `toEither` / `toValidated`, then `flatMap` / `andThen` for sync stages or `flatMapAsync` when the
next stage returns a `CompletableFuture`.

Sync-only example — validate emails, then look up users, with one unified `List<String>` error channel:

```java
// Stage 1: collect every bad email, then hand off to short-circuit code
// → Either<List<String>, Batch>
final Either<List<String>, Batch> afterEmails = emailPath
  .updateValidated(batch, this::checkEmail)
  .mapErrors(EmailError::reason) // EmailError -> String
  .toEither(); // accumulating -> short-circuit

// Stage 2: short-circuit on the first user lookup failure, normalize its error too
// → Either<List<String>, Batch>
final Either<List<String>, Batch> afterUsers = afterEmails.flatMap((b) ->
  userPath.updateEither(b, this::lookupUser).mapLeft((err) -> List.of(err.id() + " not found"))
);
```

Crossing into an async stage uses `flatMapAsync`, which mirrors `flatMap` but accepts a function returning a
`CompletableFuture`. Errors remain in the `Either` (or `Validated`) result; only the success side runs asynchronously:

```java
return afterUsers.flatMapAsync(ok -> enrichPath.updateAsync(ok, this::enrich));
// → CompletableFuture<Either<List<String>, Batch>>
```
