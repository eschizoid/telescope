package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Sources2;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.Telescope.Accessor;

/**
 * One correspondence row for {@link Telescope#merge(Class, Class, Class, MergeStep2[])} — the
 * two-source forward-only mapper. Each row binds one slot of {@link Sources2} to a target component
 * by name; the slot is inferred from the source accessor's declaring class (the typical case) or
 * specified explicitly via {@link #first} / {@link #second} when the two sources share a class.
 *
 * <p>Sealed over four package-private records; users construct rows through the static factories
 * below and never see the underlying types at call sites.
 *
 * <p>Note that {@code merge} is forward-only — there is no equivalent of {@link Mapping#to(
 * Accessor, Accessor)}'s typed transform or {@code .via(...)} nested-mapper here because the
 * backward direction is documented as unsupported when more than one source contributes to a single
 * target.
 *
 * <h2>Naming</h2>
 *
 * <p>The {@code 2} suffix is deliberate: the 3-source sibling is {@link MergeStep3}, and the family
 * scales to arity 5 with the same template (see {@link io.github.eschizoid.telescope.Sources}).
 * Arity &gt; 5 is a signal to pre-aggregate into a holder type — the family stops there by design.
 *
 * @param <A> the first source type
 * @param <B> the second source type
 * @param <T> the target type
 */
public sealed interface MergeStep2<A, B, T>
  permits MergeStep2.FromFirst, MergeStep2.FromSecond, MergeStep2.FromInferred, MergeStep2.AutoSameName {
  /**
   * A row whose source slot is resolved at {@link Telescope#merge} build time from the source
   * accessor's {@code SerializedLambda} declaring class.
   *
   * <p>Internal — construct via {@link #from(Accessor, Accessor)}.
   */
  record FromInferred<A, B, T, X>(Accessor<?, X> src, Accessor<T, X> tgt) implements MergeStep2<A, B, T> {}

  /**
   * A row that reads its source value from {@link Sources2#first()}.
   *
   * <p>Internal — construct via {@link #first(Accessor, Accessor)}.
   */
  record FromFirst<A, B, T, X>(Accessor<A, X> src, Accessor<T, X> tgt) implements MergeStep2<A, B, T> {}

  /**
   * A row that reads its source value from {@link Sources2#second()}.
   *
   * <p>Internal — construct via {@link #second(Accessor, Accessor)}.
   */
  record FromSecond<A, B, T, X>(Accessor<B, X> src, Accessor<T, X> tgt) implements MergeStep2<A, B, T> {}

  /**
   * A backfill marker: at build time, every target component whose name + type matches a component
   * on the named source slot's class is auto-mapped, EXCEPT names already claimed by explicit rows.
   *
   * <p>Internal — construct via {@link #auto(Class)}.
   */
  record AutoSameName<A, B, T>(Class<?> sourceClass) implements MergeStep2<A, B, T> {}

  /**
   * Row whose source slot is inferred from the source accessor's declaring class. The slot dispatch
   * happens at {@link Telescope#merge} build time using {@link
   * io.github.eschizoid.telescope.internal.LambdaIntrospection#implClassOf} — the same mechanism
   * that drives {@link Mapping#auto()} sibling-class backfill. The recommended factory for the
   * common case where {@code A} and {@code B} are distinct classes.
   *
   * <pre>{@code
   * Telescope.merge(Customer.class, Audit.class, Profile.class,
   *     from(Customer::id,        Profile::id),
   *     from(Audit::createdBy,    Profile::createdBy));
   * }</pre>
   *
   * <p>For the rare case where both sources share a class ({@code Telescope.merge(Pair.class,
   * Pair.class, ...)}), the inferred lookup is ambiguous — use {@link #first(Accessor, Accessor)} /
   * {@link #second(Accessor, Accessor)} explicitly. Wrong source class against the merge's declared
   * pair throws {@link IllegalArgumentException} at build time naming the row.
   */
  static <S, A, B, T, X> MergeStep2<A, B, T> from(final Accessor<S, X> src, final Accessor<T, X> tgt) {
    return new FromInferred<>(src, tgt);
  }

  /**
   * Row that explicitly reads its source value from the first slot of {@link Sources2}. Use this
   * when the two sources share a class so {@link #from(Accessor, Accessor)}'s class-based slot
   * inference is ambiguous, or when the user prefers explicit slot naming at the call site.
   *
   * <pre>{@code
   * MergeStep2.first(Customer::id, Profile::id)
   * }</pre>
   */
  static <A, B, T, X> MergeStep2<A, B, T> first(final Accessor<A, X> src, final Accessor<T, X> tgt) {
    return new FromFirst<>(src, tgt);
  }

  /**
   * Row that explicitly reads its source value from the second slot of {@link Sources2}. Mirrors
   * {@link #first} for the {@code B}-side.
   *
   * <pre>{@code
   * MergeStep2.second(Audit::createdBy, Profile::createdBy)
   * }</pre>
   */
  static <A, B, T, X> MergeStep2<A, B, T> second(final Accessor<B, X> src, final Accessor<T, X> tgt) {
    return new FromSecond<>(src, tgt);
  }

  /**
   * Auto-backfill row: every target component whose name + type matches a component on {@code
   * sourceClass} gets a free {@code from(sourceClass::<comp>, target::<comp>)} row at build time —
   * but only for names not already claimed by an explicit row in the same merge.
   *
   * <p>Mirrors {@link Mapping#auto()} for the multi-source case: when 9 of 10 target fields come
   * straight from one source by name, write one {@code auto(Source.class)} row plus the one
   * exception, instead of 10 explicit rows.
   *
   * <pre>{@code
   * Telescope.merge(Customer.class, Audit.class, Profile.class,
   *     auto(Customer.class),                                // backfills id, email, name, ...
   *     from(Audit::createdBy,    Profile::createdBy),       // the exception
   *     from(Audit::createdAt,    Profile::createdAt));      // the exception
   * }</pre>
   *
   * <p>{@code sourceClass} must be {@code A} or {@code B} (the merge's declared source classes); a
   * wrong class throws {@link IllegalArgumentException} at build time.
   */
  static <A, B, T> MergeStep2<A, B, T> auto(final Class<?> sourceClass) {
    return new AutoSameName<>(sourceClass);
  }
}
