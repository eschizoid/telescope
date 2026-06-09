package io.github.eschizoid.telescope.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.Mapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Integration tests for {@link TelescopeAutoConfiguration} + {@link TelescopeMapperRegistry}. Drive
 * a minimal Spring {@code ApplicationContext} via {@link ApplicationContextRunner}, register a few
 * {@code @Bean Mapper<A, B>} definitions, and assert the registry indexes them correctly.
 */
class TelescopeMapperRegistryTest {

  record Source(String name) {}

  record Target(String name) {}

  record AltSource(int value) {}

  record AltTarget(int value) {}

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
    AutoConfigurations.of(TelescopeAutoConfiguration.class)
  );

  @Test
  void registryIsAutoCreatedAndIndexesEveryMapperBean() {
    contextRunner
      .withUserConfiguration(TwoMappersConfig.class)
      .run(ctx -> {
        assertThat(ctx).hasSingleBean(TelescopeMapperRegistry.class);
        final var registry = ctx.getBean(TelescopeMapperRegistry.class);
        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.contains(Source.class, Target.class)).isTrue();
        assertThat(registry.contains(AltSource.class, AltTarget.class)).isTrue();
      });
  }

  @Test
  void getReturnsTheRegisteredMapperForALookedUpTypePair() {
    contextRunner
      .withUserConfiguration(TwoMappersConfig.class)
      .run(ctx -> {
        final var registry = ctx.getBean(TelescopeMapperRegistry.class);
        final Mapper<Source, Target> mapper = registry.get(Source.class, Target.class);
        assertThat(mapper).isNotNull();
        final var dto = mapper.forward(new Source("alice"));
        assertThat(dto.name()).isEqualTo("alice");
      });
  }

  @Test
  void getThrowsForMissingTypePairByDefault() {
    contextRunner
      .withUserConfiguration(TwoMappersConfig.class)
      .run(ctx -> {
        final var registry = ctx.getBean(TelescopeMapperRegistry.class);
        assertThatThrownBy(() -> registry.get(Source.class, AltTarget.class))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("No Mapper")
          .hasMessageContaining("registered");
      });
  }

  @Test
  void failFastFalseMakesGetReturnNullInsteadOfThrowing() {
    contextRunner
      .withUserConfiguration(TwoMappersConfig.class)
      .withPropertyValues("telescope.registry.fail-fast=false")
      .run(ctx -> {
        final var registry = ctx.getBean(TelescopeMapperRegistry.class);
        assertThat(registry.get(Source.class, AltTarget.class)).isNull();
      });
  }

  @Test
  void findReturnsOptionalRegardlessOfFailFast() {
    contextRunner
      .withUserConfiguration(TwoMappersConfig.class)
      .run(ctx -> {
        final var registry = ctx.getBean(TelescopeMapperRegistry.class);
        assertThat(registry.find(Source.class, Target.class)).isPresent();
        assertThat(registry.find(Source.class, AltTarget.class)).isEmpty();
      });
  }

  @Test
  void duplicateTypePairFailsAtContextStartup() {
    contextRunner.withUserConfiguration(DuplicatePairConfig.class).run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void userOverrideOfRegistryBeanSuppressesAutoConfig() {
    contextRunner
      .withUserConfiguration(CustomRegistryConfig.class)
      .run(ctx -> {
        final var registry = ctx.getBean(TelescopeMapperRegistry.class);
        // Empty collection passed to the user's override -> size 0.
        assertThat(registry.size()).isZero();
      });
  }

  @Configuration
  static class TwoMappersConfig {

    @Bean
    Mapper<Source, Target> sourceToTarget() {
      return Telescope.mapper(Source.class, Target.class);
    }

    @Bean
    Mapper<AltSource, AltTarget> altSourceToAltTarget() {
      return Telescope.mapper(AltSource.class, AltTarget.class);
    }
  }

  @Configuration
  static class DuplicatePairConfig {

    @Bean
    Mapper<Source, Target> sourceToTargetOne() {
      return Telescope.mapper(Source.class, Target.class);
    }

    @Bean
    Mapper<Source, Target> sourceToTargetTwo() {
      return Telescope.mapper(Source.class, Target.class);
    }
  }

  @Configuration
  static class CustomRegistryConfig {

    @Bean
    TelescopeMapperRegistry telescopeMapperRegistry() {
      return new TelescopeMapperRegistry(java.util.List.of(), true);
    }
  }
}
