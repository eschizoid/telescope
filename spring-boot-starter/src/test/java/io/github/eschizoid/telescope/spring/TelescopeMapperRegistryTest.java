package io.github.eschizoid.telescope.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
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
          .hasMessageContaining("registered")
          .hasMessageContaining("Define a @Bean Mapper<Source, AltTarget> in your @Configuration");
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

  @Test
  @DisplayName("qualified Mapper beans for one pair still fail startup, and the message names what keeps one out")
  void qualifiedDuplicatesFailWithAdviceThatWorks() {
    contextRunner
      .withUserConfiguration(QualifiedDuplicatePairConfig.class)
      .run(ctx -> {
        assertThat(ctx).hasFailed();
        assertThat(rootCause(ctx.getStartupFailure()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Duplicate Mapper for type pair")
          .hasMessageContaining("one Mapper per type pair")
          .hasMessageContaining("a @Qualifier does not keep the second one out")
          .hasMessageContaining("@Bean(defaultCandidate = false) and inject it by its qualifier")
          .hasMessageContaining("wrap it in a type of your own")
          .hasMessageContaining("build it where it is used");
      });
  }

  @Test
  @DisplayName("fail-fast set to false governs missing pairs only; a duplicate pair still fails startup")
  void failFastFalseStillRejectsADuplicatePair() {
    contextRunner
      .withUserConfiguration(DuplicatePairConfig.class)
      .withPropertyValues("telescope.registry.fail-fast=false")
      .run(ctx -> {
        assertThat(ctx).hasFailed();
        assertThat(rootCause(ctx.getStartupFailure()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Duplicate Mapper for type pair");
      });
  }

  @Test
  @DisplayName("a Mapper bean that is not a default candidate stays out of the registry and injects by qualifier")
  void nonDefaultCandidateMapperStaysOutOfTheRegistry() {
    contextRunner
      .withUserConfiguration(NonDefaultCandidateConfig.class)
      .run(ctx -> {
        assertThat(ctx).hasNotFailed();
        final var registry = ctx.getBean(TelescopeMapperRegistry.class);
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.get(Source.class, Target.class)).isSameAs(ctx.getBean("primaryMapper"));
        assertThat(ctx.getBean(LegacyConsumer.class).mapper()).isSameAs(ctx.getBean("legacyMapper"));
      });
  }

  @Test
  @DisplayName("a second mapper wrapped in an application type stays out of the registry")
  void wrappedMapperStaysOutOfTheRegistry() {
    contextRunner
      .withUserConfiguration(WrappedSecondMapperConfig.class)
      .run(ctx -> {
        assertThat(ctx).hasNotFailed();
        final var registry = ctx.getBean(TelescopeMapperRegistry.class);
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.get(Source.class, Target.class)).isSameAs(ctx.getBean("primaryMapper"));
        assertThat(ctx.getBean(LegacyConsumer.class).mapper()).isNotSameAs(ctx.getBean("primaryMapper"));
      });
  }

  private static Throwable rootCause(final Throwable failure) {
    var cause = failure;
    while (cause.getCause() != null) cause = cause.getCause();
    return cause;
  }

  record LegacyConsumer(Mapper<Source, Target> mapper) {}

  @Configuration
  static class QualifiedDuplicatePairConfig {

    @Bean
    @Qualifier("primary")
    Mapper<Source, Target> primaryMapper() {
      return Telescope.mapper(Source.class, Target.class);
    }

    @Bean
    @Qualifier("legacy")
    Mapper<Source, Target> legacyMapper() {
      return Telescope.mapper(Source.class, Target.class);
    }
  }

  @Configuration
  static class NonDefaultCandidateConfig {

    @Bean
    Mapper<Source, Target> primaryMapper() {
      return Telescope.mapper(Source.class, Target.class);
    }

    @Bean(defaultCandidate = false)
    @Qualifier("legacy")
    Mapper<Source, Target> legacyMapper() {
      return Telescope.mapper(Source.class, Target.class);
    }

    @Bean
    LegacyConsumer legacyConsumer(@Qualifier("legacy") final Mapper<Source, Target> mapper) {
      return new LegacyConsumer(mapper);
    }
  }

  @Configuration
  static class WrappedSecondMapperConfig {

    @Bean
    Mapper<Source, Target> primaryMapper() {
      return Telescope.mapper(Source.class, Target.class);
    }

    @Bean
    LegacyConsumer legacyConsumer() {
      return new LegacyConsumer(Telescope.mapper(Source.class, Target.class));
    }
  }

  @Configuration
  static class CustomRegistryConfig {

    @Bean
    TelescopeMapperRegistry telescopeMapperRegistry() {
      return new TelescopeMapperRegistry(List.of(), true);
    }
  }
}
