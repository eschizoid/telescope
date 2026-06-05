package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the Phase A ergonomic helpers on {@link Either} and {@link Validated}: {@code mapLeft},
 * {@code swap}, {@code toValidated} on Either; {@code mapErrors}, {@code andThen}, {@code toEither}
 * on Validated.
 */
class EitherValidatedHelpersTest {

  @Nested
  @DisplayName("Either.mapLeft")
  class EitherMapLeft {

    @Test
    @DisplayName("Left value is transformed; type changes")
    void leftTransformed() {
      final Either<Integer, String> in = Either.left(42);
      final Either<String, String> out = in.mapLeft(n -> "err-" + n);
      assertEquals(Either.left("err-42"), out);
    }

    @Test
    @DisplayName("Right passes through untouched")
    void rightUntouched() {
      final Either<Integer, String> in = Either.right("ok");
      final Either<String, String> out = in.mapLeft(n -> "err-" + n);
      assertEquals(Either.right("ok"), out);
    }
  }

  @Nested
  @DisplayName("Either.swap")
  class EitherSwap {

    @Test
    @DisplayName("Right becomes Left")
    void rightToLeft() {
      assertEquals(Either.left("x"), Either.<Integer, String>right("x").swap());
    }

    @Test
    @DisplayName("Left becomes Right")
    void leftToRight() {
      assertEquals(Either.right(7), Either.<Integer, String>left(7).swap());
    }

    @Test
    @DisplayName("double-swap is identity")
    void doubleSwap() {
      final Either<Integer, String> original = Either.right("ok");
      assertEquals(original, original.swap().swap());
    }
  }

  @Nested
  @DisplayName("Either.toValidated")
  class EitherToValidated {

    @Test
    @DisplayName("Right becomes Valid")
    void rightToValid() {
      assertEquals(Validated.valid("ok"), Either.<String, String>right("ok").toValidated());
    }

    @Test
    @DisplayName("Left becomes Invalid with a single-element error list")
    void leftToInvalidSingleton() {
      final Validated<String, String> v = Either.<String, String>left("boom").toValidated();
      assertInstanceOf(Validated.Invalid.class, v);
      assertEquals(List.of("boom"), ((Validated.Invalid<String, String>) v).errors());
    }
  }

  @Nested
  @DisplayName("Validated.mapErrors")
  class ValidatedMapErrors {

    @Test
    @DisplayName("every error is transformed; type changes")
    void everyErrorTransformed() {
      final Validated<Integer, String> in = Validated.invalid(List.of(1, 2, 3));
      final Validated<String, String> out = in.mapErrors(n -> "err-" + n);
      assertInstanceOf(Validated.Invalid.class, out);
      assertEquals(List.of("err-1", "err-2", "err-3"), ((Validated.Invalid<String, String>) out).errors());
    }

    @Test
    @DisplayName("Valid passes through untouched")
    void validUntouched() {
      final Validated<Integer, String> in = Validated.valid("ok");
      final Validated<String, String> out = in.mapErrors(n -> "err-" + n);
      assertEquals(Validated.valid("ok"), out);
    }
  }

  @Nested
  @DisplayName("Validated.andThen")
  class ValidatedAndThen {

    @Test
    @DisplayName("Valid feeds into next step; result is the next step's output")
    void validChains() {
      final Validated<String, Integer> step1 = Validated.valid(10);
      final Validated<String, String> step2 = step1.andThen(n -> Validated.valid("v=" + n));
      assertEquals(Validated.valid("v=10"), step2);
    }

    @Test
    @DisplayName("Invalid short-circuits — next step is not called")
    void invalidShortCircuits() {
      final boolean[] nextCalled = { false };
      final Validated<String, Integer> step1 = Validated.invalid("nope");
      final Validated<String, String> step2 = step1.andThen(n -> {
        nextCalled[0] = true;
        return Validated.valid("v=" + n);
      });
      assertInstanceOf(Validated.Invalid.class, step2);
      assertEquals(List.of("nope"), ((Validated.Invalid<String, String>) step2).errors());
      assertFalse(nextCalled[0]);
    }

