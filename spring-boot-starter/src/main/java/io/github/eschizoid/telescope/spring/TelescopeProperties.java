package io.github.eschizoid.telescope.spring;

import io.github.eschizoid.telescope.WriteHint;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the telescope Spring Boot starter. Bound from the {@code
 * telescope.*} namespace in {@code application.yml} / {@code application.properties}.
 *
 * <pre>{@code
 * telescope:
 *   default-write-strategy: SETTERS
 *   registry:
 *     fail-fast: true
 * }</pre>
 *
 * <p>None of these properties are required — defaults match the behaviour you'd get from importing
 * telescope directly. Setting {@code telescope.default-write-strategy} only affects mappers built
 * via the (future) declarative {@code @TelescopeMapper} shortcut; mappers built explicitly via
 * {@link io.github.eschizoid.telescope.Telescope#mapper Telescope.mapper(...)} continue to use
 * whatever {@code writeBeans(...)} / {@code writeBean(...)} rows the caller declares.
 */
@ConfigurationProperties(prefix = "telescope")
public class TelescopeProperties {

  /** Default constructor invoked by Spring's {@code @ConfigurationProperties} binder. */
  public TelescopeProperties() {}

  /**
   * Default {@link WriteHint.WriteStrategy} applied to mappers built through the starter's
   * declarative shortcuts when the user doesn't pin one explicitly. {@code null} means "use {@code
   * Beans.autoWriter}'s per-class auto-detect ladder". Common choices: {@code SETTERS} for
   * Hibernate-friendly entity targets, {@code BUILDER} for Lombok {@code @Builder}-heavy domains.
   */
  private WriteHint.WriteStrategy defaultWriteStrategy;

  /** Registry-specific configuration. See {@link Registry}. */
  private final Registry registry = new Registry();

  public WriteHint.WriteStrategy getDefaultWriteStrategy() {
    return defaultWriteStrategy;
  }

  public void setDefaultWriteStrategy(final WriteHint.WriteStrategy defaultWriteStrategy) {
    this.defaultWriteStrategy = defaultWriteStrategy;
  }

  public Registry getRegistry() {
    return registry;
  }

  /** Configuration knobs for {@link TelescopeMapperRegistry}. */
  public static class Registry {

    /** Default constructor invoked by Spring's {@code @ConfigurationProperties} binder. */
    public Registry() {}

    /**
     * When true (default), the registry throws on {@code get(srcCls, tgtCls)} for a type pair with
     * no registered {@code Mapper} bean. When false, returns {@code null} silently. Keep this
     * {@code true} unless you're intentionally probing the registry for optional mappers.
     */
    private boolean failFast = true;

    public boolean isFailFast() {
      return failFast;
    }

    public void setFailFast(final boolean failFast) {
      this.failFast = failFast;
    }
  }
}
