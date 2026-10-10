package io.github.eschizoid.telescope.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.quarkus.arc.DefaultBean;
import io.quarkus.test.component.QuarkusComponentTest;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link TelescopeProducer} through a real ArC container. The registry is built from
 * {@code @All List<Mapper<?, ?>>}, which resolves the way {@code @Any Instance<Mapper<?, ?>>} does:
 * a {@code @DefaultBean} mapper is dropped once any non-default {@code Mapper} bean exists,
 * whatever pair either one maps.
 */
@QuarkusComponentTest(TelescopeProducer.class)
class TelescopeProducerComponentTest {

  record Source(String name) {}

  record Target(String name) {}

  record AltSource(int value) {}

  record AltTarget(int value) {}

  record LoneSource(String id) {}

  record LoneTarget(String id) {}

  @Singleton
  static class Mappers {

    static final Mapper<Source, Target> PRIMARY = Telescope.mapper(Source.class, Target.class);
    static final Mapper<Source, Target> SECOND = Telescope.mapper(Source.class, Target.class);
    static final Mapper<AltSource, AltTarget> UNRELATED = Telescope.mapper(AltSource.class, AltTarget.class);
    static final Mapper<LoneSource, LoneTarget> LONE_DEFAULT = Telescope.mapper(LoneSource.class, LoneTarget.class);

    @Produces
    Mapper<Source, Target> primary() {
      return PRIMARY;
    }

    @Produces
    @DefaultBean
    @Named("second")
    Mapper<Source, Target> second() {
      return SECOND;
    }

    @Produces
    Mapper<AltSource, AltTarget> unrelated() {
      return UNRELATED;
    }

    @Produces
    @DefaultBean
    Mapper<LoneSource, LoneTarget> loneDefault() {
      return LONE_DEFAULT;
    }
  }

  @Inject
  TelescopeMapperRegistry registry;

  @Inject
  @Named("second")
  Mapper<Source, Target> second;

  @Test
  @DisplayName("a @DefaultBean mapper for a registered pair stays out of the registry and injects by its qualifier")
  void defaultBeanKeepsASecondMapperOutOfTheRegistry() {
    assertThat(registry.get(Source.class, Target.class)).isSameAs(Mappers.PRIMARY);
    assertThat(registry.get(AltSource.class, AltTarget.class)).isSameAs(Mappers.UNRELATED);
    assertThat(second).isSameAs(Mappers.SECOND);
  }

  @Test
  @DisplayName("a lone @DefaultBean mapper is dropped from the registry once any other Mapper bean exists")
  void loneDefaultBeanIsDroppedWhenAnotherMapperExists() {
    assertThat(registry.contains(LoneSource.class, LoneTarget.class)).isFalse();
    assertThat(registry.size()).isEqualTo(2);
  }
}
