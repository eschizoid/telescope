package io.github.eschizoid.telescope.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.runtime.instances.EitherK;
import io.github.eschizoid.telescope.runtime.instances.ValidatedK;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Exercises both cases of every sealed-dispatch method on {@link Either} and {@link Validated} (and
 * the applicative witnesses {@link EitherK} / {@link ValidatedK}). Pins the post-Java-17 instanceof
 * chain rewrites so a regression in any single branch (e.g. swapping Left/Right ordering,
 * accidentally dropping a branch, mishandling the both-Invalid accumulation path) surfaces here
 * rather than as a behavioural drift in a downstream caller.
 *
 * <p>Each method gets one Left/Invalid test and one Right/Valid test; combinators that fan out on
 * the cross-product ({@code Validated.combine}, {@code map2} on both witnesses) get explicit tests
 * for every cell of the 2×2 (or 2×2×2) table.
 */
class SealedEffectsBranchTest {

  @Nested
  @DisplayName("Either — both cases of every default method")
  class EitherBothCases {

    @Test
    @DisplayName("fold on Right calls onRight; fold on Left calls onLeft")
    void foldBothCases() {
      assertEquals("R:1", Either.<String, Integer>right(1).fold(l -> "L:" + l, r -> "R:" + r));
      assertEquals("L:bad", Either.<String, Integer>left("bad").fold(l -> "L:" + l, r -> "R:" + r));
    }

    @Test
    @DisplayName("map transforms Right; leaves Left untouched")
    void mapBothCases() {
      assertEquals(Either.right(3), Either.<String, Integer>right(1).map(n -> n + 2));
      assertEquals(Either.left("bad"), Either.<String, Integer>left("bad").map(n -> n + 2));
    }

    @Test
    @DisplayName("flatMap chains on Right; short-circuits on Left")
    void flatMapBothCases() {
      assertEquals(
        Either.<String, Integer>right(10),
        Either.<String, Integer>right(5).flatMap(n -> Either.right(n + 5))
      );
      assertEquals(Either.left("bad"), Either.<String, Integer>left("bad").flatMap(n -> Either.right(n + 5)));
    }

    @Test
    @DisplayName("mapLeft transforms Left; leaves Right untouched")
    void mapLeftBothCases() {
      assertEquals(Either.left("E:bad"), Either.<String, Integer>left("bad").mapLeft(e -> "E:" + e));
      assertEquals(Either.right(7), Either.<String, Integer>right(7).mapLeft(e -> "E:" + e));
    }

    @Test
    @DisplayName("swap turns Right→Left and Left→Right")
    void swapBothCases() {
      assertEquals(Either.left(7), Either.<String, Integer>right(7).swap());
      assertEquals(Either.right("bad"), Either.<String, Integer>left("bad").swap());
    }

    @Test
    @DisplayName("toValidated maps Right→Valid and Left→single-error Invalid")
    void toValidatedBothCases() {
      assertEquals(Validated.valid(7), Either.<String, Integer>right(7).toValidated());
      assertEquals(Validated.invalid("bad"), Either.<String, Integer>left("bad").toValidated());
    }

    @Test
    @DisplayName("getOrElse returns Right value or the default on Left")
    void getOrElseBothCases() {
      assertEquals(7, Either.<String, Integer>right(7).getOrElse(0));
      assertEquals(0, Either.<String, Integer>left("bad").getOrElse(0));
    }

    @Test
    @DisplayName("getOrElseGet returns Right value or runs the supplier on Left")
    void getOrElseGetBothCases() {
      assertEquals(7, Either.<String, Integer>right(7).getOrElseGet(() -> 99));
      assertEquals(99, Either.<String, Integer>left("bad").getOrElseGet(() -> 99));
    }

    @Test
    @DisplayName("toOptional wraps Right as present, Left as empty")
    void toOptionalBothCases() {
      assertEquals(Optional.of(7), Either.<String, Integer>right(7).toOptional());
      assertEquals(Optional.empty(), Either.<String, Integer>left("bad").toOptional());
    }

