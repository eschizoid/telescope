package io.github.eschizoid.telescope.quarkus;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.quarkus.arc.All;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.util.List;

/**
 * CDI producer for {@link TelescopeMapperRegistry}. Activates whenever the {@code telescope-
 * quarkus} extension is on the classpath — analogue of the Spring Boot starter's auto-config.
 *
 * <p>The {@code @All List<Mapper<?, ?>>} parameter is Quarkus ArC's collector pattern: CDI hands
 * over every {@link Mapper} bean defined in any {@code @ApplicationScoped} (or other normal-scope)
 * class in the user's application. No extra wiring needed — declare a producer that returns {@code
 * Mapper<A, B>} (or annotate an {@code @ApplicationScoped} class implementing the right type) and
 * it shows up in the registry.
 *
 * <p>Users who want to suppress the registry (e.g., to provide their own implementation) can
 * declare their own {@code @Produces TelescopeMapperRegistry} bean. CDI's specialization /
 * alternatives picks theirs over ours.
 */
@ApplicationScoped
public class TelescopeProducer {

  /** Default constructor invoked by Quarkus' CDI container. */
  public TelescopeProducer() {}

  /**
   * Build the {@link TelescopeMapperRegistry} from every {@link Mapper} bean ArC can resolve. The
   * {@code @All} annotation is Quarkus-specific and collects every CDI bean of the parameter type
   * into a {@link List} — without it, CDI would only inject one (and fail at startup if multiple
   * candidates exist).
   */
  @Produces
  @ApplicationScoped
  public TelescopeMapperRegistry telescopeMapperRegistry(
    @All final List<Mapper<?, ?>> mappers,
    final TelescopeConfig config
  ) {
    return new TelescopeMapperRegistry(mappers, config.registry().failFast());
  }
}
