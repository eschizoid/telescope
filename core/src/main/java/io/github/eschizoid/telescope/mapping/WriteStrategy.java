package io.github.eschizoid.telescope.mapping;

/**
 * How a mapper builds a bean, named by a {@link Mapping#writeBean writeBean(...)} or {@link
 * Mapping#writeBeans writeBeans(...)} row. {@link Mapping} declares each constant too, so the
 * single {@code import static io.github.eschizoid.telescope.mapping.Mapping.*} that brings the rows
 * brings these:
 *
 * <pre>{@code
 * Telescope.mapper(OrderRecord.class, OrderPojo.class, writeBean(OrderPojo.class, CONSTRUCTOR));
 * }</pre>
 *
 * <p>How a collection is written is not a strategy: a collection goes through the owner's adder
 * wherever the owner is written member by member, and the back-reference of each element the mapper
 * converts is set to the owner where no adder sets it, whichever strategy builds the owner.
 */
public enum WriteStrategy {
  /** A static {@code builder()} method on the target, and its {@code build()}. */
  BUILDER,
  /**
   * The all-args constructor. Arguments are matched by parameter name when the names are known,
   * from {@code -parameters} or from {@code -g}, and every one names a property; by position when
   * the names are unknown or none names a property. A constructor only some of whose known names
   * are properties is refused when the mapper is built. Inside a native image the names {@code -g}
   * keeps cannot be read, so compile with {@code -parameters} to get this check there.
   */
  CONSTRUCTOR,
  /** A no-arg constructor and a public {@code setX(value)} setter per property. */
  SETTERS,
}
