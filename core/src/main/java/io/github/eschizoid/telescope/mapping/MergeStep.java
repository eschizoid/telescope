package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Sources;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.Telescope.Accessor;

/**
 * One correspondence row for {@link Telescope#merge(Class, MergeStep[])} — the N-source
 * forward-only mapper. Each row binds one entry of {@link Sources} (looked up by source class) to a
 * target component by name.
 *
 * <p>Single arity-agnostic type. The source slot is recovered from the source accessor's declaring
 * class via {@code SerializedLambda} — the same mechanism that drives {@code Mapping.auto()} — so
 * the row factories scale to any number of source classes without per-arity specialization.
 *
 * <p>Sealed over two package-private records; users construct rows through the static factories
 * below.
 *
 * <p>Note that {@code merge} is forward-only — there is no equivalent of {@link Mapping#to(
 * Accessor, Accessor, java.util.function.Function, java.util.function.Function)}'s typed transform
 * or {@code .via(...)} nested-mapper here because the backward direction is documented as
 * unsupported when more than one source contributes to a single target.
 *
 * @param <T> the target type
 */
public sealed interface MergeStep<T> permits MergeStep.FromInferred, MergeStep.AutoSameName {
  /**
   * A row whose source class is resolved at {@link Telescope#merge} build time from the source
   * accessor's {@code SerializedLambda} declaring class.
   *
   * <p>Internal — construct via {@link #from(Accessor, Accessor)}.
   */
  record FromInferred<T, X>(Accessor<?, X> src, Accessor<T, X> tgt) implements MergeStep<T> {}

  /**
   * A backfill marker: at build time, every target component whose name + type matches a component
   * on {@code sourceClass} is auto-mapped, EXCEPT names already claimed by explicit rows.
   *
   * <p>Internal — construct via {@link #auto(Class)}.
   */
  record AutoSameName<T>(Class<?> sourceClass) implements MergeStep<T> {}

  /**
   * Row whose source class is inferred from the source accessor's declaring class. At build time
   * the engine validates the inferred class is present in the merge's source bag declaration; at
   * forward time it reads the source via {@link Sources#byClass(Class)}.
   *
   * <pre>{@code
   * Telescope.merge(Profile.class,
   *     from(Customer::id,        Profile::id),
   *     from(Audit::createdBy,    Profile::createdBy),
   *     from(LineItem::totalCents, Profile::totalCents));
   * }</pre>
   *
   * <p>Wrong source class (no {@code Sources.byClass} entry at forward time) throws {@link
   * IllegalStateException} naming the missing class.
   */
  static <S, T, X> MergeStep<T> from(final Accessor<S, X> src, final Accessor<T, X> tgt) {
    return new FromInferred<>(src, tgt);
  }

  /**
   * Auto-backfill row: every target component whose name + type matches a component on {@code
   * sourceClass} gets a free {@code from(sourceClass::<comp>, target::<comp>)} row at build time —
   * minus names already claimed by explicit rows in the same merge.
   *
   * <p>Mirrors {@link Mapping#auto()} for the multi-source case: when many target fields come
   * straight from one source by name, write one {@code auto(Source.class)} row plus the exceptions,
   * instead of N explicit rows.
   *
   * <pre>{@code
   * Telescope.merge(Profile.class,
   *     auto(Customer.class),                                // backfills id, email, name, ...
   *     from(Audit::createdBy,    Profile::createdBy),       // the exception
   *     from(Audit::createdAt,    Profile::createdAt));      // the exception
   * }</pre>
   */
  static <T> MergeStep<T> auto(final Class<?> sourceClass) {
    return new AutoSameName<>(sourceClass);
  }
}
