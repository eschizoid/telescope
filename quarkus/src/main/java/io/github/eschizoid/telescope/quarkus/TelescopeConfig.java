package io.github.eschizoid.telescope.quarkus;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration mapping for the telescope Quarkus extension. Read by {@link TelescopeProducer} when
 * constructing the {@link TelescopeMapperRegistry}.
 *
 * <p>Configuration keys live under the {@code telescope.*} namespace in {@code
 * application.properties} / {@code application.yaml}:
 *
 * <pre>{@code
 * telescope.registry.fail-fast=true
 * }</pre>
 *
 * <h2>Properties</h2>
 *
 * <ul>
 *   <li>{@code telescope.registry.fail-fast} — when {@code true} (default), {@link
 *       TelescopeMapperRegistry#get(Class, Class) registry.get(srcCls, tgtCls)} throws on missing
 *       type pair; when {@code false}, returns {@code null}. Either way, {@link
 *       TelescopeMapperRegistry#find(Class, Class) registry.find(...)} returns {@link
 *       java.util.Optional}.
 * </ul>
 */
@ConfigMapping(prefix = "telescope")
public interface TelescopeConfig {
  /** Registry-scoped configuration. */
  Registry registry();

  /** Settings for the {@link TelescopeMapperRegistry} bean. */
  interface Registry {
    /**
     * When {@code true} (default), {@link TelescopeMapperRegistry#get(Class, Class)} throws on a
     * missing type pair; when {@code false}, returns {@code null}.
     */
    @WithDefault("true")
    boolean failFast();
  }
}
