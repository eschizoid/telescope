package io.github.eschizoid.telescope.quarkus;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eschizoid.telescope.inject.TelescopeCustomizer;
import io.github.eschizoid.telescope.quarkus.blueprint.NullPathTransformer;
import io.github.eschizoid.telescope.quarkus.blueprint.NullPathTransformerImpl;
import io.github.eschizoid.telescope.quarkus.blueprint.User;
import io.github.eschizoid.telescope.quarkus.blueprint.UserProjection;
import io.github.eschizoid.telescope.quarkus.blueprint.UserProjectionImpl;
import io.quarkus.test.component.QuarkusComponentTest;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A generated bean that cannot be built fails where the container creates it. An
 * {@code @ApplicationScoped} bean is created on its first call, so that call throws.
 */
@QuarkusComponentTest(
  { UserProjectionImpl.class, NullPathTransformerImpl.class, GeneratedBeansFailureComponentTest.Customizers.class }
)
class GeneratedBeansFailureComponentTest {

  @Singleton
  static class Customizers {

    @Produces
    TelescopeCustomizer<UserProjection> mapsTooEarly() {
      return projection -> projection.map(new User("Alice", null));
    }
  }

  @Inject
  UserProjection projection;

  @Inject
  NullPathTransformer nullPath;

  @Test
  @DisplayName("a customizer that maps while the projection is being constructed fails with an explanation")
  void mappingFromACustomizerFails() {
    assertThatThrownBy(() -> projection.map(new User("Alice", null)))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("UserProjection cannot map while it is being constructed");
  }

  @Test
  @DisplayName("a transformer whose path() returns null fails when the bean is created")
  void nullPathFailsAtBeanCreation() {
    assertThatThrownBy(() -> nullPath.read(new User("Alice", null)))
      .isInstanceOf(NullPointerException.class)
      .hasMessageContaining("path() must not return null");
  }
}
