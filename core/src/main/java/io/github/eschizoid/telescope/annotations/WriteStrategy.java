package io.github.eschizoid.telescope.annotations;

/**
 * Override for the POJO construction strategy that {@link Bridge} codegen chooses when emitting
 * forward/backward bodies. Mirrors the runtime {@code WriteHint.writeBean(cls, strategy)} hint.
 *
 * <p>Records always use their canonical constructor and ignore this setting. For POJOs, the default
 * {@link #AUTO} runs the priority ladder: static {@code builder()} method → name-matched
 * constructor → no-arg constructor plus setters, the order the runtime takes with no write hint.
 * The other values force one specific strategy and surface a precise compile error if the POJO
 * doesn't expose the required shape.
 */
public enum WriteStrategy {
  /**
   * Default: auto-detect by trying a static {@code builder()}, then a name-matched constructor,
   * then a no-arg constructor plus setters, and pick the first that applies. The builder is skipped
   * when it has no member named for a property the next strategy would write (every property when
   * there is a name-matched constructor, otherwise every property a public setter writes), or when
   * a member named for any property cannot take a value of that property's type. The runtime writer
   * takes the same order and the same rule, so a POJO offering several of these is built the same
   * way by the bridge and by {@code Telescope.mapper}.
   */
  AUTO,

  /**
   * Force the name-matched constructor strategy. The POJO must expose a public constructor whose
   * parameter names match the bridge fields. Useful for POJOs whose builder is incidental and
   * shouldn't be exercised, since {@link #AUTO} prefers the builder.
   */
  CONSTRUCTOR,

  /**
   * Force the static {@code builder()} strategy. The POJO must expose {@code public static Builder
   * builder()} returning a class with one setter per bridge field plus a {@code build()} method.
   * Useful when {@link #AUTO} would pass the builder over because it lacks a member a setter
   * writes, or to make the choice explicit.
   */
  BUILDER,

  /**
   * Force the no-arg constructor plus setters strategy. The POJO must expose a public no-arg
   * constructor and a public {@code setX(...)} per bridge field. The canonical JavaBeans /
   * Hibernate entity shape, and the way to keep setter writes on a POJO that also offers a builder
   * or a name-matched constructor, which {@link #AUTO} would prefer.
   */
  SETTERS,
}
