package com.github.eschizoid.telescope.internal.optics;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Static-factory aggregator for hand-built optics. Each method is a thin alias for the
 * corresponding {@code of(...)} / {@code downcast(...)} on the optic interface — exists so callers
 * can write {@code Focus.lens(...)} / {@code Focus.prism(...)} from one import instead of pulling
 * in each optic type individually.
 *
 * <p><strong>Not the {@code @Focus} annotation.</strong> This {@code
 * com.github.eschizoid.telescope.internal.optics.Focus} is an internal, non-instantiable utility
 * class of factory methods. It is unrelated to the planned public {@code @Focus} codegen annotation
 * (v0.3); the name collision is incidental.
 *
 * <p>If you're using the DSL via {@link com.github.eschizoid.telescope.Telescope} you almost never
 * need this class; the navigation methods build the right optics internally. It's here for
 * extension points (custom collection traversals, hand-tuned lenses for hot paths, codegen output
 * in v0.3).
 */
public final class Focus {

  private Focus() {}

  /** Build a {@link Lens} from a getter and a (S, A) -&gt; S setter. */
  public static <S, A> Lens<S, A> lens(
    final Function<? super S, ? extends A> get,
    final BiFunction<? super S, ? super A, ? extends S> set
  ) {
    return Lens.of(get, set);
  }

  /**
   * Build a {@link Prism} that narrows {@code S} to a subtype {@code A}. The standard tool for
   * sealed-type cases — {@code Focus.prism(Updated.class)} succeeds on {@code Updated} events,
   * passes through the rest.
   */
  public static <S, A extends S> Prism<S, A> prism(final Class<A> caseClass) {
    return Prism.downcast(caseClass);
  }

  /**
   * Build a {@link Prism} from a partial getter and a reconstructor. Caller is responsible for the
   * partial round-trip law: {@code getOption(reverseGet(a)).equals(Optional.of(a))}.
   */
  public static <S, A> Prism<S, A> prism(
    final Function<? super S, Optional<A>> getOption,
    final Function<? super A, ? extends S> reverseGet
  ) {
    return Prism.of(getOption, reverseGet);
  }

  /**
   * Build an {@link Iso} from two inverse functions. Caller is responsible for the round-trip laws
   * — {@code forward} and {@code backward} must compose to identity in both directions over the
   * values that flow through the optic.
   */
  public static <A, B> Iso<A, B> iso(
    final Function<? super A, ? extends B> forward,
    final Function<? super B, ? extends A> backward
  ) {
    return Iso.of(forward, backward);
  }

  /**
   * Build an {@link Affine} from a partial getter and a (S, A) -&gt; S setter. Unlike a Prism, an
   * Affine can't reconstruct an {@code S} from just an {@code A} — you need an existing {@code S}
   * to write into.
   */
  public static <S, A> Affine<S, A> affine(
    final Function<? super S, Optional<A>> getOption,
    final BiFunction<? super S, ? super A, ? extends S> set
  ) {
    return Affine.of(getOption, set);
  }
}
