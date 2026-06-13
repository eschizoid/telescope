package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Sources3;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.Telescope.Accessor;

/**
 * One correspondence row for {@link Telescope#merge(Class, Class, Class, Class, MergeStep3[])} —
 * the three-source forward-only mapper, sibling of {@link MergeStep2}. Each row binds one slot of
 * {@link Sources3} to a target component by name; the slot is inferred from the source accessor's
 * declaring class (the typical case) or specified explicitly via {@link #first} / {@link #second} /
 * {@link #third} when sources share a class.
 *
 * <p>Sealed over five package-private records; users construct rows through the static factories
 * below.
 *
 * <p>Same forward-only contract as {@link MergeStep2}: multi-source rebuild has no general inverse,
 * so {@code Mapper#backward} on the produced mapper throws.
 *
 * @param <A> the first source type
 * @param <B> the second source type
 * @param <C> the third source type
 * @param <T> the target type
 */
public sealed interface MergeStep3<A, B, C, T>
  permits
    MergeStep3.FromFirst,
    MergeStep3.FromSecond,
    MergeStep3.FromThird,
    MergeStep3.FromInferred,
    MergeStep3.AutoSameName {
  /** Slot resolved at build time from the source accessor's declaring class. */
  record FromInferred<A, B, C, T, X>(Accessor<?, X> src, Accessor<T, X> tgt) implements MergeStep3<A, B, C, T> {}

  /** Row that reads from {@link Sources3#first()}. */
  record FromFirst<A, B, C, T, X>(Accessor<A, X> src, Accessor<T, X> tgt) implements MergeStep3<A, B, C, T> {}

  /** Row that reads from {@link Sources3#second()}. */
  record FromSecond<A, B, C, T, X>(Accessor<B, X> src, Accessor<T, X> tgt) implements MergeStep3<A, B, C, T> {}

  /** Row that reads from {@link Sources3#third()}. */
  record FromThird<A, B, C, T, X>(Accessor<C, X> src, Accessor<T, X> tgt) implements MergeStep3<A, B, C, T> {}

  /** Auto-backfill marker bound to one of the three source classes. */
  record AutoSameName<A, B, C, T>(Class<?> sourceClass) implements MergeStep3<A, B, C, T> {}

  /**
   * Row whose source slot is inferred from the source accessor's declaring class. Recommended when
   * the three source classes are distinct.
   *
   * <pre>{@code
   * Telescope.merge(Customer.class, Audit.class, LineItem.class, Invoice.class,
   *     from(Customer::id,            Invoice::customerId),
   *     from(Audit::createdBy,        Invoice::createdBy),
   *     from(LineItem::totalCents,    Invoice::totalCents));
   * }</pre>
   *
   * <p>Throws {@link IllegalArgumentException} at build time if the source class matches none of
   * the three slots.
   */
  static <S, A, B, C, T, X> MergeStep3<A, B, C, T> from(final Accessor<S, X> src, final Accessor<T, X> tgt) {
    return new FromInferred<>(src, tgt);
  }

  /** Row that explicitly reads from slot 1 (the {@code A} side). */
  static <A, B, C, T, X> MergeStep3<A, B, C, T> first(final Accessor<A, X> src, final Accessor<T, X> tgt) {
    return new FromFirst<>(src, tgt);
  }

  /** Row that explicitly reads from slot 2 (the {@code B} side). */
  static <A, B, C, T, X> MergeStep3<A, B, C, T> second(final Accessor<B, X> src, final Accessor<T, X> tgt) {
    return new FromSecond<>(src, tgt);
  }

  /** Row that explicitly reads from slot 3 (the {@code C} side). */
  static <A, B, C, T, X> MergeStep3<A, B, C, T> third(final Accessor<C, X> src, final Accessor<T, X> tgt) {
    return new FromThird<>(src, tgt);
  }

  /**
   * Auto-backfill row: every target component whose name + type matches a component on {@code
   * sourceClass} gets a free {@code from(sourceClass::<comp>, target::<comp>)} row at build time,
   * minus names claimed by explicit rows. Mirrors {@link MergeStep2#auto(Class)}.
   *
   * <p>{@code sourceClass} must be {@code A}, {@code B}, or {@code C} (the merge's declared source
   * classes); a wrong class throws {@link IllegalArgumentException} at build time.
   */
  static <A, B, C, T> MergeStep3<A, B, C, T> auto(final Class<?> sourceClass) {
    return new AutoSameName<>(sourceClass);
  }
}