    @Test
    @DisplayName("flatMapAsync runs the function on Right; passes the error through on Left")
    void flatMapAsyncBothCases() throws Exception {
      assertEquals(
        Either.right(10),
        Either.<String, Integer>right(5)
          .flatMapAsync(n -> CompletableFuture.completedFuture(n + 5))
          .get()
      );
      assertEquals(
        Either.left("bad"),
        Either.<String, Integer>left("bad")
          .flatMapAsync(n -> CompletableFuture.completedFuture(n + 5))
          .get()
      );
    }
  }

  @Nested
  @DisplayName("Validated — both cases of every default method")
  class ValidatedBothCases {

    @Test
    @DisplayName("fold on Valid calls onValid; fold on Invalid calls onInvalid")
    void foldBothCases() {
      assertEquals("V:1", Validated.<String, Integer>valid(1).fold(errs -> "I:" + errs.size(), v -> "V:" + v));
      assertEquals(
        "I:2",
        Validated.<String, Integer>invalid(List.of("a", "b")).fold(errs -> "I:" + errs.size(), v -> "V:" + v)
      );
    }

    @Test
    @DisplayName("map transforms Valid; Invalid errors unchanged")
    void mapBothCases() {
      assertEquals(Validated.valid(3), Validated.<String, Integer>valid(1).map(n -> n + 2));
      assertEquals(Validated.invalid(List.of("a")), Validated.<String, Integer>invalid("a").map(n -> n + 2));
    }

    @Test
    @DisplayName("mapErrors transforms every error; Valid value unchanged")
    void mapErrorsBothCases() {
      assertEquals(
        Validated.invalid(List.of("E:a", "E:b")),
        Validated.<String, Integer>invalid(List.of("a", "b")).mapErrors(e -> "E:" + e)
      );
      assertEquals(Validated.valid(7), Validated.<String, Integer>valid(7).mapErrors(e -> "E:" + e));
    }

    @Test
    @DisplayName("andThen chains on Valid; Invalid short-circuits with original errors")
    void andThenBothCases() {
      assertEquals(
        Validated.<String, Integer>valid(15),
        Validated.<String, Integer>valid(5).andThen(n -> Validated.valid(n + 10))
      );
      assertEquals(
        Validated.invalid(List.of("orig")),
        Validated.<String, Integer>invalid("orig").andThen(n -> Validated.valid(n + 10))
      );
    }

    @Test
    @DisplayName("toEither maps Valid→Right, Invalid→Left(errors list)")
    void toEitherBothCases() {
      assertEquals(Either.right(7), Validated.<String, Integer>valid(7).toEither());
      assertEquals(Either.left(List.of("a", "b")), Validated.<String, Integer>invalid(List.of("a", "b")).toEither());
    }

    @Test
    @DisplayName("getOrElse returns Valid value or the default on Invalid")
    void getOrElseBothCases() {
      assertEquals(7, Validated.<String, Integer>valid(7).getOrElse(0));
      assertEquals(0, Validated.<String, Integer>invalid("bad").getOrElse(0));
    }

    @Test
    @DisplayName("getOrElseGet returns Valid value or runs the supplier on Invalid")
    void getOrElseGetBothCases() {
      assertEquals(7, Validated.<String, Integer>valid(7).getOrElseGet(() -> 99));
      assertEquals(99, Validated.<String, Integer>invalid("bad").getOrElseGet(() -> 99));
    }

    @Test
    @DisplayName("toOptional wraps Valid as present, Invalid as empty")
    void toOptionalBothCases() {
      assertEquals(Optional.of(7), Validated.<String, Integer>valid(7).toOptional());
      assertEquals(Optional.empty(), Validated.<String, Integer>invalid("bad").toOptional());
    }

    @Test
    @DisplayName("flatMapAsync runs the function on Valid; carries errors through on Invalid")
    void flatMapAsyncBothCases() throws Exception {
      assertEquals(
        Validated.valid(10),
        Validated.<String, Integer>valid(5)
          .flatMapAsync(n -> CompletableFuture.completedFuture(n + 5))
          .get()
      );
      assertEquals(
        Validated.invalid(List.of("a", "b")),
        Validated.<String, Integer>invalid(List.of("a", "b"))
          .flatMapAsync(n -> CompletableFuture.completedFuture(n + 5))
          .get()
      );
    }
  }

