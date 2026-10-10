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
 * <p>The {@code @All List<Mapper<?, ?>>} parameter is Quarkus ArC's collector pattern. It resolves
 * the way {@code @Any Instance<Mapper<?, ?>>} does: qualifiers do not filter it, and ArC
 * disambiguates the whole set of {@link Mapper} beans, dropping a {@code @DefaultBean} mapper once
 * any non-default one exists and keeping only the highest-priority alternatives once an
 * {@code @Alternative} is present. No extra wiring needed — declare a producer method or producer
 * field of type {@code Mapper<A, B>} and it shows up in the registry. {@code Mapper} is final with
 * no public constructor, so a producer is the only way to declare one.
 *
 * <p>This producer is not a default bean, so a second plain producer of {@code
 * TelescopeMapperRegistry} makes the injection ambiguous. A replacement registry needs a producer
 * annotated with {@code @Alternative} and {@code @Priority}, which CDI then chooses over this one.
 */
@ApplicationScoped
public class TelescopeProducer {

  /** Default constructor invoked by Quarkus' CDI container. */
  public TelescopeProducer() {}

  /**
   * Build the {@link TelescopeMapperRegistry} from the {@link Mapper} beans ArC resolves. The
   * {@code @All} annotation is Quarkus-specific and collects the disambiguated CDI beans of the
   * parameter type into a {@link List} — without it, CDI would only inject one (and fail at startup
   * if multiple candidates exist).
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
