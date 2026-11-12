package io.github.eschizoid.telescope.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.quarkus.blueprint.User;
import io.github.eschizoid.telescope.quarkus.blueprint.UserCityTransformer;
import io.github.eschizoid.telescope.quarkus.blueprint.UserCityTransformerImpl;
import io.github.eschizoid.telescope.quarkus.blueprint.UserDto;
import io.github.eschizoid.telescope.quarkus.blueprint.UserTranslatedProjection;
import io.github.eschizoid.telescope.quarkus.blueprint.UserTranslatedProjectionImpl;
import io.quarkus.test.component.QuarkusComponentTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An {@code @ApplicationScoped} bean is reached through a client proxy, which ArC builds through
 * the bean class's no-argument constructor. The counts here start before the container does, so
 * they also see work done for the proxy. The class holds one test, so one container runs.
 */
@QuarkusComponentTest({ UserCityTransformerImpl.class, UserTranslatedProjectionImpl.class })
class GeneratedBeansBuildOnceComponentTest {

  private static int pathsBefore;
  private static int transformsBefore;
  private static int translationsBefore;

  @Inject
  UserCityTransformer city;

  @Inject
  UserTranslatedProjection translated;

  @BeforeAll
  static void countBeforeTheContainerStarts() {
    pathsBefore = UserCityTransformer.BUILDS.get();
    transformsBefore = UserCityTransformer.TRANSFORM_BUILDS.get();
    translationsBefore = UserTranslatedProjection.TRANSLATIONS.get();
  }

  @Test
  @DisplayName("a generated bean builds its path, transformation and mapping once, and never for its proxy")
  void beansBuildTheirStateOnce() {
    final var user = new User("Alice", new User.Address("Boston"));
    city.read(user);
    city.set(user, "Paris");
    city.apply(user);
    city.apply(user);
    translated.map(user);
    translated.backward(new UserDto("Bob", null));
    translated.map(user);
    assertThat(UserCityTransformer.BUILDS.get() - pathsBefore).isEqualTo(1);
    assertThat(UserCityTransformer.TRANSFORM_BUILDS.get() - transformsBefore).isEqualTo(1);
    assertThat(UserTranslatedProjection.TRANSLATIONS.get() - translationsBefore).isEqualTo(1);
  }
}