  @Nested
  @DisplayName("Validated.combine — every cell of the 2×2 cross-product")
  class ValidatedCombineCells {

    @Test
    @DisplayName("Valid + Valid → Valid(fn(a, b))")
    void validValid() {
      final Validated<String, Integer> a = Validated.valid(3);
      final Validated<String, String> b = Validated.valid("x");
      assertEquals(Validated.valid("3x"), Validated.combine(a, b, (i, s) -> i + s));
    }

    @Test
    @DisplayName("Invalid + Valid → Invalid(left's errors)")
    void invalidValid() {
      final Validated<String, Integer> a = Validated.invalid(List.of("e1"));
      final Validated<String, String> b = Validated.valid("x");
      assertEquals(Validated.invalid(List.of("e1")), Validated.combine(a, b, (i, s) -> i + s));
    }

    @Test
    @DisplayName("Valid + Invalid → Invalid(right's errors)")
    void validInvalid() {
      final Validated<String, Integer> a = Validated.valid(3);
      final Validated<String, String> b = Validated.invalid(List.of("e2"));
      assertEquals(Validated.invalid(List.of("e2")), Validated.combine(a, b, (i, s) -> i + s));
    }

    @Test
    @DisplayName("Invalid + Invalid → Invalid(concat(left, right)) — accumulating semantics")
    void invalidInvalid() {
      final Validated<String, Integer> a = Validated.invalid(List.of("e1", "e2"));
      final Validated<String, String> b = Validated.invalid(List.of("e3"));
      assertEquals(Validated.invalid(List.of("e1", "e2", "e3")), Validated.combine(a, b, (i, s) -> i + s));
    }
  }

  @Nested
  @DisplayName("Validated.combineAll — Valid roll-up vs accumulated Invalid")
  class ValidatedCombineAllShapes {

    @Test
    @DisplayName("every input Valid → Valid(list of values, input order preserved)")
    void allValid() {
      final var inputs = List.of(
        Validated.<String, Integer>valid(1),
        Validated.<String, Integer>valid(2),
        Validated.<String, Integer>valid(3)
      );
      assertEquals(Validated.valid(List.of(1, 2, 3)), Validated.combineAll(inputs));
    }

    @Test
    @DisplayName("any input Invalid → Invalid(every error concatenated across all inputs)")
    void mixedInvalidWins() {
      final var inputs = List.of(
        Validated.<String, Integer>valid(1),
        Validated.<String, Integer>invalid(List.of("e1")),
        Validated.<String, Integer>valid(2),
        Validated.<String, Integer>invalid(List.of("e2", "e3"))
      );
      assertEquals(Validated.invalid(List.of("e1", "e2", "e3")), Validated.combineAll(inputs));
    }

    @Test
    @DisplayName("empty input → Valid(empty list)")
    void emptyInputs() {
      assertEquals(Validated.valid(List.of()), Validated.<String, Integer>combineAll(List.of()));
    }

    @Test
    @DisplayName("a null element fails loudly with a message naming the API, never a silent drop")
    void nullElementFailsLoudly() {
      final var inputs = Arrays.asList(Validated.<String, Integer>valid(1), null);
      final var thrown = assertThrows(NullPointerException.class, () -> Validated.combineAll(inputs));
      assertEquals("combineAll: null Validated element in inputs", thrown.getMessage());
    }
  }

  @Nested
  @DisplayName("EitherK.map2 — every cell of the 2×2 cross-product")
  class EitherKMap2 {

    @Test
    @DisplayName("Right + Right → Right(fn(a, b)) — the only success path")
    void rightRight() {
      final var app = EitherK.<String>forLeft();
      final var fa = EitherK.box(Either.<String, Integer>right(3));
      final var fb = EitherK.box(Either.<String, Integer>right(4));
      assertEquals(Either.right(7), EitherK.unbox(app.map2(fa, fb, Integer::sum)));
    }

