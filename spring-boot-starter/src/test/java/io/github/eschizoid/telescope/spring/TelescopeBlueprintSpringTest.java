package io.github.eschizoid.telescope.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eschizoid.telescope.spring.blueprint.ContactBean;
import io.github.eschizoid.telescope.spring.blueprint.ContactNameTransformer;
import io.github.eschizoid.telescope.spring.blueprint.Counter;
import io.github.eschizoid.telescope.spring.blueprint.CounterValueTransformer;
import io.github.eschizoid.telescope.spring.blueprint.Directory;
import io.github.eschizoid.telescope.spring.blueprint.DirectoryNamesTransformer;
import io.github.eschizoid.telescope.spring.blueprint.DirectoryUsersTransformer;
import io.github.eschizoid.telescope.spring.blueprint.User;
import io.github.eschizoid.telescope.spring.blueprint.UserCityPrefixTransformer;
import io.github.eschizoid.telescope.spring.blueprint.UserCityTransformer;
import io.github.eschizoid.telescope.spring.blueprint.UserConfiguredProjection;
import io.github.eschizoid.telescope.spring.blueprint.UserDto;
import io.github.eschizoid.telescope.spring.blueprint.UserProjection;
import io.github.eschizoid.telescope.spring.blueprint.Workspace;
import io.github.eschizoid.telescope.spring.blueprint.WorkspaceEmailTransformer;
import io.github.eschizoid.telescope.spring.invalidblueprint.NullPathTransformerImpl;
import io.github.eschizoid.telescope.spring.invalidblueprint.NullTransformerImpl;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

class TelescopeBlueprintSpringTest {

