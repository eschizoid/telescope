package io.github.eschizoid.telescope.effects;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Sum type for "either a left value or a right value." By convention the {@code Right} carries the
 * successful result and the {@code Left} carries the failure case, mirroring Haskell / Scala /
 * Arrow usage. Shipped in-house so the library has no external dependency for its effectful-update
 * API ({@link Telescope#updateEither}).
 *
 * <p><b>Short-circuit semantics.</b> {@code Either} stops at the first failure: {@link #flatMap},
 * {@link #flatMapAsync}, and {@link Telescope#updateEither} all return the first {@code Left} they
 * encounter and skip the rest. Contrast with {@link Validated}, which accumulates every error
 * instead of stopping at one.
 *
 * <p>Sealed: pattern-match on the two cases directly.
 *
 * <pre>{@code
 * final Either<ParseError, Company> result = emails.updateEither(company, EmailParser::parse);
 * return switch (result) {
 *   case Either.Right<?, Company>(var c) -> save(c);
 *   case Either.Left<ParseError, ?>(var err) -> respondError(err);
 * };
 * }</pre>
 */
public sealed interface Either<L, R> {
  /**
   * The failure / left side. Pattern-match it to read the carried error, e.g. {@code case
   * Either.Left<ParseError, ?>(var err) -> report(err)}.
   */
  record Left<L, R>(L value) implements Either<L, R> {}

  /**
   * The success / right side. Pattern-match it to read the carried result, e.g. {@code case
   * Either.Right<?, Company>(var c) -> save(c)}.
   */
  record Right<L, R>(R value) implements Either<L, R> {}

  /**
   * Construct a {@code Left}.
   *
   * <pre>{@code
   * final Either<String, Integer> bad = Either.left("not a number");
   * }</pre>
   */
  static <L, R> Either<L, R> left(final L value) {
    return new Left<>(value);
  }

  /**
   * Construct a {@code Right}.
   *
   * <pre>{@code
   * final Either<String, Integer> ok = Either.right(42);
   * }</pre>
   */
  static <L, R> Either<L, R> right(final R value) {
    return new Right<>(value);
  }

  /**
   * {@code true} for {@code Right}, {@code false} for {@code Left}. Sibling of {@link #isLeft()}.
   *
   * <pre>{@code
   * Either.right(42).isRight();  // true
   * Either.left("e").isRight();  // false
   * }</pre>
   */
  default boolean isRight() {
    return this instanceof Right<L, R>;
  }

  /**
   * {@code true} for {@code Left}. The complement of {@link #isRight()}; see that method for an
   * example.
   */
  default boolean isLeft() {
    return this instanceof Left<L, R>;
  }

  /**
   * Fold both cases into a single value. Equivalent to {@code switch (this) { case Right(var r) ->
   * onRight.apply(r); case Left(var l) -> onLeft.apply(l); }}.
   *
   * <pre>{@code
   * final String msg = result.fold(
   *     err -> "failed: " + err,
   *     company -> "saved " + company.name());
   * }</pre>
   */
  default <T> T fold(final Function<? super L, ? extends T> onLeft, final Function<? super R, ? extends T> onRight) {
    return switch (this) {
      case Left<L, R> l -> onLeft.apply(l.value());
      case Right<L, R> r -> onRight.apply(r.value());
    };
  }

  /**
   * Map the right side. Leaves a {@code Left} unchanged. Pure transform — to chain a step that can
   * itself fail, use {@link #flatMap}.
   *
   * <pre>{@code
   * Either.right(42).map(n -> n + 1);   // Right(43)
   * Either.<String, Integer>left("e").map(n -> n + 1);  // Left("e")
   * }</pre>
   */
  default <T> Either<L, T> map(final Function<? super R, ? extends T> f) {
    return switch (this) {
      case Left<L, R> l -> new Left<>(l.value());
      case Right<L, R> r -> new Right<>(f.apply(r.value()));
    };
  }

  /**
   * Flat-map the right side: chain a step that returns its own {@code Either}. Short-circuits on
   * {@code Left} — if {@code this} is a {@code Left}, {@code f} is never called and the original
   * error is returned. This is the building block for the first-failure-wins behavior.
   *
   * <pre>{@code
   * Either<String, Integer> parsed = Either.right("42").flatMap(s -> parseInt(s));  // parseInt returns Either
   * }</pre>
   */
  default <T> Either<L, T> flatMap(final Function<? super R, ? extends Either<L, T>> f) {
    return switch (this) {
      case Left<L, R> l -> new Left<>(l.value());
      case Right<L, R> r -> f.apply(r.value());
    };
  }

  /**
   * Map the left side. Leaves a {@code Right} unchanged. Useful for translating typed errors to a
   * different error type at a boundary (e.g., mapping a {@code ParseError} to a {@code String}
   * before reporting).
   *
   * <pre>{@code
   * final Either<String, Company> reportable = result.mapLeft(ParseError::message);
   * }</pre>
   */
  default <T> Either<T, R> mapLeft(final Function<? super L, ? extends T> f) {
    return switch (this) {
      case Left<L, R> l -> new Left<>(f.apply(l.value()));
      case Right<L, R> r -> new Right<>(r.value());
    };
  }

  /**
   * Swap left and right. {@code Right(x)} becomes {@code Left(x)} and vice versa.
   *
   * <pre>{@code
   * Either.right(1).swap();  // Left(1)
   * Either.left("e").swap(); // Right("e")
   * }</pre>
   */
  default Either<R, L> swap() {
    return switch (this) {
      case Left<L, R> l -> new Right<>(l.value());
      case Right<L, R> r -> new Left<>(r.value());
    };
  }

  /**
   * Bridge to {@link Validated}: {@code Left(e)} becomes a single-element {@code Invalid([e])};
   * {@code Right(v)} becomes {@code Valid(v)}. Use when a short-circuiting result needs to feed
   * into accumulating code.
   *
   * <pre>{@code
   * final Validated<ParseError, Company> v = result.toValidated();
   * }</pre>
   */
  default Validated<L, R> toValidated() {
    return switch (this) {
      case Left<L, R> l -> Validated.invalid(l.value());
      case Right<L, R> r -> Validated.valid(r.value());
    };
  }

  /**
   * Return the {@code Right} value, or {@code defaultValue} if this is a {@code Left}.
   *
   * <pre>{@code
   * Either.right(42).getOrElse(0);   // 42
   * Either.<String, Integer>left("e").getOrElse(0);  // 0
   * }</pre>
   */
  default R getOrElse(final R defaultValue) {
    return switch (this) {
      case Left<L, R> ignored -> defaultValue;
      case Right<L, R> r -> r.value();
    };
  }

  /**
   * Return the {@code Right} value, or compute one via {@code supplier} if this is a {@code Left}.
   * Use over {@link #getOrElse(Object)} when the default is expensive to construct.
   *
   * <pre>{@code
   * final Config c = result.getOrElseGet(Config::loadFallback);  // loadFallback runs only on Left
   * }</pre>
   */
  default R getOrElseGet(final Supplier<? extends R> supplier) {
    return switch (this) {
      case Left<L, R> ignored -> supplier.get();
      case Right<L, R> r -> r.value();
    };
  }

  /**
   * Drop the error and bridge to {@link Optional}: {@code Right(v)} becomes {@code Optional.of(v)};
   * {@code Left(_)} becomes {@code Optional.empty()}. Use when downstream code only cares about the
   * success path. A {@code Right(null)} maps to {@code Optional.empty()}.
   *
   * <pre>{@code
   * result.toOptional().ifPresent(this::save);
   * }</pre>
   */
  default Optional<R> toOptional() {
    return switch (this) {
      case Left<L, R> ignored -> Optional.empty();
      case Right<L, R> r -> Optional.ofNullable(r.value());
    };
  }

  /**
   * Apply an asynchronous function on the {@code Right} side; the error stays in the {@code Left}
   * side of the result. Bridges sync code into an async pipeline without losing the {@link Either}
   * contract — errors remain visible in the result type rather than being moved to the future's
   * exception channel.
   *
   * <pre>{@code
   * final CompletableFuture<Either<ApiError, User>> f =
   *     validated.flatMapAsync(id -> userClient.fetchAsync(id));  // fetchAsync returns CompletableFuture<User>
   * }</pre>
   */
  default <T> CompletableFuture<Either<L, T>> flatMapAsync(
    final Function<? super R, ? extends CompletableFuture<? extends T>> f
  ) {
    return switch (this) {
      case Left<L, R> l -> CompletableFuture.completedFuture(Either.left(l.value()));
      case Right<L, R> r -> f.apply(r.value()).thenApply(Either::<L, T>right);
    };
  }
}
