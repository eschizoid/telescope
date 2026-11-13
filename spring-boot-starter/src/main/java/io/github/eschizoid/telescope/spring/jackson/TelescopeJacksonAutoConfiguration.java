package io.github.eschizoid.telescope.spring.jackson;

import io.github.eschizoid.telescope.jackson.TelescopeModule;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Installs a {@link TelescopeModule} in the application's Jackson 3 {@code JsonMapper}, when
 * telescope-jackson and Jackson 3 databind are on the classpath. Spring Boot adds every {@code
 * JacksonModule} bean to the mappers it builds, so the module is a bean and nothing more.
 *
 * <p>The module carries the mappers {@link TelescopeModuleCustomizer} beans add and no others. An
 * application that declares a {@code TelescopeModule} bean of its own gets that one instead.
 *
 * <p>The class conditions name their classes as text, and the module type appears only in bean
 * method signatures, so the configuration loads, and steps aside, on a classpath without either.
 */
@AutoConfiguration(beforeName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration")
@ConditionalOnClass(
  name = { "io.github.eschizoid.telescope.jackson.TelescopeModule", "tools.jackson.databind.json.JsonMapper" }
)
public class TelescopeJacksonAutoConfiguration {

  /** Creates the configuration; Spring instantiates it. */
  public TelescopeJacksonAutoConfiguration() {}

  // The bean method is public: on the module path Spring calls it from its own modules, which reach
  // public members of this exported package without an opens.

  /**
   * The module, carrying the mappers the customizers add.
   *
   * @param customizers the customizer beans, in {@code @Order} order
   * @return the module
   */
  @Bean
  @ConditionalOnMissingBean(TelescopeModule.class)
  public TelescopeModule telescopeModule(final ObjectProvider<TelescopeModuleCustomizer> customizers) {
    final var builder = TelescopeModule.builder();
    customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
    return builder.build();
  }
}
