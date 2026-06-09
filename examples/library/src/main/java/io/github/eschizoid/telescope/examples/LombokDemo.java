package io.github.eschizoid.telescope.examples;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.examples.lombok.LombokBuilderUser;
import io.github.eschizoid.telescope.examples.lombok.LombokDataUser;

/**
 * Exercises the {@code telescope-lombok} integration. The {@code LombokFocusProcessor} runs over
 * the {@code @Data} and {@code @Builder} fixtures during {@code compileJava} and emits the same
 * {@code <X>Path<R>} navigator shape that {@code @BeanFocus} produces — but consuming Lombok's
 * synthesised getters / setters / builder.
 *
 * <p>The Lombok processor emits on {@code processingOver()} (the last round) so its outputs are
 * <em>not</em> available to subsequent rounds of processing in the same task. Direct {@code import}
 * lines for {@code *Path} types therefore can't resolve. The integration test in {@code :lombok}
 * uses {@link Class#forName} for the same reason — that's the supported access path. Here we mirror
 * it: load the generated class by name, invoke its {@code start()} and per-property methods
 * reflectively, then exercise the resulting {@code Telescope} via its normal typed surface.
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

  // @Data: synthesised getId / setId / getEmail / setEmail. The generated Path uses the no-arg ctor
  // + setters rebuild path.
  private static void dataLombok() {
    final var user = new LombokDataUser("ABC", "FOO@BAR.COM");
    final Telescope<LombokDataUser, String> emailPath = generatedEmailPath(
      "io.github.eschizoid.telescope.examples.lombok.LombokDataUserPath"
    );
    final var lowered = emailPath.update(user, String::toLowerCase);
    System.out.println("[@Data] email update          : " + lowered);
  }

  // @Builder + @Getter: synthesised builder(). The generated Path rebuilds via builder().
  private static void builderLombok() {
    final var user = LombokBuilderUser.builder().id("ABC").email("FOO@BAR.COM").build();
    final Telescope<LombokBuilderUser, String> emailPath = generatedEmailPath(
      "io.github.eschizoid.telescope.examples.lombok.LombokBuilderUserPath"
    );
    final var lowered = emailPath.update(user, String::toLowerCase);
    System.out.println("[@Builder] email update       : id=" + lowered.getId() + ", email=" + lowered.getEmail());
  }

  // Load a generated <X>Path class by FQN, call its static start(), then call email() on the result
  // to get the leaf Telescope. The reflective glue lives only here — every actual Telescope op runs
  // through the normal typed API.
  @SuppressWarnings("unchecked")
  private static <S> Telescope<S, String> generatedEmailPath(final String pathClassFqn) {
    try {
      final var pathClass = Class.forName(pathClassFqn);
      final var start = pathClass.getDeclaredMethod("start").invoke(null);
      return (Telescope<S, String>) pathClass.getDeclaredMethod("email").invoke(start);
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException("could not load generated path " + pathClassFqn, e);
    }
  }
}