    @Test
    @DisplayName("Invalid errors from step1 are preserved through chaining")
    void errorsPreserved() {
      final Validated<String, Integer> step1 = Validated.invalid(List.of("e1", "e2"));
      final Validated<String, String> step2 = step1.andThen(n -> Validated.valid("v=" + n));
      assertInstanceOf(Validated.Invalid.class, step2);
      assertEquals(List.of("e1", "e2"), ((Validated.Invalid<String, String>) step2).errors());
    }
  }

  @Nested
  @DisplayName("Validated.toEither")
  class ValidatedToEither {

    @Test
    @DisplayName("Valid becomes Right")
    void validToRight() {
      assertEquals(Either.right("ok"), Validated.<String, String>valid("ok").toEither());
    }

    @Test
    @DisplayName("Invalid becomes Left carrying the full error list")
    void invalidToLeft() {
      final var v = Validated.<String, String>invalid(List.of("e1", "e2"));
      final var e = v.toEither();
      assertInstanceOf(Either.Left.class, e);
      assertEquals(List.of("e1", "e2"), ((Either.Left<List<String>, String>) e).value());
    }
  }

  @Nested
  @DisplayName("Either.flatMapAsync")
  class EitherFlatMapAsync {

    @Test
    @DisplayName("Right invokes the async function and returns Right of the result")
    void rightApplies() throws Exception {
      final Either<String, Integer> in = Either.right(10);
      final var done = in.flatMapAsync(n -> CompletableFuture.completedFuture(n * 2));
      assertEquals(Either.right(20), done.get());
    }

    @Test
    @DisplayName("Left short-circuits — async function is never called, future completes immediately")
    void leftShortCircuits() throws Exception {
      final boolean[] called = { false };
      final Either<String, Integer> in = Either.left("nope");
      final var done = in.flatMapAsync(n -> {
        called[0] = true;
        return CompletableFuture.completedFuture(n * 2);
      });
      assertTrue(done.isDone());
      assertEquals(Either.left("nope"), done.get());
      assertFalse(called[0]);
    }

    @Test
    @DisplayName("failing future propagates through the result future")
    void failingFuturePropagates() {
      final Either<String, Integer> in = Either.right(10);
      final var done = in.flatMapAsync(n -> CompletableFuture.<Integer>failedFuture(new RuntimeException("boom")));
      assertTrue(done.isCompletedExceptionally());
    }
  }

  @Nested
  @DisplayName("Validated.flatMapAsync")
  class ValidatedFlatMapAsync {

    @Test
    @DisplayName("Valid invokes the async function and returns Valid of the result")
    void validApplies() throws Exception {
      final Validated<String, Integer> in = Validated.valid(10);
      final var done = in.flatMapAsync(n -> CompletableFuture.completedFuture(n * 2));
      assertEquals(Validated.valid(20), done.get());
    }

    @Test
    @DisplayName("Invalid short-circuits — async function is never called, errors preserved")
    void invalidShortCircuits() throws Exception {
      final boolean[] called = { false };
      final Validated<String, Integer> in = Validated.invalid(List.of("e1", "e2"));
      final var done = in.flatMapAsync(n -> {
        called[0] = true;
        return CompletableFuture.completedFuture(n * 2);
      });
      assertTrue(done.isDone());
      final var got = done.get();
      assertInstanceOf(Validated.Invalid.class, got);
      assertEquals(List.of("e1", "e2"), ((Validated.Invalid<String, Integer>) got).errors());
      assertFalse(called[0]);
    }
  }

  @Nested
  @DisplayName("Either.getOrElse / getOrElseGet / toOptional")
  class EitherDefaults {

    @Test
    @DisplayName("getOrElse returns the Right value, ignores the default")
    void getOrElseOnRight() {
      assertEquals(42, (int) Either.<String, Integer>right(42).getOrElse(0));
    }

    @Test
    @DisplayName("getOrElse returns the default on Left")
    void getOrElseOnLeft() {
      assertEquals(0, (int) Either.<String, Integer>left("err").getOrElse(0));
    }

    @Test
    @DisplayName("getOrElseGet doesn't call the supplier on Right")
    void getOrElseGetLazyOnRight() {
      final boolean[] called = { false };
      final int v = Either.<String, Integer>right(42).getOrElseGet(() -> {
        called[0] = true;
        return 0;
      });
      assertEquals(42, v);
      assertFalse(called[0]);
    }

