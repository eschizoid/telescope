package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.effects.Either;
import io.github.eschizoid.telescope.effects.Validated;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests the four effectful-update methods on {@link Telescope}. */
class EffectfulUpdateTest {

  record User(String name, int age, String email) {}

  record Team(String name, List<User> users) {}

  @Nested
  @DisplayName("updateAsync")
  class UpdateAsync {

    @Test
    @DisplayName("lifts each focused value through a CompletableFuture; result completes with rebuilt S")
    void liftsThroughFuture() throws Exception {
      final var emails = Telescope.of(Team.class).each(Team::users).field(User::email);
      final var t = new Team("a", List.of(new User("alice", 30, "Alice@X"), new User("bob", 25, "Bob@Y")));

      final var done = emails.updateAsync(t, email -> CompletableFuture.completedFuture(email.toLowerCase()));
      final var result = done.get();

      assertEquals("alice@x", result.users().get(0).email());
      assertEquals("bob@y", result.users().get(1).email());
    }

    @Test
    @DisplayName("empty focus returns a completed future with the unchanged source")
    void emptyFocus() throws Exception {
      final var emails = Telescope.of(Team.class).each(Team::users).field(User::email);
      final var t = new Team("empty", List.of());

      final var done = emails.updateAsync(t, email -> CompletableFuture.completedFuture(email + "!"));
      assertEquals(t, done.get());
    }

    @Test
    @DisplayName("a failing future propagates")
    void failingFuture() {
      final var name = Telescope.of(User.class).field(User::name);
      final var alice = new User("alice", 30, null);

      final var done = name.updateAsync(alice, n -> CompletableFuture.failedFuture(new RuntimeException("boom")));

      assertTrue(done.isCompletedExceptionally());
    }
  }

  @Nested
  @DisplayName("updateOptional")
  class UpdateOptional {

    @Test
    @DisplayName("returns the rebuilt S when every focused value yields a present Optional")
    void allPresent() {
      final var names = Telescope.of(Team.class).each(Team::users).field(User::name);
      final var t = new Team("a", List.of(new User("alice", 30, null), new User("bob", 25, null)));

      final var result = names.updateOptional(t, n -> Optional.of(n.toUpperCase()));

      assertTrue(result.isPresent());
      assertEquals("ALICE", result.get().users().get(0).name());
      assertEquals("BOB", result.get().users().get(1).name());
    }

    @Test
    @DisplayName("returns empty if any single focused value yields Optional.empty")
    void anyEmptyPropagates() {
      final var names = Telescope.of(Team.class).each(Team::users).field(User::name);
      final var t = new Team("a", List.of(new User("alice", 30, null), new User("bob", 25, null)));

      final var result = names.updateOptional(t, n -> n.equals("bob") ? Optional.empty() : Optional.of(n));
      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("single-field telescope: present in, present out")
    void singleField() {
      final var name = Telescope.of(User.class).field(User::name);
      final var alice = new User("alice", 30, null);

      final var result = name.updateOptional(alice, n -> Optional.of(n.toUpperCase()));
      assertEquals(Optional.of("ALICE"), result.map(User::name));
    }
  }

  @Nested
  @DisplayName("updateEither")
  class UpdateEither {

    record ParseError(String message) {}

    @Test
    @DisplayName("returns Right(S) when every focused value yields Right")
    void allRight() {
      final var emails = Telescope.of(Team.class).each(Team::users).field(User::email);
      final var t = new Team("a", List.of(new User("alice", 30, "Alice@X"), new User("bob", 25, "Bob@Y")));

      final Either<ParseError, Team> result = emails.updateEither(t, e -> Either.right(e.toLowerCase()));

      assertInstanceOf(Either.Right.class, result);
      final var team = ((Either.Right<ParseError, Team>) result).value();
      assertEquals("alice@x", team.users().get(0).email());
      assertEquals("bob@y", team.users().get(1).email());
    }

