package io.github.eschizoid.telescope.codegen.lombok;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.lombok.fixtures.FinalRoundUser;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.FinalRoundUserCaller;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a navigator the {@code @BeanFocus} processor writes in the final round can be used for in
 * the compilation that writes it, with real Lombok on the processor path. A method body calls it:
 * javac attributes bodies after it has entered the final round's files. The type of a field naming
 * it does not resolve, and javac's warning that the file was created in the last round fails a
 * {@code -Werror} build unless {@code -nowarn} is passed too. With telescope-lombok on the path the
 * same {@code @Data} class gets its navigator before the final round, and every one of those
 * compiles.
 */
class FinalRoundNavigatorTest {

  // The source of the fixture this module's own compileTestJava compiles. Gradle runs tests from
  // the module's directory.
  private static final Path FINAL_ROUND_USER = Path.of(
    "src/test/java/io/github/eschizoid/telescope/codegen/lombok/fixtures/FinalRoundUser.java"
  );

  // The member sits on line 9 of the source.
  private static String person(final String member) {
    return """
    package demo;
    import io.github.eschizoid.telescope.annotations.BeanFocus;
    import lombok.Data;
    @BeanFocus
    @Data
    public class Person {
      private String name;
      private int age;
      %s
    }
    """.formatted(member);
  }

  private static final String BODY_CALL =
    "public static Person shout(final Person person) { return PersonTelescope.of().name().update(person, String::toUpperCase); }";

  private static final String DECLARED_TYPE = "static final PersonTelescope<Person> NAVIGATOR = PersonTelescope.of();";

  private static boolean lastRound(final List<String> warnings, final String type) {
    return warnings.contains(
      "File for type '" + type + "' created in the last round will not be subject to annotation processing."
    );
  }

  @Test
  @DisplayName("a method body in this source set calls a navigator the @BeanFocus processor writes in the final round")
  void thisSourceSetCallsAFinalRoundNavigator(@TempDir final Path dir) throws IOException {
    final var user = new FinalRoundUser();
    user.setName("ada");
    user.setAge(36);

    final var shouted = FinalRoundUserCaller.shoutName(user);

    assertEquals("ADA", shouted.getName());
    assertEquals(36, shouted.getAge());
    // The same source, through this module's processors, has its navigator written in the final
    // round, so the call above is a call into a final-round navigator.
    final var warnings = LombokLastCompiler.warnings(
      dir,
      "FinalRoundUser",
      Files.readString(FINAL_ROUND_USER),
      true,
      List.of()
    );
    assertTrue(
      lastRound(warnings, "io.github.eschizoid.telescope.codegen.lombok.fixtures.FinalRoundUserTelescope"),
      warnings::toString
    );
  }

  @Test
  @DisplayName("without telescope-lombok, a method body calls the final-round navigator of a @BeanFocus @Data class")
  void aBodyCallsTheFinalRoundNavigatorOfADataClass(@TempDir final Path dir) throws Exception {
    final var warnings = LombokLastCompiler.warnings(dir, "Person", person(BODY_CALL), false, List.of());
    assertTrue(lastRound(warnings, "demo.PersonTelescope"), warnings::toString);

    final var classes = new URL[] { dir.resolve("classes").toUri().toURL() };
    try (final var loader = new URLClassLoader(classes, getClass().getClassLoader())) {
      final var type = loader.loadClass("demo.Person");
      final var person = type.getDeclaredConstructor().newInstance();
      type.getMethod("setName", String.class).invoke(person, "ada");

      final var shouted = type.getMethod("shout", type).invoke(null, person);

      assertEquals("ADA", type.getMethod("getName").invoke(shouted));
    }
  }

  @Test
  @DisplayName("without telescope-lombok, a field whose type is the final-round navigator does not resolve")
  void aFieldTypedAsTheFinalRoundNavigatorFails(@TempDir final Path dir) throws IOException {
    LombokLastCompiler.compile(dir.resolve("early"), "Person", person(DECLARED_TYPE), true);

    final var errors = LombokLastCompiler.errors(
      dir.resolve("late"),
      "Person",
      person(DECLARED_TYPE),
      false,
      List.of()
    );

    assertTrue(errors.contains("Person.java:9: cannot find symbol"), errors);
  }

  @Test
  @DisplayName("without telescope-lombok, the last-round warning fails a -Werror build of the @Data class")
  void theFinalRoundNavigatorFailsWerror(@TempDir final Path dir) throws IOException {
    final var werror = List.of("-Werror");
    LombokLastCompiler.compile(dir.resolve("early"), "Person", person(BODY_CALL), true, werror);
    LombokLastCompiler.compile(
      dir.resolve("nowarn"),
      "Person",
      person(BODY_CALL),
      false,
      List.of("-Werror", "-nowarn")
    );

    final var warnings = LombokLastCompiler.warnings(
      dir.resolve("warned"),
      "Person",
      person(BODY_CALL),
      false,
      List.of()
    );
    final var errors = LombokLastCompiler.errors(dir.resolve("late"), "Person", person(BODY_CALL), false, werror);

    assertTrue(errors.contains("warnings found and -Werror specified"), errors);
    // The warnings -Werror fails on are the last-round ones, and only those.
    assertTrue(lastRound(warnings, "demo.PersonTelescope"), warnings::toString);
    assertTrue(warnings.stream().allMatch(w -> w.contains("created in the last round")), warnings::toString);
  }
}