    @Test
    @DisplayName("getOrElseGet calls the supplier on Left")
    void getOrElseGetEvalOnLeft() {
      assertEquals(99, (int) Either.<String, Integer>left("err").getOrElseGet(() -> 99));
    }

    @Test
    @DisplayName("toOptional: Right becomes Optional.of, Left becomes empty")
    void toOptional() {
      assertEquals(Optional.of(42), Either.<String, Integer>right(42).toOptional());
      assertEquals(Optional.empty(), Either.<String, Integer>left("err").toOptional());
    }
  }

  @Nested
  @DisplayName("Validated.getOrElse / getOrElseGet / toOptional")
  class ValidatedDefaults {

    @Test
    @DisplayName("getOrElse returns the Valid value, ignores the default")
    void getOrElseOnValid() {
      assertEquals(42, (int) Validated.<String, Integer>valid(42).getOrElse(0));
    }

    @Test
    @DisplayName("getOrElse returns the default on Invalid")
    void getOrElseOnInvalid() {
      assertEquals(0, (int) Validated.<String, Integer>invalid("nope").getOrElse(0));
    }

    @Test
    @DisplayName("getOrElseGet doesn't call the supplier on Valid")
    void getOrElseGetLazyOnValid() {
      final boolean[] called = { false };
      final int v = Validated.<String, Integer>valid(42).getOrElseGet(() -> {
        called[0] = true;
        return 0;
      });
      assertEquals(42, v);
      assertFalse(called[0]);
    }

    @Test
    @DisplayName("getOrElseGet calls the supplier on Invalid")
    void getOrElseGetEvalOnInvalid() {
      assertEquals(99, (int) Validated.<String, Integer>invalid("nope").getOrElseGet(() -> 99));
    }

    @Test
    @DisplayName("toOptional: Valid becomes Optional.of, Invalid becomes empty")
    void toOptional() {
      assertEquals(Optional.of(42), Validated.<String, Integer>valid(42).toOptional());
      assertEquals(Optional.empty(), Validated.<String, Integer>invalid("err").toOptional());
    }
  }

  @Nested
  @DisplayName("Validated.combineAll")
  class ValidatedCombineAll {

    @Test
    @DisplayName("all Valid → Valid of the list in order")
    void allValid() {
      final var inputs = List.of(
        Validated.<String, Integer>valid(1),
        Validated.<String, Integer>valid(2),
        Validated.<String, Integer>valid(3)
      );
      assertEquals(Validated.valid(List.of(1, 2, 3)), Validated.combineAll(inputs));
    }

    @Test
    @DisplayName("any Invalid → Invalid accumulating every error in order")
    void anyInvalid() {
      final var inputs = List.of(
        Validated.<String, Integer>invalid("e1"),
        Validated.<String, Integer>valid(2),
        Validated.<String, Integer>invalid(List.of("e2", "e3"))
      );
      final var result = Validated.combineAll(inputs);
      assertInstanceOf(Validated.Invalid.class, result);
      assertEquals(List.of("e1", "e2", "e3"), ((Validated.Invalid<String, List<Integer>>) result).errors());
    }

    @Test
    @DisplayName("empty input → Valid of an empty list")
    void emptyInput() {
      assertEquals(Validated.valid(List.<Integer>of()), Validated.combineAll(List.<Validated<String, Integer>>of()));
    }
  }

  @Nested
  @DisplayName("Round-trip via bridges")
  class RoundTrips {

    @Test
    @DisplayName("Either.right -> toValidated -> toEither preserves the value")
    void rightRoundTrip() {
      final Either<String, Integer> start = Either.right(42);
      final Either<List<String>, Integer> after = start.toValidated().toEither();
      assertTrue(after.isRight());
      assertEquals(42, ((Either.Right<List<String>, Integer>) after).value());
    }

    @Test
    @DisplayName("Validated.invalid -> toEither -> Either.left list is preserved")
    void invalidRoundTrip() {
      final Validated<String, Integer> start = Validated.invalid(List.of("e1", "e2"));
      final Either<List<String>, Integer> e = start.toEither();
      assertEquals(List.of("e1", "e2"), ((Either.Left<List<String>, Integer>) e).value());
    }
  }
}
