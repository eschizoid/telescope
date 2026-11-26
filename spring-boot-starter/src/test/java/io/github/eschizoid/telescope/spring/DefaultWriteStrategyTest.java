package io.github.eschizoid.telescope.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.mapping.WriteStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** How {@code telescope.default-write-strategy} binds: a strategy by name, and ADDERS refused. */
class DefaultWriteStrategyTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
    AutoConfigurations.of(TelescopeAutoConfiguration.class)
  );

  @Test
  @DisplayName("a strategy binds by its constant name, in any case and with dashes")
  void aStrategyBindsByName() {
    contextRunner
      .withPropertyValues("telescope.default-write-strategy=setters")
      .run(ctx ->
        assertThat(ctx.getBean(TelescopeProperties.class).getDefaultWriteStrategy()).isEqualTo(WriteStrategy.SETTERS)
      );
  }

  @Test
  @DisplayName("ADDERS fails the application at startup, saying what replaced it")
  void addersFailsStartupWithTheReason() {
    contextRunner
      .withPropertyValues("telescope.default-write-strategy=ADDERS")
      .run(ctx -> {
        assertThat(ctx).hasFailed();
        var cause = ctx.getStartupFailure();
        var found = false;
        while (cause != null) {
          found |= PairingMessages.addersRemoved("telescope.default-write-strategy").equals(cause.getMessage());
          cause = cause.getCause();
        }
        assertThat(found).as("the reason is in the startup failure: %s", ctx.getStartupFailure()).isTrue();
      });
  }
}
