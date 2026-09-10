package io.github.eschizoid.telescope.examples;

import io.github.eschizoid.telescope.examples.lombok.LombokBuilderUser;
import io.github.eschizoid.telescope.examples.lombok.LombokBuilderUserTelescope;
import io.github.eschizoid.telescope.examples.lombok.LombokDataUser;
import io.github.eschizoid.telescope.examples.lombok.LombokDataUserTelescope;

/**
 * Exercises the {@code telescope-lombok} integration. The {@code LombokFocusProcessor} runs over
 * the {@code @Data} and {@code @Builder} fixtures during {@code compileJava} and emits the same
 * {@code <X>Telescope<R>} navigator shape that {@code @BeanFocus} produces — but consuming Lombok's
 * synthesised getters / setters / builder.
 *
 * <p>The navigators are referenced here by direct {@code import}, from main sources in the module
 * whose compilation generates them. That resolving at all is the property the processor's emission
 * order exists to provide: a navigator emitted only in the final processing round would not exist
 * yet when these references are bound, so this class fails to compile if that order regresses.
 */
final class LombokDemo {

  private LombokDemo() {}

  static void main() {
    run();
  }

  static void run() {
    dataLombok();
    builderLombok();
  }

  // @Data: synthesised getters and setters. The generated navigator rebuilds through the setters.
  private static void dataLombok() {
    final var user = new LombokDataUser("ABC", "FOO@BAR.COM");
    final var lowered = LombokDataUserTelescope.of().email().update(user, String::toLowerCase);
    System.out.println("[@Data] email update          : " + lowered);
  }

  // @Builder + @Getter: synthesised builder(). The generated navigator rebuilds via builder().
  private static void builderLombok() {
    final var user = LombokBuilderUser.builder().id("ABC").email("FOO@BAR.COM").build();
    final var lowered = LombokBuilderUserTelescope.of().email().update(user, String::toLowerCase);
    System.out.println("[@Builder] email update       : id=" + lowered.getId() + ", email=" + lowered.getEmail());
  }
}
