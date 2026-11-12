package io.github.eschizoid.telescope.quarkus;

import static io.github.eschizoid.telescope.mapping.Mapping.toOrElse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.inject.TelescopeCustomizer;
import io.github.eschizoid.telescope.inject.TelescopePath;
import io.github.eschizoid.telescope.quarkus.blueprint.LowerCityUserMapper;
import io.github.eschizoid.telescope.quarkus.blueprint.LowerCityUserMapperImpl;
import io.github.eschizoid.telescope.quarkus.blueprint.User;
import io.github.eschizoid.telescope.quarkus.blueprint.UserCityPrefixTransformer;
import io.github.eschizoid.telescope.quarkus.blueprint.UserCityPrefixTransformerImpl;
import io.github.eschizoid.telescope.quarkus.blueprint.UserCityTransformer;
import io.github.eschizoid.telescope.quarkus.blueprint.UserCityTransformerImpl;
import io.github.eschizoid.telescope.quarkus.blueprint.UserConfiguredProjection;
import io.github.eschizoid.telescope.quarkus.blueprint.UserConfiguredProjectionImpl;
import io.github.eschizoid.telescope.quarkus.blueprint.UserDto;
import io.github.eschizoid.telescope.quarkus.blueprint.UserMapper;
import io.github.eschizoid.telescope.quarkus.blueprint.UserMapperImpl;
import io.github.eschizoid.telescope.quarkus.blueprint.UserProjection;
import io.github.eschizoid.telescope.quarkus.blueprint.UserProjectionImpl;
import io.github.eschizoid.telescope.quarkus.blueprint.UserTranslatedProjection;
import io.github.eschizoid.telescope.quarkus.blueprint.UserTranslatedProjectionImpl;
import io.quarkus.test.component.QuarkusComponentTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs the CDI beans the processor generates for {@code @TelescopeMapper} and
 * {@code @TelescopeTransformer} in an ArC container, against the behaviour the Spring components
 * have. Every generated class is listed, since an interface injection point with no bean is
 * otherwise satisfied by a mock.
 */
@QuarkusComponentTest(
  {
    TelescopeProducer.class,
    UserProjectionImpl.class,
    UserTranslatedProjectionImpl.class,
    UserConfiguredProjectionImpl.class,
    UserMapperImpl.class,
    LowerCityUserMapperImpl.class,
    UserCityTransformerImpl.class,
    UserCityPrefixTransformerImpl.class,
    GeneratedBeansComponentTest.Customizers.class,
  }
)
class GeneratedBeansComponentTest {

  /**
   * Customizers for {@link UserConfiguredProjection}, declared lowest priority first: ArC sorts an
   * {@code @All} list highest priority first, so priority, not declaration order, decides.
   */
  @Singleton
  static class Customizers {

    @Produces
    @Priority(1)
    TelescopeCustomizer<UserConfiguredProjection> second(final UserCityPrefixTransformer prefix) {
      return projection -> projection.addTransformer(prefix);
    }

    @Produces
    @Priority(2)
    TelescopeCustomizer<UserConfiguredProjection> first(final UserCityTransformer lower) {
      return projection -> projection.addTransformer(lower);
    }

    /** The one {@code Mapper} bean, for a pair no generated bean maps. */
    @Produces
    Mapper<User.Address, User.Address> addresses() {
      return ADDRESSES;
    }
  }

  static final Mapper<User.Address, User.Address> ADDRESSES = Telescope.mapper(User.Address.class, User.Address.class);

  @Inject
  UserProjection projection;

  @Inject
  @Named("userProjection")
  UserProjection namedProjection;

  @Inject
  UserTranslatedProjection translated;

  @Inject
  UserConfiguredProjection configured;

  @Inject
  UserMapper mapper;

  @Inject
  LowerCityUserMapper lowerCity;

  @Inject
  UserCityTransformer city;

  @Inject
  @Named("userCity")
  TelescopePath<User, String> namedCity;

  @Inject
  UserCityPrefixTransformer prefix;

  @Inject
  TelescopeMapperRegistry registry;

