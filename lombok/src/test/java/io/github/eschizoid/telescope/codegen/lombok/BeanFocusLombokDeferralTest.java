package io.github.eschizoid.telescope.codegen.lombok;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.FocusedAllArgsUser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code @BeanFocus} bean whose constructor Lombok synthesises is rebuilt through that
 * constructor, as the runtime writer rebuilds it. A processor reading the bean before Lombok has
 * added the constructor sees only the hand-written setter and rebuilds through it instead.
 *
 * <p>Whether Lombok has run by the first round depends on where it sits on the processor path. The
 * fixture compiled by this module's build has Lombok first; the last test recompiles the same shape
 * with Lombok last, which is the order in which a first-round read sees the un-patched class.
 *
 * <p>Runs real javac with Lombok on the processor path, since Lombok's hooks do not install in the
 * in-memory harness.
 */
class BeanFocusLombokDeferralTest {

  @Test
  @DisplayName("the generated holder builds a Lombok all-args bean through its constructor")
  void generatedHolderUsesTheLombokConstructor() throws ReflectiveOperationException {
    final var holder = Class.forName(
      "io.github.eschizoid.telescope.codegen.lombok.fixtures.FocusedAllArgsUserFieldOptics"
    );
    final Function<String, Object> values = name -> "v";
    final var built = (FocusedAllArgsUser) holder.getMethod("construct", Function.class).invoke(null, values);

    assertEquals("v", built.getName(), "routed through the setter this would be v[setters]");
  }

  @Test
  @DisplayName("the runtime writer builds the same bean through the same constructor")
  void runtimeAgrees() {
    final var seed = new FocusedAllArgsUser("a");
    final var written = Telescope.ofBean(FocusedAllArgsUser.class).field(FocusedAllArgsUser::getName).set(seed, "v");

    assertEquals("v", written.getName());
  }

  @Test
  @DisplayName("with Lombok last on the processor path, the holder still builds through Lombok's constructor")
  void lombokLastOnTheProcessorPath(@TempDir final Path dir) throws IOException {
    final var generated = LombokLastCompiler.compile(
      dir,
      "LateUser",
      """
      package demo;
      import io.github.eschizoid.telescope.annotations.BeanFocus;
      import lombok.AllArgsConstructor;
      import lombok.NoArgsConstructor;
      @BeanFocus
      @NoArgsConstructor
      @AllArgsConstructor
      public class LateUser {
        private String name;
        public String getName() { return name; }
        public void setName(final String name) { this.name = name + "[setters]"; }
      }
      """,
      false
    );

    final var holder = generated.resolve("demo/LateUserFieldOptics.java");
    assertTrue(Files.exists(holder), () -> "no holder generated under " + generated);
    final var text = Files.readString(holder);
    assertTrue(
      text.contains("return new LateUser((String) values.apply(\"name\"));"),
      () -> "construct() should call Lombok's constructor; saw " + text
    );
  }
}
