package org.telescope.internal.optics;

import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * At-most-one + reconstruct: focuses on at-most-one {@code A} inside an {@code S}, and given an
 * {@code A} can rebuild an {@code S}. The reconstruction half is what distinguishes Prism from
 * {@link Affine}: an Affine can write through a known {@code A}, but a Prism can synthesize an
 * {@code S} from nothing but an {@code A}.
 *
 * <p>Use it for:
 *
 * <ul>
 *   <li>Sealed-type narrowing — {@code Prism<Event, Updated>} succeeds when the event is an {@code
 *       Updated}, fails otherwise, and any {@code Updated} is itself an {@code Event}.
 *   <li>{@code Optional<A>} payload — succeeds on present, fails on empty.
 *   <li>Tag wrappers — {@code Prism<Result<T>, T>} for the success case of a sealed result type.
 * </ul>
 *
 * <pre>{@code
 * final var updated = Prism.<Event, Updated>of(
 *     e -> e instanceof Updated u ? Optional.of(u) : Optional.empty(),
 *     u -> u);                                     // reverseGet: any Updated is an Event
 * final var hit = updated.getOption(event);        // Optional<Updated>
 * final var rebuilt = updated.reverseGet(anUpdate); // Event from just an Updated
 * final var same = updated.modify(deletedEvent, u -> u); // miss → source unchanged
 * }</pre>
 *
 * <h2>Composition (Prism as outer)</h2>
 *
 * <ul>
 *   <li>{@code Prism.then(Prism)} → {@link Prism}, {@code Prism.then(Iso)} → {@link Prism}
 *   <li>{@code Prism.then(Lens)} → {@link Affine} (the Lens has no reconstruct half)
 *   <li>{@code Prism.then(Traversal)} → {@link Traversal} (inherited)
 * </ul>
 *
 * <h2>Laws</h2>
 *
 * <ul>
 *   <li>partial round-trip: {@code prism.getOption(prism.reverseGet(a)).equals(Optional.of(a))}
 *   <li>on a hit, {@code modify} flows through reconstruction; on a miss it returns the source
 *       unchanged.
 * </ul>
 */
public interface Prism<S, A> extends Affine<S, A> {
  /** Try to read the focused {@code A} — present on a match, empty on a miss. */
  @Override
  Optional<A> getOption(final S source);

  /** Rebuild an {@code S} from an {@code A} alone — the half {@link Affine} lacks. */
  S reverseGet(final A value);

  @Override
  default S modify(final S source, final Function<? super A, ? extends A> f) {
    final var current = getOption(source);
    if (current.isEmpty()) return source;
    return reverseGet(f.apply(current.get()));
  }

  @Override
  default Stream<A> getAll(final S source) {
    return getOption(source).stream();
  }

  /** Build a Prism from a partial getter and a reconstructor (must satisfy the round-trip law). */
  static <S, A> Prism<S, A> of(
    final Function<? super S, Optional<A>> getOption,
    final Function<? super A, ? extends S> reverseGet
  ) {
    return new Prism<>() {
      @Override
      public Optional<A> getOption(final S source) {
        return getOption.apply(source);
      }

      @Override
      public S reverseGet(final A value) {
        return reverseGet.apply(value);
      }
    };
  }

  /**
   * Build a Prism that narrows {@code S} to a subtype {@code A} via {@code instanceof}. {@code
   * reverseGet} is the identity widening (every {@code A} already is an {@code S}). The standard
   * tool for sealed-type cases.
   */
  static <S, A extends S> Prism<S, A> downcast(final Class<A> caseClass) {
    return new Prism<>() {
      @Override
      public Optional<A> getOption(final S source) {
        return caseClass.isInstance(source) ? Optional.of(caseClass.cast(source)) : Optional.empty();
      }

      @Override
      public S reverseGet(final A value) {
        return value;
      }
    };
  }

  /** {@code Prism . Prism = Prism} */
  default <B> Prism<S, B> then(final Prism<A, B> next) {
    final var self = this;
    return new Prism<>() {
      @Override
      public Optional<B> getOption(final S source) {
        return self.getOption(source).flatMap(next::getOption);
      }

      @Override
      public S reverseGet(final B value) {
        return self.reverseGet(next.reverseGet(value));
      }
    };
  }

  /** {@code Prism . Iso = Prism} */
  default <B> Prism<S, B> then(final Iso<A, B> next) {
    final var self = this;
    return new Prism<>() {
      @Override
      public Optional<B> getOption(final S source) {
        return self.getOption(source).map(next::to);
      }

      @Override
      public S reverseGet(final B value) {
        return self.reverseGet(next.from(value));
      }
    };
  }

  /** {@code Prism . Lens = Affine} */
  default <B> Affine<S, B> then(final Lens<A, B> next) {
    final var self = this;
    return Affine.of(
      source -> self.getOption(source).map(next::get),
      (source, b) ->
        self
          .getOption(source)
          .map(a -> self.reverseGet(next.set(a, b)))
          .orElse(source)
    );
  }
}
