package io.github.eschizoid.telescope.quarkus.tests;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;

/** Assertions on the failure a misconfigured application boots with. */
final class Refusals {

  private Refusals() {}

  /** Asserts that some exception in the failure's cause chain carries exactly {@code message}. */
  static Consumer<Throwable> refusedWith(final String message) {
    return failure -> {
      final var messages = new StringBuilder();
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        if (message.equals(cause.getMessage())) return;
        messages.append('\n').append(cause.getMessage());
      }
      assertThat(messages.toString()).as("the failure's messages").contains(message);
    };
  }
}
