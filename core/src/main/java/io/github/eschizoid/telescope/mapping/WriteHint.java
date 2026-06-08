package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope;

/**
 * Per-target write-strategy override consumed by {@link Telescope#map(Class, Class, MapStep...)}.
 * Tells the deep-mapping engine which {@code Beans.BeanWriter} strategy to use when constructing an
 * instance of {@code B}, overriding the auto-detected strategy from {@code Beans.autoWriter}.
 *
 * <p>Closes the gap on immutable all-args-only POJOs (no builder, no no-arg constructor) by letting
 * the user pin {@link WriteStrategy#CONSTRUCTOR}. Also useful when several strategies apply and the
 * user wants to force a specific one (e.g., {@link WriteStrategy#FIELDS} over a class that also has
 * a builder).
 *
 * <pre>{@code
 * import static io.github.eschizoid.telescope.mapping.Mapping.to;
 * import static io.github.eschizoid.telescope.mapping.WriteHint.writeBean;
 * import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.CONSTRUCTOR;
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
   * The {@code Beans.BeanWriter} strategies available for explicit selection.
   *
   * <ul>
   *   <li>{@link #BUILDER} — requires a static {@code builder()} method on the target
   *   <li>{@link #SETTERS} — requires a no-arg constructor and public {@code setX(value)} setters
   *   <li>{@link #FIELDS} — requires a no-arg constructor; injects values into declared fields
   *       reflectively (needs {@code opens} under JPMS for private fields)
   *   <li>{@link #CONSTRUCTOR} — uses the all-args constructor; matches arguments by parameter name
   *       when compiled with {@code -parameters}, otherwise positional
   * </ul>
   */
  enum WriteStrategy {
    BUILDER,
    SETTERS,
    FIELDS,
    CONSTRUCTOR,
  }

  /**
   * Declare that {@code target} should be constructed via {@code strategy} during deep mapping,
   * overriding the auto-detected choice.
   */
  static <B> WriteHint<B> writeBean(final Class<B> target, final WriteStrategy strategy) {
    return new BeanWriteHint<>(target, strategy);
  }

  /**
   * Declare a <em>default</em> write strategy applied to every bean target encountered during deep
   * mapping that does not have a more specific {@link #writeBean(Class, WriteStrategy)} hint. One
   * row replaces N per-class enumerations when every target shares the same strategy (e.g., pinning
   * SETTERS across an entire JPA-entity tree so Hibernate's identity assignment fires on every
   * level).
   *
   * <pre>{@code
   * Telescope.mapper(
   *     Order.class, OrderEntity.class,
   *     writeBeans(SETTERS),                       // default for OrderEntity, CustomerEntity,
   *                                                // LineItemEntity, AddressEmbeddable, …
   *     writeBean(CashRegisterEntity.class, FIELDS) // override on one specific target
   * );
   * }</pre>
   *
   * <p>At most one {@code writeBeans(...)} default may appear per {@code Telescope.map(...)} call.
   * Per-class {@code writeBean(X.class, …)} rows always win for class {@code X}. The default is
   * resolved lazily — the writer for a given target is constructed the first time the recursion
   * reaches that target, so a default strategy incompatible with a particular target (e.g.,
   * {@code BUILDER} on a class with no static {@code builder()}) only throws when that target is
   * actually visited.
   */
  static WriteHint<?> writeBeans(final WriteStrategy strategy) {
    return new DefaultWriteHint(strategy);
  }

  /**
   * The target class this hint applies to — used to key the per-class strategy lookup. {@code null}
   * for a {@link #writeBeans(WriteStrategy)} default hint, which applies to every encountered bean
   * target instead of one specific class.
   */
  Class<B> targetClass();

  /** The chosen write strategy for {@link #targetClass()}. */
  WriteStrategy strategy();

  /** Package-private record impl; users construct via {@link #writeBean}. */
  record BeanWriteHint<B>(Class<B> targetClass, WriteStrategy strategy) implements WriteHint<B> {}

  /**
   * Package-private record impl for {@link #writeBeans(WriteStrategy)}. Carries no target class
   * (the strategy applies to every encountered bean target lacking a per-class override); {@link
   * #targetClass()} returns {@code null} as the sentinel.
   */
  record DefaultWriteHint(WriteStrategy strategy) implements WriteHint<Object> {
    @Override
    public Class<Object> targetClass() {
      return null;
    }
  }
}
