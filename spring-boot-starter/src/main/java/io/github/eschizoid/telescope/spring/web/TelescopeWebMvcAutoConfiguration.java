package io.github.eschizoid.telescope.spring.web;

import io.github.eschizoid.telescope.spring.TelescopeAutoConfiguration;
import io.github.eschizoid.telescope.spring.TelescopeMapperRegistry;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SpringDocConfigProperties.ApiDocs.OpenApiVersion;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/**
 * Maps the response body of every handler method annotated with {@link TelescopeGetMapping} or a
 * sibling. Active in a servlet web application with Spring MVC on the classpath.
 */
@AutoConfiguration(after = TelescopeAutoConfiguration.class)
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnClass(RequestMappingHandlerAdapter.class)
public class TelescopeWebMvcAutoConfiguration {

  /** Creates the configuration; Spring instantiates it. */
  public TelescopeWebMvcAutoConfiguration() {}

  @Bean
  static TelescopeResponses telescopeResponses(
    final ConfigurableListableBeanFactory beanFactory,
    final ObjectProvider<TelescopeMapperRegistry> registry
  ) {
    return new TelescopeResponses(beanFactory, registry);
  }

  // Static, so the post-processor is created before this configuration class itself.
  @Bean
  static TelescopeResponseHandlers telescopeResponseHandlers(final ObjectProvider<TelescopeResponses> responses) {
    return new TelescopeResponseHandlers(responses);
  }

  // The schema model follows springdoc's configured OpenAPI version, so the registered schemas
  // match the ones
  // springdoc writes.
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(OperationCustomizer.class)
  static class OpenApi {

    @Bean
    TelescopeOpenApiCustomizer telescopeOpenApiCustomizer(
      final TelescopeResponses responses,
      final ObjectProvider<SpringDocConfigProperties> properties
    ) {
      final var springdoc = properties.getIfAvailable();
      final var openapi31 = springdoc != null && springdoc.getApiDocs().getVersion() == OpenApiVersion.OPENAPI_3_1;
      return new TelescopeOpenApiCustomizer(responses, openapi31);
    }
  }
}