  @Test
  void generatedMapperAndPathAreInjectable() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        assertThat(context).hasSingleBean(UserProjection.class);
        assertThat(context).hasSingleBean(UserCityTransformer.class);
        final var user = new User("Alice", new User.Address("Boston"));
        assertThat(context.getBean(UserProjection.class).map(user)).isEqualTo(
          new UserDto("Alice", new User.Address("Boston"))
        );
        final var city = context.getBean(UserCityTransformer.class);
        assertThat(city.path()).isSameAs(city.path());
        assertThat(city.read(user)).isEqualTo("Boston");
        assertThat(city.apply(user).address().city()).isEqualTo("boston");
      });
  }

  @Test
  void nestedTransformReadsSetsAndUpdatesWithoutMutatingTheInput() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        final var email = context.getBean(WorkspaceEmailTransformer.class);
        final var original = new Workspace(new Workspace.Profile(new Workspace.Contact("ALICE@EXAMPLE.COM")));

        assertThat(email.read(original)).isEqualTo("ALICE@EXAMPLE.COM");
        final var lowered = email.apply(original);
        assertThat(email.read(lowered)).isEqualTo("alice@example.com");
        assertThat(email.read(original)).isEqualTo("ALICE@EXAMPLE.COM");
        assertThat(email.read(email.set(original, "other@example.com"))).isEqualTo("other@example.com");
      });
  }

  @Test
  void nullLeafCanBeReadAndReplacedAndNullRootHasNoReadFocus() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        final var email = context.getBean(WorkspaceEmailTransformer.class);
        final var workspace = new Workspace(new Workspace.Profile(new Workspace.Contact(null)));
        assertThat(email.read(workspace)).isNull();
        assertThat(email.read(email.set(workspace, "fallback@example.com"))).isEqualTo("fallback@example.com");
        assertThatThrownBy(() -> email.read(null)).isInstanceOf(NoSuchElementException.class);

        final var mapper = context.getBean(UserProjection.class);
        assertThat(mapper.map(null)).isNull();
        assertThat(mapper.map(new User(null, null))).isEqualTo(new UserDto("(unnamed)", null));
      });
  }

  @Test
  void missingNestedRecordsHaveNoFocusAndWritesLeaveThemUnchanged() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        final var email = context.getBean(WorkspaceEmailTransformer.class);
        final var missingProfile = new Workspace(null);
        final var missingContact = new Workspace(new Workspace.Profile(null));

        assertThatThrownBy(() -> email.read(missingProfile)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> email.read(missingContact)).isInstanceOf(NoSuchElementException.class);
        assertThat(email.set(missingProfile, "new@example.com")).isEqualTo(missingProfile);
        assertThat(email.apply(missingContact)).isEqualTo(missingContact);
      });
  }

  @Test
  void primitiveTerminalIsBoxedAndCanBeUpdated() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        final var value = context.getBean(CounterValueTransformer.class);
        assertThat(value.read(new Counter(4))).isEqualTo(4);
        assertThat(value.apply(new Counter(4))).isEqualTo(new Counter(5));
      });
  }

  @Test
  void namedBeansAreQualifiedAndPathsAreBuiltOncePerBean() {
    final var before = UserCityTransformer.BUILDS.get();
    final var beforeTransform = UserCityTransformer.TRANSFORM_BUILDS.get();
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class, Consumers.class)
      .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context.getBean("cityValue")).isEqualTo("Boston");
        assertThat(context.getBean("userProjection")).isSameAs(context.getBean(UserProjection.class));
        final var city = context.getBean(UserCityTransformer.class);
        final var user = new User("Alice", new User.Address("Boston"));
        city.read(user);
        city.set(user, "Paris");
        city.apply(user);
        assertThat(UserCityTransformer.BUILDS.get() - before).isEqualTo(1);
        assertThat(UserCityTransformer.TRANSFORM_BUILDS.get() - beforeTransform).isEqualTo(1);
      });
  }

  @Test
  void collectionFocusAndTraversalKeepTheirTypesAndCopySemantics() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        final var users = context.getBean(DirectoryUsersTransformer.class);
        final var names = context.getBean(DirectoryNamesTransformer.class);
        final var original = new Directory(List.of(new User("Alice", null), new User("Bob", null)));
        assertThat(users.read(original)).isEqualTo(original.users());
        assertThat(users.set(original, List.of()).users()).isEmpty();
        assertThat(names.toList(original)).containsExactly("Alice", "Bob");
        assertThat(names.find(original)).contains("Alice");
        assertThat(names.find(new Directory(List.of()))).isEmpty();
        assertThat(names.toList(names.apply(original))).containsExactly("alice", "bob");
        assertThat(names.toList(original)).containsExactly("Alice", "Bob");
      });
  }

  @Test
  void beanPathRebuildsWithoutMutatingTheInput() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        final var name = context.getBean(ContactNameTransformer.class);
        final var original = new ContactBean();
        original.setName("Alice");
        final var changed = name.apply(original);
        assertThat(name.read(changed)).isEqualTo("alice");
        assertThat(name.read(original)).isEqualTo("Alice");
        assertThat(changed).isNotSameAs(original);
      });
  }

  @Test
  void nullPathFailsAtBeanCreation() {
    new ApplicationContextRunner()
      .withUserConfiguration(NullPathTransformerImpl.class)
      .run(context -> {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure())
          .hasRootCauseInstanceOf(NullPointerException.class)
          .hasStackTraceContaining("path() must not return null");
      });
  }

  @Test
  void nullTransformFailsAtBeanCreation() {
    new ApplicationContextRunner()
      .withUserConfiguration(NullTransformerImpl.class)
      .run(context -> {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure())
          .hasRootCauseInstanceOf(NullPointerException.class)
          .hasStackTraceContaining("transform() must not return null");
      });
  }

  @Test
  void nullLeafUsesDeclaredDefaultAndLeavesOriginalUnchanged() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        final var names = context.getBean(DirectoryNamesTransformer.class);
        final var original = new Directory(List.of(new User(null, null), new User("ALICE", null)));
        final var changed = names.apply(original);
        assertThat(names.toList(changed)).containsExactly("(unnamed)", "alice");
        assertThat(names.toList(original)).containsExactly(null, "ALICE");
      });
  }

  @Test
  void nullDefaultPreservesNullNestedLeaf() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        final var city = context.getBean(UserCityTransformer.class);
        final var original = new User("Alice", new User.Address(null));
        assertThat(city.apply(original).address().city()).isNull();
        assertThat(city.apply(new User("Alice", null))).isEqualTo(new User("Alice", null));
      });
  }

  @Test
  void emptyAndWhitespaceValuesRunTheOperation() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        final var city = context.getBean(UserCityTransformer.class);
        assertThat(city.apply(new User("Alice", new User.Address(""))).address().city()).isEmpty();
        assertThat(city.apply(new User("Alice", new User.Address("  "))).address().city()).isEqualTo("  ");
      });
  }

  @Test
  void emptyAndNullCollectionsUseDifferentBranches() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        final var users = context.getBean(DirectoryUsersTransformer.class);
        final var first = new User("Alice", null);
        final var second = new User("Bob", null);
        assertThat(users.apply(new Directory(null)).users()).isEmpty();
        assertThat(users.apply(new Directory(List.of())).users()).isEmpty();
        assertThat(users.apply(new Directory(List.of(first, second))).users()).containsExactly(second, first);
      });
  }

  @Test
  void emptyTraversalHasNoFocus() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        final var names = context.getBean(DirectoryNamesTransformer.class);
        final var source = new Directory(List.of());
        assertThat(names.apply(source)).isEqualTo(source);
        assertThat(names.toList(names.apply(source))).isEmpty();
      });
  }

  @Test
  void missingIntermediateRecordsDoNotReceiveLeafDefault() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        final var email = context.getBean(WorkspaceEmailTransformer.class);
        final var missingProfile = new Workspace(null);
        final var missingContact = new Workspace(new Workspace.Profile(null));
        assertThat(email.apply(missingProfile)).isEqualTo(missingProfile);
        assertThat(email.apply(missingContact)).isEqualTo(missingContact);
      });
  }

  @Test
  void mixedTraversalTransformsEveryAvailableFocus() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        final var names = context.getBean(DirectoryNamesTransformer.class);
        final var source = new Directory(
          Arrays.asList(new User("ALICE", null), null, new User(null, null), new User("", null))
        );
        final var changed = names.apply(source);
        assertThat(changed.users().get(0).name()).isEqualTo("alice");
        assertThat(changed.users().get(1)).isNull();
        assertThat(changed.users().get(2).name()).isEqualTo("(unnamed)");
        assertThat(changed.users().get(3).name()).isEmpty();
        assertThat(source.users().get(0).name()).isEqualTo("ALICE");
      });
  }

  @Test
  void normalizerAppliesToAllHundredElements() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        final var names = context.getBean(DirectoryNamesTransformer.class);
        final var original = new Directory(
          IntStream.range(0, 100)
            .mapToObj(index -> new User("NAME" + index, null))
            .toList()
        );
        final var changed = names.apply(original);
        assertThat(names.toList(changed)).hasSize(100).startsWith("name0").endsWith("name99");
        assertThat(names.toList(original)).startsWith("NAME0");
      });
  }

  @Test
  void sourceAndTransformationCanBeReused() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        final var city = context.getBean(UserCityTransformer.class);
        final var original = new User("Alice", new User.Address("BOSTON"));
        assertThat(city.transform()).isSameAs(city.transform());
        assertThat(city.apply(original).address().city()).isEqualTo("boston");
        assertThat(city.apply(original).address().city()).isEqualTo("boston");
        assertThat(original.address().city()).isEqualTo("BOSTON");
      });
  }

  @Test
  void projectionExposesTheCoreMapperOperations() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        final var projection = context.getBean(UserProjection.class);
        final var user = new User("Alice", new User.Address("Boston"));
        assertThat(projection.forward(user)).isEqualTo(projection.map(user));
        assertThat(projection.backward(new UserDto("Bob", new User.Address("Paris")))).isEqualTo(
          new User("Bob", new User.Address("Paris"))
        );
        assertThat(projection.patch(user, new UserDto("Bob", new User.Address("Boston"))).name()).isEqualTo("Bob");

        // Transformers apply on the way forward only.
        final var configured = context.getBean(UserConfiguredProjection.class);
        final var source = new User("Alice", new User.Address("BOSTON"));
        assertThat(configured.forward(source).address().city()).isEqualTo("prefix:boston");
        assertThat(configured.backward(new UserDto("Alice", new User.Address("BOSTON"))).address().city()).isEqualTo(
          "BOSTON"
        );
      });
  }

  @Test
  void customizerRegistersTransformersInOrderWithoutASecondBean() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class, CustomizerConfig.class)
      .run(context -> {
        assertThat(context).hasSingleBean(UserProjection.class);
        final var source = new User("Alice", new User.Address("BOSTON"));
        assertThat(context.getBean(UserProjection.class).map(source).address().city()).isEqualTo("prefix:boston");
        assertThat(source.address().city()).isEqualTo("BOSTON");
      });
  }

  @Test
  void declaredTransformersAreInjectedIntoTheMapperBeanInOrder() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        final var projection = context.getBean(UserConfiguredProjection.class);
        final var source = new User("Alice", new User.Address("BOSTON"));
        assertThat(projection.map(source).address().city()).isEqualTo("prefix:boston");
        assertThat(context.getBean(UserProjection.class).map(source).address().city()).isEqualTo("BOSTON");
      });
  }

  @Test
  void customizersRunAfterDeclaredTransformersInOrderAndOnlyForTheirProjection() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class, OrderedCustomizerConfig.class)
      .run(context -> {
        final var source = new User("Alice", new User.Address("BOSTON"));
        assertThat(context.getBean(UserConfiguredProjection.class).map(source).address().city()).isEqualTo(
          "PREFIX:prefix:boston"
        );
        assertThat(context.getBean(UserProjection.class).map(source).address().city()).isEqualTo("BOSTON");
      });
  }

  @Test
  void transformersAreFixedOnceTheBeanIsConstructed() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        final var projection = context.getBean(UserProjection.class);
        final var prefix = context.getBean(UserCityPrefixTransformer.class);
        assertThatThrownBy(() -> projection.addTransformer(prefix))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("TelescopeCustomizer<UserProjection>");
        assertThat(projection.map(new User("Alice", new User.Address("BOSTON"))).address().city()).isEqualTo("BOSTON");
      });
  }

  @Test
  void nullSourceAndMissingIntermediateSkipMapperTransformers() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        final var projection = context.getBean(UserConfiguredProjection.class);
        assertThat(projection.map(null)).isNull();
        assertThat(projection.map(new User("Alice", null))).isEqualTo(new UserDto("Alice", null));
        assertThat(projection.map(new User("Alice", new User.Address(null))).address().city()).isEqualTo(
          "prefix:unknown"
        );
      });
  }

  @Test
  void nullTransformerCannotBeRegistered() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class, NullCustomizerConfig.class)
      .run(context -> {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure())
          .hasRootCauseInstanceOf(NullPointerException.class)
          .hasStackTraceContaining("transformer must not be null");
      });
  }

  @Configuration
  static class CustomizerConfig {

    @Bean
    TelescopeCustomizer<UserProjection> userProjectionTransformers(
      UserCityPrefixTransformer prefix,
      UserCityTransformer lower
    ) {
      return projection -> projection.addTransformer(prefix).addTransformer(lower);
    }
  }

  @Configuration
  static class OrderedCustomizerConfig {

    // Declared in reverse: @Order, not declaration order, decides.
    @Bean
    @Order(2)
    TelescopeCustomizer<UserConfiguredProjection> second(UserCityPrefixTransformer prefix) {
      return projection -> projection.addTransformer(prefix);
    }

    @Bean
    @Order(1)
    TelescopeCustomizer<UserConfiguredProjection> first(UserCityTransformer lower) {
      return projection -> projection.addTransformer(lower);
    }
  }

  @Configuration
  static class NullCustomizerConfig {

    @Bean
    TelescopeCustomizer<UserProjection> nullTransformer() {
      return projection -> projection.addTransformer(null);
    }
  }

  @Configuration
  static class Consumers {

    @Bean
    String cityValue(@Qualifier("userCity") TelescopePath<User, String> city) {
      return city.read(new User("Alice", new User.Address("Boston")));
    }
  }

  @Configuration
  @ComponentScan(basePackageClasses = UserProjection.class)
  static class ScanBlueprints {}
}
