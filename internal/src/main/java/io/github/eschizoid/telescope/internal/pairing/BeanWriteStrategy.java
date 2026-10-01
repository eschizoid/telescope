package io.github.eschizoid.telescope.internal.pairing;

import java.util.List;
import java.util.Optional;

/**
 * A way to build a bean, and the order and rules an unhinted write follows to pick one.
 *
 * <p>The runtime writer and every processor that rebuilds a bean answer the same question when no
 * hint names a strategy, and they answer it here: each world describes the bean as a {@link Shape},
 * and {@link #auto} picks the first strategy in {@link #AUTO_ORDER} the bean offers. A bean
 * offering several ways to be built is then built the same way on both paths, so a builder or
 * constructor that trims, validates or derives a value does so whichever path converts it.
 *
 * <p>The facts are the world's to answer, because each sees the bean differently: the runtime
 * matches constructor arguments by the parameter names javac keeps only under {@code -parameters},
 * and a processor reads them from the compilation unit. The decision made from those facts is this
 * class's alone.
 */
public enum BeanWriteStrategy {
  /** A static {@code builder()} whose builder has a {@code build()}. */
  BUILDER,
  /** A public constructor taking every property, matched by parameter name. */
  CONSTRUCTOR,
  /** A no-argument constructor followed by one public setter call per property. */
  SETTERS;

  /**
   * The order an unhinted write tries the strategies in. A builder and a constructor build the
   * value in one call, through whatever checks the class puts there; setters leave a window in
   * which the object exists half-written, so they come last.
   */
  public static final List<BeanWriteStrategy> AUTO_ORDER = List.of(BUILDER, CONSTRUCTOR, SETTERS);

  /** What a bean's builder has for one property. */
  public enum Member {
    /** No member answers to the property's name. */
    ABSENT,
    /** A member answers to the name and takes a value of the property's type. */
    ACCEPTS,
    /** A member answers to the name and cannot take a value of the property's type. */
    REJECTS,
  }

  /** The facts about one bean that the choice is made from, as one world sees them. */
  public interface Shape {
    /** The bean's property names. */
    List<String> properties();

    /** Whether the bean has a static {@code builder()} whose builder has a {@code build()}. */
    boolean hasBuilder();

    /** What the builder has for {@code property}. Asked only when {@link #hasBuilder} is true. */
    Member builderMember(String property);

    /** Whether the bean has a constructor taking every property, matched by parameter name. */
    boolean hasConstructor();

    /** Whether the bean can be built through a no-argument constructor and its setters. */
    boolean hasSetters();

    /** Whether a public setter writes {@code property}. */
    boolean setterWrites(String property);

    /**
     * Whether the bean or a superclass declares an instance field named {@code property} that only
     * a write strategy can give a value. A getter with no such field computes its value, and a
     * final field given its value where it is declared keeps that value, so no strategy writes
     * either and none can lose it. A world that cannot see initializers counts no final field.
     */
    boolean stored(String property);
  }

  /** The first strategy in {@link #AUTO_ORDER} the bean offers, or empty when it offers none. */
  public static Optional<BeanWriteStrategy> auto(final Shape shape) {
    for (final var strategy : AUTO_ORDER) {
      if (offers(shape, strategy)) return Optional.of(strategy);
    }
    return Optional.empty();
  }

  private static boolean offers(final Shape shape, final BeanWriteStrategy strategy) {
    return switch (strategy) {
      case BUILDER -> shape.hasBuilder() && builderCarriesWhatTheNextStrategyWrites(shape);
      case CONSTRUCTOR -> shape.hasConstructor();
      case SETTERS -> shape.hasSetters();
    };
  }

  /**
   * Whether the builder can stand in for the strategy that would build the bean without it.
   *
   * <p>A builder skips a property it has no member for, silently, so a builder lacking a member for
   * a property the next available strategy writes would lose a value that strategy keeps. When no
   * other strategy is available, every stored property is one the builder alone would have to
   * write, so a missing member there loses the value outright; a getter with no field behind it
   * computes its value and asks nothing. A member that answers to a property's name but cannot take
   * its value is worse: every write through the builder fails. Each of these passes the builder
   * over. A property the next strategy does not write either asks nothing of the builder, which is
   * what lets a bean whose setters cover only some of its getters keep its builder.
   *
   * <p>The constructor writes every property, since it takes one parameter per property; the
   * setters write the properties a public setter exists for.
   */
  private static boolean builderCarriesWhatTheNextStrategyWrites(final Shape shape) {
    for (final var property : shape.properties()) {
      final var member = shape.builderMember(property);
      if (member == Member.REJECTS) return false;
      if (member == Member.ABSENT && nextStrategyWrites(shape, property)) return false;
    }
    return true;
  }

  private static boolean nextStrategyWrites(final Shape shape, final String property) {
    if (shape.hasConstructor()) return true;
    if (shape.hasSetters()) return shape.setterWrites(property);
    return shape.stored(property);
  }
}
