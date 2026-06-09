package io.github.eschizoid.telescope.spring;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.Collection;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot autoconfiguration for telescope. Activates when {@link Telescope} is on the classpath
 * — which it will be, since the {@code telescope-spring-boot-starter} module pulls the {@code
 * telescope} library in transitively.
 *
 * <p>Provides one bean by default:
 *
 * <ul>
 *   <li>{@link TelescopeMapperRegistry} — a typed registry indexing every {@link Mapper} bean in
 *       the context by {@code (sourceClass, targetClass)}. Useful for polymorphic conversion in
 *       generic services.
 * </ul>
 *
 * <p>The registry is built from {@link Collection}{@code <}{@link Mapper}{@code <?, ?>>}, which
 * Spring auto-injects with every {@link Mapper} bean defined in any {@code @Configuration} the user
 * wrote. No extra wiring needed — declare {@code @Bean Mapper<A, B>} and it shows up in the
 * registry.
 *
 * <p>Users who want to suppress the registry (e.g., to provide their own implementation) can
 * declare their own {@code @Bean TelescopeMapperRegistry} — {@link ConditionalOnMissingBean} backs
 * off automatically.
 */
@AutoConfiguration
@ConditionalOnClass(Telescope.class)
@EnableConfigurationProperties(TelescopeProperties.class)
public class TelescopeAutoConfiguration {

  /** Default constructor invoked by Spring's bean factory. */
  public TelescopeAutoConfiguration() {}

  /**
   * Build the {@link TelescopeMapperRegistry} from every {@link Mapper} bean the context can
   * resolve. Spring injects the {@code Collection} parameter with the live list of {@code Mapper}
   * beans regardless of how many or how few are registered — an empty graph yields an empty
   * registry (still usable; {@code get()} would throw or return {@code null} per {@code
   * fail-fast}).
   *
   * <p>The {@code Map.copyOf} defensive snapshot inside the registry constructor isolates it from
   * any later modification of the collection Spring hands over.
   */
  @Bean
  @ConditionalOnMissingBean
  @SuppressWarnings("rawtypes")
  public TelescopeMapperRegistry telescopeMapperRegistry(
    final Collection<Mapper> mappers,
    final TelescopeProperties properties
  ) {
    return new TelescopeMapperRegistry(eraseToWildcardCollection(mappers), properties.getRegistry().isFailFast());
  }

  /**
   * Spring resolves {@code Collection<Mapper>} (raw) for us so it grabs every generic variant; the
   * registry's API uses {@code Collection<Mapper<?, ?>>}. The cast erases the raw collection to the
   * wildcard form — runtime-equivalent, just satisfies the generic contract at the registry
   * boundary. We use the raw {@code Mapper} type in the bean method signature because Spring's
   * autowiring can't otherwise match {@code Collection<Mapper<?, ?>>} against specific parametric
   * beans at injection time.
   */
  @SuppressWarnings({ "rawtypes", "unchecked" })
  private static Collection<Mapper<?, ?>> eraseToWildcardCollection(final Collection<Mapper> mappers) {
    return (Collection<Mapper<?, ?>>) (Collection<?>) mappers;
  }
}
