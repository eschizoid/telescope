package io.github.eschizoid.telescope.effects;

import io.github.eschizoid.telescope.Telescope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Sum type for "valid value" or "accumulated errors." Like {@link Either} but the {@code Invalid}
 * case always holds a list, and the applicative instance accumulates errors across all branches
 * instead of short-circuiting on the first one.
 *
 * <p><b>Accumulating semantics.</b> Where {@link Either} short-circuits on the first {@code Left},
 * {@code Validated} keeps going: {@link #combine}, {@link #combineAll}, and {@link
 * Telescope#updateValidated} run every branch and gather <em>all</em> errors into one {@code
 * Invalid}. Reach for {@code Validated} when you want to report every problem at once (form
 * validation, batch import); reach for {@link Either} when the first failure is enough.
 *
 * <p>The error list lives inside the {@code Invalid} case, so the error type {@code E} is named
 * once on the type — the public signatures speak in terms of {@code E}, not {@code List<E>}. Only
 * {@link #toEither} surfaces the list, since {@code Either}'s left side carries a single value.
 *
 * <p>Use {@code Validated<E, A>} when you want to collect every problem ({@link
 * Telescope#updateValidated} reports every focused element that failed); use {@link Either} when
 * the first failure is enough.
 *
 * <pre>{@code
 * final Validated<EmailError, Company> result = emails.updateValidated(company, EmailParser::parse);
 * return switch (result) {
 *   case Validated.Valid<?, Company>(var c) -> save(c);
 *   case Validated.Invalid<EmailError, ?>(var errors) -> respondBadRequest(errors);
 * };
 * }</pre>
 */
public sealed interface Validated<E, A> {
  /**
   * The success case. Pattern-match it to read the carried value, e.g. {@code case
   * Validated.Valid<?, Company>(var c) -> save(c)}.
   */
  record Valid<E, A>(A value) implements Validated<E, A> {}

  /**
   * The failure case — one or more accumulated errors. The list is copied defensively on
   * construction, so the held list is immutable.
   */
  record Invalid<E, A>(List<E> errors) implements Validated<E, A> {
    public Invalid {
      errors = List.copyOf(errors);
    }
  }

  /**
   * Construct a {@code Valid}.
   *
   * <pre>{@code
   * final Validated<String, Integer> ok = Validated.valid(42);
   * }</pre>
   */
  static <E, A> Validated<E, A> valid(final A value) {
    return new Valid<>(value);
  }

  /**
   * Construct an {@code Invalid} with a single error.
   *
   * <pre>{@code
   * final Validated<String, Integer> bad = Validated.invalid("not a number");
   * }</pre>
   */
  static <E, A> Validated<E, A> invalid(final E error) {
    return new Invalid<>(List.of(error));
  }

  /**
   * Construct an {@code Invalid} from a list of errors. The list is copied defensively.
   *
   * <pre>{@code
   * final Validated<String, Integer> bad = Validated.invalid(List.of("too low", "not even"));
   * }</pre>
   */
  static <E, A> Validated<E, A> invalid(final List<E> errors) {
    return new Invalid<>(errors);
  }

  /**
   * {@code true} for {@code Valid}. Sibling of {@link #isInvalid()}.
   *
   * <pre>{@code
   * Validated.valid(42).isValid();        // true
   * Validated.invalid("e").isValid();     // false
   * }</pre>
   */
  default boolean isValid() {
    return this instanceof Valid<E, A>;
  }

  /**
   * {@code true} for {@code Invalid}. The complement of {@link #isValid()}; see it for an example.
   */
  default boolean isInvalid() {
    return this instanceof Invalid<E, A>;
  }

  /**
   * Fold both cases into a single value. The {@code onInvalid} branch receives the full list of
   * accumulated errors.
   *
   * <pre>{@code
   * final String msg = result.fold(
   *     errors -> errors.size() + " problem(s)",
   *     company -> "saved " + company.name());
   * }</pre>
   */
  default <T> T fold(
    final Function<? super List<E>, ? extends T> onInvalid,
    final Function<? super A, ? extends T> onValid
  ) {
    if (this instanceof Invalid<E, A> inv) return onInvalid.apply(inv.errors());
    if (this instanceof Valid<E, A> v) return onValid.apply(v.value());
    throw new IllegalStateException("unreachable: Validated is sealed");
  }

  /**
   * Map the valid side. Leaves an {@code Invalid} (and its accumulated errors) unchanged.
   *
   * <pre>{@code
   * Validated.valid(42).map(n -> n + 1);    // Valid(43)
   * Validated.<String, Integer>invalid("e").map(n -> n + 1);  // Invalid(["e"])
   * }</pre>
   */
  default <T> Validated<E, T> map(final Function<? super A, ? extends T> f) {
    if (this instanceof Invalid<E, A> inv) return new Invalid<>(inv.errors());
    if (this instanceof Valid<E, A> v) return new Valid<>(f.apply(v.value()));
    throw new IllegalStateException("unreachable: Validated is sealed");
  }

  /**
   * Map every accumulated error. Leaves a {@code Valid} unchanged. Useful for translating typed
   * errors to a different error type at a boundary. {@code f} is applied to each element of the
   * error list.
   *
   * <pre>{@code
   * final Validated<String, Company> reportable = result.mapErrors(EmailError::message);
   * }</pre>
   */
  default <T> Validated<T, A> mapErrors(final Function<? super E, ? extends T> f) {
    if (this instanceof Invalid<E, A> inv) return new Invalid<>(inv.errors().stream().<T>map(f).toList());
    if (this instanceof Valid<E, A> v) return new Valid<>(v.value());
    throw new IllegalStateException("unreachable: Validated is sealed");
  }

  /**
   * Sequence two validations. {@code Invalid} short-circuits with the original errors; {@code
   * Valid} feeds its value into the next validation step. Note: this is short-circuiting (unlike
   * {@link #combine}, which accumulates). Use {@code andThen} when one step depends on the result
   * of the previous; use {@code combine} for independent validations whose errors should both be
   * reported.
   *
   * <pre>{@code
   * // parse first, then range-check the parsed value — the range check only runs if parse succeeded
   * final Validated<String, Integer> v = parse(input).andThen(this::inRange);
   * }</pre>
   */
  default <B> Validated<E, B> andThen(final Function<? super A, ? extends Validated<E, B>> f) {
    if (this instanceof Invalid<E, A> inv) return new Invalid<>(inv.errors());
    if (this instanceof Valid<E, A> v) return f.apply(v.value());
    throw new IllegalStateException("unreachable: Validated is sealed");
  }

  /**
   * Bridge to {@link Either}: {@code Invalid(errors)} becomes {@code Left(errors)}; {@code
   * Valid(v)} becomes {@code Right(v)}. Use when accumulated validation results need to feed into
   * short-circuiting code. This is the one method that surfaces the error list, since {@link
   * Either}'s left side carries a single value.
   *
   * <pre>{@code
   * final Either<List<EmailError>, Company> e = result.toEither();
   * }</pre>
   */
  default Either<List<E>, A> toEither() {
    if (this instanceof Invalid<E, A> inv) return Either.left(inv.errors());
    if (this instanceof Valid<E, A> v) return Either.right(v.value());
    throw new IllegalStateException("unreachable: Validated is sealed");
  }

  /**
   * Return the {@code Valid} value, or {@code defaultValue} if this is {@code Invalid}.
   *
   * <pre>{@code
   * Validated.valid(42).getOrElse(0);   // 42
   * Validated.<String, Integer>invalid("e").getOrElse(0);  // 0
   * }</pre>
   */
  default A getOrElse(final A defaultValue) {
    if (this instanceof Invalid<E, A>) return defaultValue;
    if (this instanceof Valid<E, A> v) return v.value();
    throw new IllegalStateException("unreachable: Validated is sealed");
  }

  /**
   * Return the {@code Valid} value, or compute one via {@code supplier} if this is {@code Invalid}.
   * Use over {@link #getOrElse(Object)} when the default is expensive to construct.
   *
   * <pre>{@code
   * final Config c = result.getOrElseGet(Config::loadFallback);  // loadFallback runs only on Invalid
   * }</pre>
   */
  default A getOrElseGet(final Supplier<? extends A> supplier) {
    if (this instanceof Invalid<E, A>) return supplier.get();
    if (this instanceof Valid<E, A> v) return v.value();
    throw new IllegalStateException("unreachable: Validated is sealed");
  }

  /**
   * Drop the errors and bridge to {@link Optional}: {@code Valid(v)} becomes {@code
   * Optional.of(v)}; {@code Invalid(_)} becomes {@code Optional.empty()}. Use when downstream code
   * only cares about the success path. A {@code Valid(null)} maps to {@code Optional.empty()}.
   *
   * <pre>{@code
   * result.toOptional().ifPresent(this::save);
   * }</pre>
   */
  default Optional<A> toOptional() {
    if (this instanceof Invalid<E, A>) return Optional.empty();
    if (this instanceof Valid<E, A> v) return Optional.ofNullable(v.value());
    throw new IllegalStateException("unreachable: Validated is sealed");
  }

  /**
   * Apply an asynchronous function on the {@code Valid} side; the accumulated errors stay in the
   * {@code Invalid} side of the result. Bridges sync validation into an async pipeline without
   * losing the {@link Validated} contract: errors remain visible in the result type rather than
   * being moved to the future's exception channel.
   *
   * <p>Mirrors {@link Either#flatMapAsync} but preserves the accumulated error list when {@code
   * Invalid}.
   *
   * <pre>{@code
   * final CompletableFuture<Validated<EmailError, User>> f =
   *     validated.flatMapAsync(id -> userClient.fetchAsync(id));  // fetchAsync returns CompletableFuture<User>
   * }</pre>
   */
  default <B> CompletableFuture<Validated<E, B>> flatMapAsync(
    final Function<? super A, ? extends CompletableFuture<? extends B>> f
  ) {
    if (this instanceof Invalid<E, A> inv) return CompletableFuture.completedFuture(Validated.invalid(inv.errors()));
    if (this instanceof Valid<E, A> v) return f.apply(v.value()).thenApply(Validated::<E, B>valid);
    throw new IllegalStateException("unreachable: Validated is sealed");
  }

  /**
   * Combine a list of {@code Validated} values into a single {@code Validated} carrying the list of
   * valid values. If every input is {@code Valid}, returns {@code Valid(List<A>)} preserving input
   * order. Otherwise returns {@code Invalid} accumulating every error across every input — the
   * N-ary equivalent of {@link #combine(Validated, Validated, java.util.function.BiFunction)}.
   *
   * <pre>{@code
   * final List<Validated<OrderError, Order>> per = orders.stream().map(this::validate).toList();
   * final Validated<OrderError, List<Order>> all = Validated.combineAll(per);
   * }</pre>
   *
   * <p>For 3+ pairwise combinations with a custom combining function, chain {@link
   * #combine(Validated, Validated, java.util.function.BiFunction)} calls — this method is the only
   * N-ary combinator the library ships.
   */
  static <E, A> Validated<E, List<A>> combineAll(final List<? extends Validated<E, A>> inputs) {
    final var values = new ArrayList<A>(inputs.size());
    final var errors = new ArrayList<E>();
    for (final var v : inputs) {
      if (v instanceof Valid<E, A> ok) {
        values.add(ok.value());
      } else if (v instanceof Invalid<E, A> bad) {
        errors.addAll(bad.errors());
      }
    }
    if (!errors.isEmpty()) return new Invalid<>(errors);
    return new Valid<>(List.copyOf(values));
  }

  /**
   * Combine two {@code Validated} values with a binary function. If either is {@code Invalid},
   * accumulates both error lists; otherwise applies the function to the two valid values. This is
   * the applicative behavior — different from {@link Either#flatMap}, which short-circuits. For an
   * arbitrary number of inputs, see {@link #combineAll}.
   *
   * <pre>{@code
   * // both checks run; if both fail, the result Invalid carries both errors
   * final Validated<String, User> user =
   *     Validated.combine(validateName(n), validateAge(a), User::new);
   * }</pre>
   */
  static <E, A, B, C> Validated<E, C> combine(
    final Validated<E, A> left,
    final Validated<E, B> right,
    final BiFunction<? super A, ? super B, ? extends C> f
  ) {
    if (left instanceof Invalid<E, A> leftInvalid && right instanceof Invalid<E, B> rightInvalid) {
      final var leftErrors = leftInvalid.errors();
      final var rightErrors = rightInvalid.errors();
      final var combined = new ArrayList<E>(leftErrors.size() + rightErrors.size());
      combined.addAll(leftErrors);
      combined.addAll(rightErrors);
      return new Invalid<>(combined);
    }
    if (left instanceof Invalid<E, A> leftInvalid) return new Invalid<>(leftInvalid.errors());
    if (right instanceof Invalid<E, B> rightInvalid) return new Invalid<>(rightInvalid.errors());
    final var l = ((Valid<E, A>) left).value();
    final var r = ((Valid<E, B>) right).value();
    return new Valid<>(f.apply(l, r));
  }
}
