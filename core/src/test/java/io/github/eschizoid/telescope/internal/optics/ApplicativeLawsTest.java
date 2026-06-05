package io.github.eschizoid.telescope.internal.optics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.eschizoid.telescope.Either;
import io.github.eschizoid.telescope.Validated;
import io.github.eschizoid.telescope.internal.optics.instances.CompletableFutureK;
import io.github.eschizoid.telescope.internal.optics.instances.EitherK;
import io.github.eschizoid.telescope.internal.optics.instances.OptionalK;
import io.github.eschizoid.telescope.internal.optics.instances.ValidatedK;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Applicative laws — the core algebraic guarantees each effect instance must satisfy. We don't
 * check every law in full generality, just the cases that matter for {@code modifyF}:
 *
 * <ul>
 *   <li>identity: {@code map(fa, x -> x) == fa}
 *   <li>homomorphism: {@code map(pure(a), f) == pure(f(a))}
 *   <li>map2 composition matches each effect's documented semantics (propagate-empty,
 *       short-circuit, accumulate, sequential).
 * </ul>
 */
class ApplicativeLawsTest {

  @Nested
  @DisplayName("OptionalK")
  class OptionalKLaws {

    final Applicative<OptionalK> A = OptionalK.applicative();

    @Test
    @DisplayName("identity: map(fa, x -> x) == fa")
    void identity() {
      final var fa = OptionalK.box(Optional.of(42));
      assertEquals(Optional.of(42), OptionalK.unbox(A.map(fa, x -> x)));
    }

    @Test
    @DisplayName("homomorphism: map(pure(a), f) == pure(f(a))")
    void homomorphism() {
      assertEquals(OptionalK.unbox(A.pure(43)), OptionalK.unbox(A.map(A.pure(42), x -> x + 1)));
    }

    @Test
    @DisplayName("map2: any empty propagates")
    void map2Empty() {
      assertEquals(
        Optional.empty(),
        OptionalK.unbox(A.map2(OptionalK.box(Optional.of(1)), OptionalK.box(Optional.empty()), (a, b) -> a + "" + b))
      );
      assertEquals(
        Optional.empty(),
        OptionalK.unbox(A.map2(OptionalK.box(Optional.empty()), OptionalK.box(Optional.of(2)), (a, b) -> a + "" + b))
      );
    }

    @Test
    @DisplayName("map2: both present combines")
    void map2Both() {
      assertEquals(
        Optional.of("1-2"),
        OptionalK.unbox(A.map2(OptionalK.box(Optional.of(1)), OptionalK.box(Optional.of(2)), (a, b) -> a + "-" + b))
      );
    }
  }

  @Nested
  @DisplayName("EitherK<String>")
  class EitherKLaws {

    final Applicative<EitherK<String>> A = EitherK.forLeft();

    @Test
    @DisplayName("identity")
    void identity() {
      final var fa = EitherK.<String, Integer>box(Either.right(42));
      assertEquals(Either.right(42), EitherK.unbox(A.map(fa, x -> x)));
    }

    @Test
    @DisplayName("homomorphism")
    void homomorphism() {
      assertEquals(EitherK.unbox(A.pure(43)), EitherK.unbox(A.map(A.pure(42), x -> x + 1)));
    }

    @Test
    @DisplayName("map2: both Right combines")
    void map2BothRight() {
      assertEquals(
        Either.right("1-2"),
        EitherK.unbox(A.map2(EitherK.box(Either.right(1)), EitherK.box(Either.right(2)), (a, b) -> a + "-" + b))
      );
    }

    @Test
    @DisplayName("map2: first Left short-circuits")
    void firstLeftWins() {
      assertEquals(
        Either.left("err"),
        EitherK.unbox(
          A.map2(EitherK.<String, Integer>box(Either.left("err")), EitherK.box(Either.right(2)), (a, b) -> "" + a + b)
        )
      );
    }

    @Test
    @DisplayName("map2: second Left propagates when first is Right")
    void secondLeftPropagates() {
      assertEquals(
        Either.left("err2"),
        EitherK.unbox(
          A.map2(EitherK.box(Either.right(1)), EitherK.<String, Integer>box(Either.left("err2")), (a, b) -> "" + a + b)
        )
      );
    }
  }

  @Nested
  @DisplayName("ValidatedK<String>")
  class ValidatedKLaws {

    final Applicative<ValidatedK<String>> A = ValidatedK.forError();

    @Test
    @DisplayName("identity")
    void identity() {
      final var fa = ValidatedK.<String, Integer>box(Validated.valid(42));
      assertEquals(Validated.valid(42), ValidatedK.unbox(A.map(fa, x -> x)));
    }

    @Test
    @DisplayName("homomorphism")
    void homomorphism() {
      assertEquals(ValidatedK.unbox(A.pure(43)), ValidatedK.unbox(A.map(A.pure(42), x -> x + 1)));
    }

    @Test
    @DisplayName("map2: both Valid combines")
    void bothValid() {
      assertEquals(
        Validated.valid("1-2"),
        ValidatedK.unbox(
          A.map2(ValidatedK.box(Validated.valid(1)), ValidatedK.box(Validated.valid(2)), (a, b) -> a + "-" + b)
        )
      );
    }

    @Test
    @DisplayName("map2: both Invalid accumulates errors in order (left then right)")
    void bothInvalidAccumulatesInOrder() {
      final var result = ValidatedK.unbox(
        A.map2(
          ValidatedK.<String, Integer>box(Validated.invalid("err1")),
          ValidatedK.<String, Integer>box(Validated.invalid("err2")),
          (a, b) -> "" + a + b
        )
      );
      assertInstanceOf(Validated.Invalid.class, result);
      final var errs = ((Validated.Invalid<String, ?>) result).errors();
      assertEquals(List.of("err1", "err2"), errs);
    }

    @Test
    @DisplayName("map2: Invalid + Valid stays Invalid")
    void invalidPlusValid() {
      assertEquals(
        Validated.invalid(List.of("err")),
        ValidatedK.unbox(
          A.map2(
            ValidatedK.<String, Integer>box(Validated.invalid("err")),
            ValidatedK.box(Validated.valid(2)),
            (a, b) -> "" + a + b
          )
        )
      );
    }
  }

  @Nested
  @DisplayName("CompletableFutureK")
  class CompletableFutureKLaws {

    final Applicative<CompletableFutureK> A = CompletableFutureK.applicative();

    @Test
    @DisplayName("pure wraps in a completed future")
    void pureCompleted() throws Exception {
      assertEquals(42, CompletableFutureK.unbox(A.pure(42)).get());
    }

    @Test
    @DisplayName("map2: combines two completed futures")
    void map2Combines() throws Exception {
      final var result = CompletableFutureK.unbox(
        A.map2(
          CompletableFutureK.box(CompletableFuture.completedFuture(1)),
          CompletableFutureK.box(CompletableFuture.completedFuture(2)),
          (a, b) -> a + "-" + b
        )
      );
      assertEquals("1-2", result.get());
    }

    @Test
    @DisplayName("map: thenApply semantics on a completed future")
    void mapThenApply() throws Exception {
      assertEquals(
        43,
        (int) CompletableFutureK.unbox(
          A.map(CompletableFutureK.box(CompletableFuture.completedFuture(42)), x -> x + 1)
        ).get()
      );
    }
  }
}
