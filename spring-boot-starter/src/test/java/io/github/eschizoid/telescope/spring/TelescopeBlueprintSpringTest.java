package io.github.eschizoid.telescope.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eschizoid.telescope.spring.blueprint.Counter;
import io.github.eschizoid.telescope.spring.blueprint.CounterValue;
import io.github.eschizoid.telescope.spring.blueprint.User;
import io.github.eschizoid.telescope.spring.blueprint.UserCity;
import io.github.eschizoid.telescope.spring.blueprint.UserDto;
import io.github.eschizoid.telescope.spring.blueprint.UserProjection;
import io.github.eschizoid.telescope.spring.blueprint.Workspace;
import io.github.eschizoid.telescope.spring.blueprint.WorkspaceEmail;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

class TelescopeBlueprintSpringTest {

  @Test
  void generatedMapperAndPathAreInjectable() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        assertThat(context).hasSingleBean(UserProjection.class);
        assertThat(context).hasSingleBean(UserCity.class);
        final var user = new User("Alice", new User.Address("Boston"));
        assertThat(context.getBean(UserProjection.class).map(user)).isEqualTo(
          new UserDto("Alice", new User.Address("Boston"))
        );
        final var city = context.getBean(UserCity.class);
        assertThat(city.read(user)).isEqualTo("Boston");
        assertThat(city.update(user, String::toUpperCase).address().city()).isEqualTo("BOSTON");
      });
  }

  @Test
  void nestedTransformReadsSetsAndUpdatesWithoutMutatingTheInput() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        final var email = context.getBean(WorkspaceEmail.class);
        final var original = new Workspace(new Workspace.Profile(new Workspace.Contact("ALICE@EXAMPLE.COM")));

        assertThat(email.read(original)).isEqualTo("ALICE@EXAMPLE.COM");
        final var lowered = email.update(original, String::toLowerCase);
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
        final var email = context.getBean(WorkspaceEmail.class);
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
        final var email = context.getBean(WorkspaceEmail.class);
        final var missingProfile = new Workspace(null);
        final var missingContact = new Workspace(new Workspace.Profile(null));

        assertThatThrownBy(() -> email.read(missingProfile)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> email.read(missingContact)).isInstanceOf(NoSuchElementException.class);
        assertThat(email.set(missingProfile, "new@example.com")).isEqualTo(missingProfile);
        assertThat(email.update(missingContact, old -> "new@example.com")).isEqualTo(missingContact);
      });
  }

  @Test
  void primitiveTerminalIsBoxedAndCanBeUpdated() {
    new ApplicationContextRunner()
      .withUserConfiguration(ScanBlueprints.class)
      .run(context -> {
        final var value = context.getBean(CounterValue.class);
        assertThat(value.read(new Counter(4))).isEqualTo(4);
        assertThat(value.update(new Counter(4), n -> n + 1)).isEqualTo(new Counter(5));
      });
  }

  @Configuration
  @ComponentScan(basePackageClasses = UserProjection.class)
  static class ScanBlueprints {}
}