    @Test
    @DisplayName("short-circuits on the first Left")
    void shortCircuitsOnFirstLeft() {
      final var emails = Telescope.of(Team.class).each(Team::users).field(User::email);
      final var t = new Team(
        "a",
        List.of(new User("alice", 30, "alice@x"), new User("bob", 25, "bad"), new User("carol", 40, "carol@x"))
      );

      final Either<ParseError, Team> result = emails.updateEither(t, e ->
        e.contains("@") ? Either.right(e) : Either.left(new ParseError("bad: " + e))
      );

      assertInstanceOf(Either.Left.class, result);
      assertEquals("bad: bad", ((Either.Left<ParseError, Team>) result).value().message());
    }
  }

  @Nested
  @DisplayName("updateValidated")
  class UpdateValidated {

    record ValidationError(String field, String message) {}

    @Test
    @DisplayName("returns Valid(S) when every focused value validates")
    void allValid() {
      final var names = Telescope.of(Team.class).each(Team::users).field(User::name);
      final var t = new Team("a", List.of(new User("alice", 30, null), new User("bob", 25, null)));

      final Validated<ValidationError, Team> result = names.updateValidated(t, n -> Validated.valid(n.toUpperCase()));

      assertTrue(result.isValid());
    }

    @Test
    @DisplayName("accumulates every validation error across focused elements")
    void accumulatesErrors() {
      final var names = Telescope.of(Team.class).each(Team::users).field(User::name);
      final var t = new Team("a", List.of(new User("", 30, null), new User("bob", 25, null), new User("?", 40, null)));

      final Validated<ValidationError, Team> result = names.updateValidated(t, n ->
        n.matches("[a-z]+") ? Validated.valid(n) : Validated.invalid(new ValidationError("name", "bad: " + n))
      );

      assertFalse(result.isValid());
      assertInstanceOf(Validated.Invalid.class, result);
      final var errors = ((Validated.Invalid<ValidationError, Team>) result).errors();
      assertEquals(2, errors.size());
      assertEquals("bad: ", errors.get(0).message());
      assertEquals("bad: ?", errors.get(1).message());
    }
  }

  @Nested
  @DisplayName("Edge cases — composition with filter / as / whenPresent")
  class EdgeCases {

    sealed interface Event permits Created, Updated {}

    record Created(String id) implements Event {}

    record Updated(String id, String diff) implements Event {}

    record Profile(String id, Optional<String> nickname) {}

    @Test
    @DisplayName("filter + updateValidated: non-matching elements pass through, errors collected from matching only")
    void filterPlusValidated() {
      final var adultNames = Telescope.of(Team.class)
        .each(Team::users)
        .filter(u -> u.age() >= 18)
        .field(User::name);
      final var t = new Team(
        "a",
        List.of(
          new User("", 30, null), // bad, adult -> error
          new User("?", 12, null), // bad, but a kid -> skipped by filter
          new User("bob", 25, null) // valid, adult -> ok
        )
      );

      final var result = adultNames.updateValidated(t, n ->
        n.matches("[a-z]+") ? Validated.valid(n) : Validated.invalid(n)
      );

      assertInstanceOf(Validated.Invalid.class, result);
      final var errs = ((Validated.Invalid<String, ?>) result).errors();
      assertEquals(List.of(""), errs); // only the adult's bad name; the kid was filtered out
    }

    @Test
    @DisplayName("as(SealedCase) + updateAsync on a miss: future completes with the unchanged source immediately")
    void asMissUpdateAsync() throws Exception {
      final var updatedDiff = Telescope.of(Event.class).as(Updated.class).field(Updated::diff);
      final Event miss = new Created("e1");

      final var done = updatedDiff.updateAsync(miss, d -> CompletableFuture.completedFuture("never-called: " + d));

      assertTrue(done.isDone());
      assertEquals(miss, done.get());
    }

    @Test
    @DisplayName("whenPresent + updateOptional on an empty Optional: returns Some(unchanged source)")
    void whenPresentEmptyUpdateOptional() {
      final var nick = Telescope.of(Profile.class).whenPresent(Profile::nickname);
      final var noNick = new Profile("p", Optional.empty());

      final var result = nick.updateOptional(noNick, n -> Optional.of(n + "!"));

      assertTrue(result.isPresent());
      assertEquals(noNick, result.get());
    }

