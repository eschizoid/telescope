package io.github.eschizoid.telescope.spring.jackson;

import io.github.eschizoid.telescope.jackson.TelescopeModule;

/**
 * Names the mappers the starter's {@link TelescopeModule} carries. The module carries none on its
 * own: a {@code Mapper} bean is not a JSON converter until a customizer adds it, because a mapper
 * between an entity and a DTO would otherwise change how every such entity is written. Customizer
 * beans run in {@code @Order} order.
 *
 * <pre>{@code
 * @Bean
 * TelescopeModuleCustomizer wireShapes() {
 *   return module -> module.add(ContactMappers.WIRE);
 * }
 * }</pre>
 */
@FunctionalInterface
public interface TelescopeModuleCustomizer {
  /**
   * Adds mappers to the module being built.
   *
   * @param module the module's builder
   */
  void customize(TelescopeModule.Builder module);
}
