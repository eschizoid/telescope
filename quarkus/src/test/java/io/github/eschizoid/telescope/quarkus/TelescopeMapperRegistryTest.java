package io.github.eschizoid.telescope.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
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
  @DisplayName("a missing pair points to a producer method or field, since Mapper cannot be a bean class")
  void getThrowsForMissingTypePairByDefault() {
    final var registry = new TelescopeMapperRegistry(List.of(Telescope.mapper(Source.class, Target.class)), true);

    assertThatThrownBy(() -> registry.get(Source.class, AltTarget.class))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("No Mapper")
      .hasMessageContaining("Declare a CDI producer method or producer field of type Mapper<Source, AltTarget>")
      .hasMessageContaining("a bean class cannot be one")
      .hasMessageNotContaining("@ApplicationScoped");
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
  @DisplayName("a duplicate pair says qualifiers do not keep a mapper out, and names what does")
  void duplicateTypePairFailsAtConstruction() {
    assertThatThrownBy(() ->
      new TelescopeMapperRegistry(
        List.of(Telescope.mapper(Source.class, Target.class), Telescope.mapper(Source.class, Target.class)),
        true
      )
    )
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("Duplicate Mapper for type pair")
      .hasMessageContaining("one Mapper per type pair")
      .hasMessageContaining("@Named or a @Qualifier does not keep a Mapper bean out of it")
      .hasMessageContaining("declare the other with @DefaultBean and inject it by its qualifier")
      .hasMessageContaining("wrap it in a type of your own, or build it where it is used")
      .hasMessageContaining("@Alternative does not help");
  }

  @Test
  @DisplayName("fail-fast set to false governs missing pairs only; a duplicate pair still fails construction")
  void failFastFalseStillRejectsADuplicatePair() {
    assertThatThrownBy(() ->
      new TelescopeMapperRegistry(
        List.of(Telescope.mapper(Source.class, Target.class), Telescope.mapper(Source.class, Target.class)),
        false
      )
    )
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("Duplicate Mapper for type pair");
  }
}
