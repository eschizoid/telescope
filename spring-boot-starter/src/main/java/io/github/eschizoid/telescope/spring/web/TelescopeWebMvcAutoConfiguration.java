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

  // The bean methods and the nested configuration are public: on the module path Spring calls them
  // from its own modules, which reach public members of this exported package without an opens.

  /**
   * Resolves every mapped handler's mapping once the context's singletons exist.
   *
   * @param beanFactory the factory the handler mappings and mappers are looked up in
   * @param registry the {@code Mapper} bean registry, consulted after projection beans
   * @return the resolved mappings, keyed by handler method
   */
  @Bean
  public static TelescopeResponses telescopeResponses(
    final ConfigurableListableBeanFactory beanFactory,
    final ObjectProvider<TelescopeMapperRegistry> registry
  ) {
    return new TelescopeResponses(beanFactory, registry);
  }

  /**
   * Resolves every mapped request-body parameter once the context's singletons exist.
   *
   * @param beanFactory the factory the handler mappings and mappers are looked up in
   * @param registry the {@code Mapper} bean registry, consulted after projection beans
   * @return the resolved mappings, keyed by handler parameter
   */
  @Bean
  public static TelescopeRequests telescopeRequests(
    final ConfigurableListableBeanFactory beanFactory,
    final ObjectProvider<TelescopeMapperRegistry> registry
  ) {
    return new TelescopeRequests(beanFactory, registry);
  }

  /**
   * Wraps the body-writing return-value handlers. Static, so the post-processor is created before
   * this configuration class itself.
   *
   * @param responses the resolved response mappings, fetched when the handler adapter is
   *     initialised
   * @param requests the resolved request-body mappings, fetched at the same point
   * @return the post-processor
   */
  @Bean
  public static TelescopeResponseHandlers telescopeResponseHandlers(
    final ObjectProvider<TelescopeResponses> responses,
    final ObjectProvider<TelescopeRequests> requests
  ) {
    return new TelescopeResponseHandlers(responses, requests);
  }

  /** Documents mapped responses when springdoc is on the classpath. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(OperationCustomizer.class)
  public static class OpenApi {

    /** Creates the configuration; Spring instantiates it. */
    public OpenApi() {}

    /**
     * The springdoc customizer. Its schema model follows springdoc's configured OpenAPI version, so
     * the schemas it registers match the ones springdoc writes.
     *
     * @param responses the resolved response mappings
     * @param requests the resolved request-body mappings
     * @param properties springdoc's configuration, when present
     * @return the customizer
     */
    @Bean
    public TelescopeOpenApiCustomizer telescopeOpenApiCustomizer(
      final TelescopeResponses responses,
      final TelescopeRequests requests,
      final ObjectProvider<SpringDocConfigProperties> properties
    ) {
      final var springdoc = properties.getIfAvailable();
      final var openapi31 = springdoc != null && springdoc.getApiDocs().getVersion() == OpenApiVersion.OPENAPI_3_1;
      return new TelescopeOpenApiCustomizer(responses, requests, openapi31);
    }
  }
}