    @Test
    @DisplayName("as + updateEither on a hit: applies and returns Right")
    void asHitUpdateEither() {
      final var updatedDiff = Telescope.of(Event.class).as(Updated.class).field(Updated::diff);
      final Event hit = new Updated("e1", "d");

      final Either<String, Event> result = updatedDiff.updateEither(hit, d -> Either.right(d + "!"));

      assertInstanceOf(Either.Right.class, result);
      assertEquals(new Updated("e1", "d!"), ((Either.Right<String, Event>) result).value());
    }

    @Test
    @DisplayName("each over empty collection + updateEither: Right(unchanged source)")
    void emptyEachUpdateEither() {
      final var names = Telescope.of(Team.class).each(Team::users).field(User::name);
      final var empty = new Team("none", List.of());

      final Either<String, Team> result = names.updateEither(empty, n -> Either.left("never"));
      assertInstanceOf(Either.Right.class, result);
      assertEquals(empty, ((Either.Right<String, Team>) result).value());
    }

    @Test
    @DisplayName("order invariant: validated errors accumulate in left-to-right traversal order")
    void orderInvariant() {
      final var names = Telescope.of(Team.class).each(Team::users).field(User::name);
      // Three bad names in a known order — accumulation must preserve their positions.
      final var t = new Team("a", List.of(new User("X", 30, null), new User("Y", 25, null), new User("Z", 40, null)));

      final var result = names.updateValidated(t, n -> Validated.invalid("bad:" + n));

      assertInstanceOf(Validated.Invalid.class, result);
      assertEquals(List.of("bad:X", "bad:Y", "bad:Z"), ((Validated.Invalid<String, ?>) result).errors());
    }
  }

  @Nested
  @DisplayName("True short-circuit — the per-element function is NOT called past a failure")
  class ShortCircuit {

    @Test
    @DisplayName("updateEither: fn is invoked only on elements up to and including the first Left")
    void eitherStopsCallingFn() {
      final var names = Telescope.of(Team.class).each(Team::users).field(User::name);
      final var t = new Team("a", List.of(new User("a", 30, null), new User("b", 25, null), new User("c", 40, null)));
      final var calls = new AtomicInteger();

      final Either<String, Team> result = names.updateEither(t, n -> {
        calls.incrementAndGet();
        return n.equals("b") ? Either.left("bad:b") : Either.right(n);
      });

      assertInstanceOf(Either.Left.class, result);
      assertEquals(2, calls.get(), "fn should be called for 'a' (Right) and 'b' (Left), then stop");
    }

    @Test
    @DisplayName("updateOptional: fn is invoked only on elements up to and including the first empty")
    void optionalStopsCallingFn() {
      final var names = Telescope.of(Team.class).each(Team::users).field(User::name);
      final var t = new Team("a", List.of(new User("a", 30, null), new User("b", 25, null), new User("c", 40, null)));
      final var calls = new AtomicInteger();

      final Optional<Team> result = names.updateOptional(t, n -> {
        calls.incrementAndGet();
        return n.equals("b") ? Optional.empty() : Optional.of(n);
      });

      assertTrue(result.isEmpty());
      assertEquals(2, calls.get(), "fn should be called for 'a' and 'b', then stop");
    }

    @Test
    @DisplayName("updateValidated: fn is invoked on every element, even past failures (accumulates)")
    void validatedCallsEveryElement() {
      final var names = Telescope.of(Team.class).each(Team::users).field(User::name);
      final var t = new Team("a", List.of(new User("a", 30, null), new User("b", 25, null), new User("c", 40, null)));
      final var calls = new AtomicInteger();

      final Validated<String, Team> result = names.updateValidated(t, n -> {
        calls.incrementAndGet();
        return n.equals("a") ? Validated.valid(n) : Validated.invalid("bad:" + n);
      });

      assertInstanceOf(Validated.Invalid.class, result);
      assertEquals(3, calls.get(), "fn should be called on every element to accumulate errors");
      assertEquals(List.of("bad:b", "bad:c"), ((Validated.Invalid<String, ?>) result).errors());
    }
  }
}