  @Test
  @DisplayName("a bridge-backed projection maps forward and back, and patches the way Mapper.patch does")
  void bridgeBackedProjectionMapsAndPatches() {
    final var user = new User("Alice", new User.Address("Boston"));
    assertThat(projection.map(user)).isEqualTo(new UserDto("Alice", new User.Address("Boston")));
    assertThat(projection.forward(user)).isEqualTo(projection.map(user));
    assertThat(projection.map(new User(null, null))).isEqualTo(new UserDto("(unnamed)", null));
    assertThat(projection.map(null)).isNull();
    assertThat(projection.backward(new UserDto("Bob", new User.Address("Paris")))).isEqualTo(
      new User("Bob", new User.Address("Paris"))
    );
    final var partial = new UserDto(null, new User.Address("Paris"));
    final var patched = projection.patch(user, partial);
    assertThat(patched).isEqualTo(new User("Alice", new User.Address("Paris")));
    assertThat(patched).isEqualTo(
      Telescope.mapper(User.class, UserDto.class, toOrElse(User::name, UserDto::name, "(unnamed)")).patch(user, partial)
    );
    assertThat(translated.patch(user, partial)).isEqualTo(patched);
  }

  @Test
  @DisplayName("a projection that overrides translate maps through a core Mapper, not the bridge")
  void translatedProjectionMapsThroughACoreMapper() {
    // The bridge fills a null name with "(unnamed)"; a core Mapper from no rows leaves it null.
    assertThat(translated.map(new User(null, null))).isEqualTo(new UserDto(null, null));
    assertThat(projection.map(new User(null, null))).isEqualTo(new UserDto("(unnamed)", null));
  }

  @Test
  @DisplayName("plain mapper interfaces map through the bridge, after any declared transformer")
  void plainMappersMapThroughTheBridge() {
    final var user = new User("Alice", new User.Address("BOSTON"));
    assertThat(mapper.map(user)).isEqualTo(new UserDto("Alice", new User.Address("BOSTON")));
    assertThat(lowerCity.map(user)).isEqualTo(new UserDto("Alice", new User.Address("boston")));
    assertThat(user.address().city()).isEqualTo("BOSTON");
  }

  @Test
  @DisplayName("declared transformers run in order, then customizers in priority order, on the way forward only")
  void transformersAndCustomizersRunInOrder() {
    final var source = new User("Alice", new User.Address("BOSTON"));
    assertThat(configured.map(source).address().city()).isEqualTo("PREFIX:prefix:boston");
    assertThat(configured.backward(new UserDto("Alice", new User.Address("BOSTON"))).address().city()).isEqualTo(
      "BOSTON"
    );
    assertThat(configured.map(new User("Alice", null))).isEqualTo(new UserDto("Alice", null));
    assertThat(projection.map(source).address().city()).isEqualTo("BOSTON");
    assertThat(source.address().city()).isEqualTo("BOSTON");
  }

  @Test
  @DisplayName("a projection's transformers are fixed once the bean is constructed")
  void transformersAreFixedOnceConstructed() {
    assertThatThrownBy(() -> projection.addTransformer(prefix))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("TelescopeCustomizer<UserProjection>");
    assertThat(projection.map(new User("Alice", new User.Address("BOSTON"))).address().city()).isEqualTo("BOSTON");
  }

  @Test
  @DisplayName("a transformer caches its path and transformation, reads, sets and applies")
  void transformerCachesItsPathAndTransformation() {
    final var user = new User("Alice", new User.Address("BOSTON"));
    assertThat(city.path()).isSameAs(city.path());
    assertThat(city.transform()).isSameAs(city.transform());
    assertThat(city.read(user)).isEqualTo("BOSTON");
    assertThat(city.set(user, "Paris").address().city()).isEqualTo("Paris");
    assertThat(city.apply(user).address().city()).isEqualTo("boston");
    assertThat(city.apply(new User("Alice", null))).isEqualTo(new User("Alice", null));
    assertThat(user.address().city()).isEqualTo("BOSTON");
  }

  @Test
  @DisplayName("a bean name becomes a @Named qualifier, and the bean still injects by its type alone")
  void beanNameIsANamedQualifier() {
    final var user = new User("Alice", new User.Address("Boston"));
    assertThat(namedCity.read(user)).isEqualTo("Boston");
    assertThat(namedProjection.map(user)).isEqualTo(projection.map(user));
  }

  @Test
  @DisplayName("generated projections stay out of the mapper registry, since a projection is not a Mapper")
  void projectionsStayOutOfTheRegistry() {
    assertThat(projection).isNotInstanceOf(Mapper.class);
    assertThat(registry.size()).isEqualTo(1);
    assertThat(registry.get(User.Address.class, User.Address.class)).isSameAs(ADDRESSES);
    assertThat(registry.contains(User.class, UserDto.class)).isFalse();
  }
}
