package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope;

/**
 * Per-target write-strategy override consumed by {@link Telescope#map(Class, Class, MapStep...)},
 * built by {@link Mapping#writeBean(Class, WriteStrategy)} and {@link Mapping#writeBeans}. Tells
 * the deep-mapping engine which {@code Beans.BeanWriter} strategy to use when constructing an
 * instance of {@code B}, overriding the auto-detected strategy from {@code Beans.autoWriter}.
 *
 * <p>With no hint, a bean is built through the first of these it offers: a static {@code
 * builder()}, then a public all-args constructor whose parameter names match the properties
 * (compiled with {@code -parameters} or {@code -g}), then a no-arg constructor with setters. The
 * generated {@code @Bridge}, {@code @BeanFocus} and {@code @FromMap} code takes the same order.
 *
 * <p>Closes the gap on immutable all-args-only POJOs (no builder, no no-arg constructor) by letting
 * the user pin {@link WriteStrategy#CONSTRUCTOR}. Also useful when several strategies apply and the
 * user wants a different one than that order picks (e.g., {@link WriteStrategy#SETTERS} over a
 * class that also has a builder).
 *
 * <pre>{@code
 * import static io.github.eschizoid.telescope.mapping.Mapping.*;
 *
 * final Telescope<OrderRecord, OrderPojo> conv = Telescope.map(
 *     OrderRecord.class, OrderPojo.class,
 *     writeBean(OrderPojo.class, CONSTRUCTOR),
 *     to(OrderRecord::sku, OrderPojo::getSku));
 * }</pre>
 *
 * <p><b>Direction of application.</b> The hint is keyed on the class. During recursion, the hint's
 * writer is used whenever {@code targetClass} appears <em>as the bean side</em> of a constructed
 * value — which is the target side during {@code Iso.to} and the source side during {@code
 * Iso.from} ({@code Mapper.backward}, {@code Mapper.patch}). If you map {@code Foo → Foo} or {@code
 * Foo} appears on both sides of a deep mapping, the single hint governs both directions — which is
 * almost always the right behavior, since the bean's construction strategy is a property of the
 * class, not of the direction.
 *
 * <p><b>Validation.</b> Hints are validated eagerly at {@code Telescope.map(...)} time:
 *
 * <ul>
 *   <li>Hint target must not be a record (records always rebuild via the canonical constructor)
 *   <li>No two hints may share the same target class
 *   <li>The chosen strategy must be applicable to the target class (e.g., {@code BUILDER} requires
 *       a static {@code builder()} method) — checked by instantiating the writer immediately
 *   <li>Every hint's target class must be reached at least once during the deep recursion; unused
 *       hints (typos, refactor stragglers) are reported rather than silently ignored
 * </ul>
 */
public sealed interface WriteHint<B> extends MapStep permits WriteHint.BeanWriteHint, WriteHint.DefaultWriteHint {
  /**
   * The target class this hint applies to — used to key the per-class strategy lookup. {@code null}
   * for a {@link Mapping#writeBeans(WriteStrategy)} default hint, which applies to every
   * encountered bean target instead of one specific class.
   */
  Class<B> targetClass();

  /** The chosen write strategy for {@link #targetClass()}. */
  WriteStrategy strategy();

  /** Package-private record impl; users construct via {@link Mapping#writeBean}. */
  record BeanWriteHint<B>(Class<B> targetClass, WriteStrategy strategy) implements WriteHint<B> {}

  /**
   * Package-private record impl for {@link Mapping#writeBeans(WriteStrategy)}. Carries no target
   * class (the strategy applies to every encountered bean target lacking a per-class override);
   * {@link #targetClass()} returns {@code null} as the sentinel.
   */
  record DefaultWriteHint(WriteStrategy strategy) implements WriteHint<Object> {
    @Override
    public Class<Object> targetClass() {
      return null;
    }
  }
}
