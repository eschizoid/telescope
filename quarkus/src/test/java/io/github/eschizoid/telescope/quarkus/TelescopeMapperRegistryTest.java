package io.github.eschizoid.telescope.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eschizoid.telescope.Mapper;
import io.github.eschizoid.telescope.Telescope;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TelescopeMapperRegistry} — the registry is pure Java with no CDI
 * dependency, so these tests exercise it directly with hand-rolled mapper lists. The full
 * {@code @QuarkusTest} integration that wires {@link TelescopeProducer} through ArC's CDI container
 * is a follow-on for users who want to validate the autoconfig end-to-end; the unit tests here pin
 * the behaviour the producer relies on.
 *
 * <p>Mirror of {@code TelescopeMapperRegistryTest} in {@code telescope-spring-boot-starter} —
 * Spring's tests drive the autoconfig through {@code ApplicationContextRunner}; Quarkus' unit
 * surface is simpler because the registry itself has no framework hook.
 */
class TelescopeMapperRegistryTest {

  record Source(String name) {}

  record Target(String name) {}

  record AltSource(int value) {}

  record AltTarget(int value) {}

  @Test
  void registryIndexesEveryMapperByTypePair() {
    final var registry = new TelescopeMapperRegistry(
      List.of(Telescope.mapper(Source.class, Target.class), Telescope.mapper(AltSource.class, AltTarget.class)),
      true
    );

    assertThat(registry.size()).isEqualTo(2);
    assertThat(registry.contains(Source.class, Target.class)).isTrue();
    assertThat(registry.contains(AltSource.class, AltTarget.class)).isTrue();
    assertThat(registry.contains(Source.class, AltTarget.class)).isFalse();
  }

  @Test
  void getReturnsTheRegisteredMapperForALookedUpTypePair() {
    final var mapper = Telescope.mapper(Source.class, Target.class);
    final var registry = new TelescopeMapperRegistry(List.of(mapper), true);

    final Mapper<Source, Target> found = registry.get(Source.class, Target.class);
    assertThat(found).isNotNull();
    final var dto = found.forward(new Source("alice"));
    assertThat(dto.name()).isEqualTo("alice");
  }

  @Test
  void getThrowsForMissingTypePairByDefault() {
    final var registry = new TelescopeMapperRegistry(List.of(Telescope.mapper(Source.class, Target.class)), true);

    assertThatThrownBy(() -> registry.get(Source.class, AltTarget.class))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("No Mapper")
      .hasMessageContaining("CDI producer");
  }

  @Test
  void failFastFalseMakesGetReturnNullInsteadOfThrowing() {
    final var registry = new TelescopeMapperRegistry(List.of(Telescope.mapper(Source.class, Target.class)), false);

    assertThat(registry.get(Source.class, AltTarget.class)).isNull();
  }

  @Test
  void findReturnsOptionalRegardlessOfFailFast() {
    final var registry = new TelescopeMapperRegistry(List.of(Telescope.mapper(Source.class, Target.class)), true);

    assertThat(registry.find(Source.class, Target.class)).isPresent();
    assertThat(registry.find(Source.class, AltTarget.class)).isEmpty();
  }

  @Test
  void duplicateTypePairFailsAtConstruction() {
    assertThatThrownBy(() ->
      new TelescopeMapperRegistry(
        List.of(Telescope.mapper(Source.class, Target.class), Telescope.mapper(Source.class, Target.class)),
        true
      )
    )
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("Duplicate Mapper")
      .hasMessageContaining("@Named / @Qualifier");
  }
}
