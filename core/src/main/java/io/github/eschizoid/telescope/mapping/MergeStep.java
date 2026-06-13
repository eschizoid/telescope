package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Sources2;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.Telescope.Accessor;

/**
 * One correspondence row for {@link Telescope#merge(Class, Class, Class, MergeStep[])} — the
 * two-source forward-only mapper. Each row picks which {@link Sources2} slot to read from (via
 * {@link #first} for the {@code A}-side or {@link #second} for the {@code B}-side) and which target
 * component receives the value.
 *
 * <p>Sealed over two package-private records; users construct rows through the static factories
 * below and never see the underlying types at call sites.
 *
 * <p>Note that {@code merge} is forward-only — there is no equivalent of {@link Mapping#to(
 * Accessor, Accessor)}'s typed transform or {@code .via(...)} nested-mapper here because the
 * backward direction is documented as unsupported when more than one source contributes to a single
 * target.
 *
 * @param <A> the first source type
 * @param <B> the second source type
 * @param <T> the target type
 */
public sealed interface MergeStep<A, B, T> permits MergeStep.FromFirst, MergeStep.FromSecond {
  /**
   * A row that reads its source value from {@link Sources2#first()}.
   *
   * <p>Internal — construct via {@link #first(Accessor, Accessor)}.
   */
  record FromFirst<A, B, T, X>(Accessor<A, X> src, Accessor<T, X> tgt) implements MergeStep<A, B, T> {}

  /**
   * A row that reads its source value from {@link Sources2#second()}.
   *
   * <p>Internal — construct via {@link #second(Accessor, Accessor)}.
   */
  record FromSecond<A, B, T, X>(Accessor<B, X> src, Accessor<T, X> tgt) implements MergeStep<A, B, T> {}

  /**
   * Row that reads its source value from the first slot of {@link Sources2}. The source accessor is
   * bound to type {@code A} (the first source), the target accessor to the target type {@code T};
   * the leaf type {@code X} is shared, mirroring {@link Mapping#to(Accessor, Accessor)}'s
   * same-typed contract.
   *
   * <pre>{@code
   * MergeStep.first(Customer::id, Profile::id)
   * }</pre>
   */
  static <A, B, T, X> MergeStep<A, B, T> first(final Accessor<A, X> src, final Accessor<T, X> tgt) {
    return new FromFirst<>(src, tgt);
  }

  /**
   * Row that reads its source value from the second slot of {@link Sources2}. Mirrors {@link
   * #first} for the {@code B}-side.
   *
   * <pre>{@code
   * MergeStep.second(Audit::createdBy, Profile::createdBy)
   * }</pre>
   */
  static <A, B, T, X> MergeStep<A, B, T> second(final Accessor<B, X> src, final Accessor<T, X> tgt) {
    return new FromSecond<>(src, tgt);
  }
}
