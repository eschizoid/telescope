package io.github.eschizoid.telescope.internal.optics;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Read+write exactly-one: focuses on exactly one {@code A} inside an {@code S} — every {@code S}
 * has one, and you can always write a new one back. Lens is the workhorse; most record-field
 * navigation produces a Lens.
 *
 * <p>Use it for required record components: {@code User::name}, {@code Address::city}. If the
 * component is an {@code Optional} or part of a sealed hierarchy, reach for a {@link Prism} (or a
 * Lens composed with one).
 *
 * <pre>{@code
 * final var name = Lens.<User, String>of(User::name, (u, n) -> new User(n, u.age()));
 * final var who = name.get(user);                  // read the one A
 * final var renamed = name.set(user, "Ada");       // write a new A
 * final var upper = name.modify(user, String::toUpperCase);
 * }</pre>
 *
 * <h2>Composition (Lens as outer)</h2>
 *
 * <ul>
 *   <li>{@code Lens.then(Lens)} → {@link Lens}, {@code Lens.then(Iso)} → {@link Lens}
 *   <li>{@code Lens.then(Prism)} → {@link Affine} (the Prism may miss, so reconstructibility is
 *       lost)
 *   <li>{@code Lens.then(Traversal)} → {@link Traversal} (inherited; widening is one-way)
 * </ul>
 *
 * <h2>Laws</h2>
 *
 * <ul>
 *   <li>get-set: {@code lens.set(s, lens.get(s)).equals(s)} — setting back what you got is a no-op
 *   <li>set-get: {@code lens.get(lens.set(s, a)).equals(a)} — what you set is what you read
 *   <li>set-set: {@code lens.set(lens.set(s, a1), a2).equals(lens.set(s, a2))} — the last set wins
 * </ul>
 */
public interface Lens<S, A> extends Affine<S, A>, Getter<S, A> {
  /** Read the one focused {@code A} out of {@code source}. */
  @Override
  A get(S source);

  /** Write {@code value} as the focused {@code A}, returning a new {@code S}. */
  @Override
  S set(S source, A value);

  @Override
  default S modify(final S source, final Function<? super A, ? extends A> f) {
    return set(source, f.apply(get(source)));
  }

  /**
   * Null-source projection: a Lens viewed as an Affine yields {@link Optional#empty()} when the
   * source is {@code null}, instead of dispatching {@code get(null)} through the captured method
   * reference (which would NPE on the receiver). For a non-null source the strict {@code
   * Optional.of(get(source))} form is preserved — a Lens whose getter returns {@code null} on a
   * non-null source is a lens-law violation, and {@code Optional.of(null)} surfaces that directly
   * instead of papering over it.
   */
  @Override
  default Optional<A> getOption(final S source) {
    if (source == null) return Optional.empty();
    return Optional.of(get(source));
  }

  /**
   * Null-source projection: a Lens viewed as a Traversal yields an empty stream when the source is
   * {@code null}, instead of dispatching {@code get(null)} through the captured method reference
   * (which would NPE on the receiver). Lets multi-hop bean paths short-circuit on nullable
   * intermediates inside composed reads. Atomic {@code .get(null)} still NPEs if the user's getter
   * does — strict-lens semantics for direct gets are unchanged.
   */
  @Override
  default Stream<A> getAll(final S source) {
    if (source == null) return Stream.empty();
    return Stream.of(get(source));
  }

  /** Build a Lens from a getter and an {@code (S, A) -> S} setter. */
  static <S, A> Lens<S, A> of(
    final Function<? super S, ? extends A> get,
    final BiFunction<? super S, ? super A, ? extends S> set
  ) {
    return new Lens<>() {
      @Override
      public A get(final S source) {
        return get.apply(source);
      }

      @Override
      public S set(final S source, final A value) {
        return set.apply(source, value);
      }
    };
  }

  /** {@code Lens . Lens = Lens} */
  default <B> Lens<S, B> then(final Lens<A, B> next) {
    final var self = this;
    return new Lens<>() {
      @Override
      public B get(final S source) {
        return next.get(self.get(source));
      }

      @Override
      public S set(final S source, final B value) {
        return self.set(source, next.set(self.get(source), value));
      }
    };
  }

  /** {@code Lens . Iso = Lens} */
  default <B> Lens<S, B> then(final Iso<A, B> next) {
    final var self = this;
    return new Lens<>() {
      @Override
      public B get(final S source) {
        return next.to(self.get(source));
      }

      @Override
      public S set(final S source, final B value) {
        return self.set(source, next.from(value));
      }
    };
  }

  /** {@code Lens . Prism = Affine} */
  default <B> Affine<S, B> then(final Prism<A, B> next) {
    final var self = this;
    return new Affine<>() {
      @Override
      public Optional<B> getOption(final S source) {
        return next.getOption(self.get(source));
      }

      @Override
      public S modify(final S source, final Function<? super B, ? extends B> f) {
        final var a = self.get(source);
        return next
          .getOption(a)
          .map(b -> self.set(source, next.reverseGet(f.apply(b))))
          .orElse(source);
      }
    };
  }
}