    @Test
    @DisplayName("Left + Right → Left(la) — fa short-circuits before fb is inspected")
    void leftRight() {
      final var app = EitherK.<String>forLeft();
      final var fa = EitherK.box(Either.<String, Integer>left("bad-a"));
      final var fb = EitherK.box(Either.<String, Integer>right(4));
      assertEquals(Either.left("bad-a"), EitherK.unbox(app.map2(fa, fb, Integer::sum)));
    }

    @Test
    @DisplayName("Right + Left → Left(lb) — fa Right, fb Left wins")
    void rightLeft() {
      final var app = EitherK.<String>forLeft();
      final var fa = EitherK.box(Either.<String, Integer>right(3));
      final var fb = EitherK.box(Either.<String, Integer>left("bad-b"));
      assertEquals(Either.left("bad-b"), EitherK.unbox(app.map2(fa, fb, Integer::sum)));
    }

    @Test
    @DisplayName("isFailed: true for Left, false for Right")
    void isFailedBothCases() {
      final var app = EitherK.<String>forLeft();
      assertTrue(app.isFailed(EitherK.box(Either.<String, Integer>left("bad"))));
      assertFalse(app.isFailed(EitherK.box(Either.right(1))));
    }

    @Test
    @DisplayName("pure wraps a value in Right; map runs the function on Right")
    void pureAndMap() {
      final var app = EitherK.<String>forLeft();
      final var pured = app.pure(5);
      assertInstanceOf(Either.Right.class, EitherK.unbox(pured));
      assertEquals(Either.right(10), EitherK.unbox(app.map(pured, n -> n * 2)));
    }
  }

  @Nested
  @DisplayName("ValidatedK.map2 — every cell of the 2×2 cross-product")
  class ValidatedKMap2 {

    @Test
    @DisplayName("Valid + Valid → Valid(fn(a, b)) — the only success path")
    void validValid() {
      final var app = ValidatedK.<String>forError();
      final var fa = ValidatedK.box(Validated.<String, Integer>valid(3));
      final var fb = ValidatedK.box(Validated.<String, Integer>valid(4));
      assertEquals(Validated.valid(7), ValidatedK.unbox(app.map2(fa, fb, Integer::sum)));
    }

    @Test
    @DisplayName("Invalid + Valid → Invalid(fa.errors) — fa carries through")
    void invalidValid() {
      final var app = ValidatedK.<String>forError();
      final var fa = ValidatedK.box(Validated.<String, Integer>invalid(List.of("e1")));
      final var fb = ValidatedK.box(Validated.<String, Integer>valid(4));
      assertEquals(Validated.invalid(List.of("e1")), ValidatedK.unbox(app.map2(fa, fb, Integer::sum)));
    }

    @Test
    @DisplayName("Valid + Invalid → Invalid(fb.errors) — fb carries through")
    void validInvalid() {
      final var app = ValidatedK.<String>forError();
      final var fa = ValidatedK.box(Validated.<String, Integer>valid(3));
      final var fb = ValidatedK.box(Validated.<String, Integer>invalid(List.of("e2")));
      assertEquals(Validated.invalid(List.of("e2")), ValidatedK.unbox(app.map2(fa, fb, Integer::sum)));
    }

    @Test
    @DisplayName("Invalid + Invalid → Invalid(concat) — accumulation, the whole point of Validated")
    void invalidInvalid() {
      final var app = ValidatedK.<String>forError();
      final var fa = ValidatedK.box(Validated.<String, Integer>invalid(List.of("e1", "e2")));
      final var fb = ValidatedK.box(Validated.<String, Integer>invalid(List.of("e3")));
      assertEquals(Validated.invalid(List.of("e1", "e2", "e3")), ValidatedK.unbox(app.map2(fa, fb, Integer::sum)));
    }

    @Test
    @DisplayName("pure wraps a value in Valid; map runs the function on Valid")
    void pureAndMap() {
      final var app = ValidatedK.<String>forError();
      final var pured = app.pure(5);
      assertInstanceOf(Validated.Valid.class, ValidatedK.unbox(pured));
      assertEquals(Validated.valid(10), ValidatedK.unbox(app.map(pured, n -> n * 2)));
    }
  }
}
