package io.github.eschizoid.telescope.examples;

import io.github.eschizoid.telescope.Either;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.Validated;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

/**
 * Exercises the four effectful-update terminals: {@code updateAsync}, {@code updateOptional},
 * {@code updateEither}, {@code updateValidated}. All four go through the same internal {@code
 * Traversal#modifyF} via a per-effect {@code Applicative} witness; users never type {@code Kind} or
 * {@code Applicative}.
 */
final class EffectfulUpdateDemo {

  private EffectfulUpdateDemo() {}

  static void main() {
    run();
  }

  record User(String name, int age, String email) {}

  record Team(String name, List<User> users) {}

  record ParseError(String field, String message) {}

  static void run() {
    final var team = new Team("core", List.of(new User("alice", 30, "ALICE@X"), new User("bob", 25, "BOB@Y")));

    asyncSameThread(team);
    asyncWithExecutor(team);
    optionalShortCircuit(team);
    eitherRightAndLeft(team);
    validatedAccumulatesErrors(team);
  }

  // updateAsync with a same-thread future: every focused value is lifted through CompletableFuture
  // and the rebuild completes in the calling thread.
  private static void asyncSameThread(final Team team) {
    final var emails = Telescope.of(Team.class).each(Team::users).field(User::email);
    final var future = emails.updateAsync(team, email -> CompletableFuture.completedFuture(email.toLowerCase()));
    final Team result;
    try {
      result = future.get();
    } catch (final InterruptedException | ExecutionException e) {
      throw new IllegalStateException("async update failed", e);
    }
    System.out.println("[updateAsync] same-thread    : " + result);
  }

  // updateAsync with an Executor overload — fn runs on the supplied pool.
  private static void asyncWithExecutor(final Team team) {
    final var executor = Executors.newSingleThreadExecutor(r -> {
      final var t = new Thread(r, "demo-async");
      t.setDaemon(true);
      return t;
    });
    try {
      final var emails = Telescope.of(Team.class).each(Team::users).field(User::email);
      final var future = emails.updateAsync(
        team,
        email -> CompletableFuture.supplyAsync(email::toLowerCase, executor),
        executor
      );
      final var result = future.get();
      System.out.println("[updateAsync] executor       : " + result);
    } catch (final InterruptedException | ExecutionException e) {
      throw new IllegalStateException("async update failed", e);
    } finally {
      executor.shutdown();
    }
  }

  // updateOptional short-circuits the first time fn returns Optional.empty().
  private static void optionalShortCircuit(final Team team) {
    final var names = Telescope.of(Team.class).each(Team::users).field(User::name);

    final Optional<Team> allOk = names.updateOptional(team, n -> Optional.of(n.toUpperCase()));
    System.out.println("[updateOptional] all present : " + allOk);

    final Optional<Team> someEmpty = names.updateOptional(team, n ->
      n.equals("bob") ? Optional.<String>empty() : Optional.of(n.toUpperCase())
    );
    System.out.println("[updateOptional] empty on bob: " + someEmpty + " (short-circuited)");
  }

  // updateEither short-circuits on the first Left, otherwise builds the rebuilt source on the
  // Right.
  private static void eitherRightAndLeft(final Team team) {
    final var emails = Telescope.of(Team.class).each(Team::users).field(User::email);

    final Either<ParseError, Team> right = emails.updateEither(team, e -> Either.right(e.toLowerCase()));
    System.out.println("[updateEither] all Right    : " + right);

    final Either<ParseError, Team> left = emails.updateEither(team, e ->
      e.contains("@") ? Either.right(e) : Either.left(new ParseError("email", "missing @: " + e))
    );
    System.out.println("[updateEither] all valid (Right): " + left);

    final Either<ParseError, Team> hasLeft = emails.updateEither(team, e ->
      e.equals("BOB@Y") ? Either.left(new ParseError("email", "blocked: " + e)) : Either.right(e.toLowerCase())
    );
    System.out.println("[updateEither] short-circuit Left: " + hasLeft);
  }

  // updateValidated accumulates EVERY error before returning Invalid; multiple bad paths surface.
  private static void validatedAccumulatesErrors(final Team team) {
    final var names = Telescope.of(Team.class).each(Team::users).field(User::name);

    // Reject any name not matching [a-z]+ — and accumulate errors from every offending element.
    final var bad = new Team(
      "core",
      List.of(new User("", 30, "a@x"), new User("bob", 25, "b@x"), new User("?!", 40, "c@x"))
    );

    final Validated<ParseError, Team> result = names.updateValidated(bad, n ->
      n.matches("[a-z]+") ? Validated.valid(n) : Validated.invalid(new ParseError("name", "bad: '" + n + "'"))
    );

    System.out.println("[updateValidated] valid?       : " + result.isValid());
    if (result instanceof Validated.Invalid<ParseError, Team> invalid) {
      System.out.println("[updateValidated] accumulated  : " + invalid.errors());
    }
  }
}
