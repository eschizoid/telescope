package io.github.eschizoid.telescope.annotations;

/**
 * Override for the POJO construction strategy that {@link Bridge} codegen chooses when emitting
 * forward/backward bodies. Mirrors the runtime {@code WriteHint.writeBean(cls, strategy)} hint.
 *
 * <p>Records always use their canonical constructor and ignore this setting. For POJOs, the
 * default {@link #AUTO} runs the priority ladder: name-matched constructor → static {@code
 * builder()} method → no-arg constructor plus setters. The other values force one specific
 * strategy and surface a precise compile error if the POJO doesn't expose the required shape.
 */
public enum WriteStrategy {
  /**
   * Default: auto-detect by trying name-matched constructor, then static {@code builder()}, then
   * no-arg constructor plus setters. Pick the first that applies cleanly.
   */
  AUTO,

  /**
   * Force the name-matched constructor strategy. The POJO must expose a public constructor whose
   * parameter names match the bridge fields. Useful for immutable POJOs whose builder is
   * incidental and shouldn't be exercised.
   */
  CONSTRUCTOR,

  /**
   * Force the static {@code builder()} strategy. The POJO must expose {@code public static
   * Builder builder()} returning a class with one setter per bridge field plus a {@code build()}
   * method. Useful when AutoValue / Immutables / Lombok @Builder targets are present but the
   * processor would prefer name-matched ctor.
   */
  BUILDER,

  /**
   * Force the no-arg constructor plus setters strategy. The POJO must expose a public no-arg
   * constructor and a public {@code setX(...)} per bridge field. The canonical JavaBeans /
   * Hibernate entity shape.
   */
  SETTERS,
}
