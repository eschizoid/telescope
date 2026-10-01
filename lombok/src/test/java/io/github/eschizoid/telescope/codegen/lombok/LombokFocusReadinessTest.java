package io.github.eschizoid.telescope.codegen.lombok;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.BuiltLombokUser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Lombok {@code @Builder} bean with hand-written accessors is rebuilt through its builder by the
 * generated navigator, as the runtime writer rebuilds it. The accessors read complete before Lombok
 * has added {@code builder()}, so a navigator emitted as soon as the bean is readable would rebuild
 * through the setter instead.
 *
 * <p>Runs real javac with Lombok on the processor path, since Lombok's hooks do not install in the
 * in-memory harness.
 */
class LombokFocusReadinessTest {

  @Test
  @DisplayName("the generated holder builds a Lombok @Builder bean through its builder")
  void generatedHolderUsesTheBuilder() throws ReflectiveOperationException {
    final var holder = Class.forName(
      "io.github.eschizoid.telescope.codegen.lombok.fixtures.BuiltLombokUserFieldOptics"
    );
    final Function<String, Object> values = name -> "v";
    final var built = (BuiltLombokUser) holder.getMethod("construct", Function.class).invoke(null, values);

    assertEquals("v", built.getName(), "routed through the setter this would be v[setters]");
  }

  @Test
  @DisplayName("the runtime writer builds the same bean through its builder")
  void runtimeAgrees() {
    final var seed = BuiltLombokUser.builder().name("a").build();
    final var written = Telescope.ofBean(BuiltLombokUser.class).field(BuiltLombokUser::getName).set(seed, "v");

    assertEquals("v", written.getName());
  }

  @Test
  @DisplayName("with Lombok last on the processor path, the navigator waits for Lombok's builder()")
  void lombokLastOnTheProcessorPath(@TempDir final Path dir) throws IOException {
    final var generated = LombokLastCompiler.compile(
      dir,
      "LateBuilt",
      """
      package demo;
      import lombok.AllArgsConstructor;
      import lombok.Builder;
      import lombok.NoArgsConstructor;
      @Builder
      @NoArgsConstructor
      @AllArgsConstructor
      public class LateBuilt {
        private String name;
        public String getName() { return name; }
        public void setName(final String name) { this.name = name + "[setters]"; }
      }
      """,
      true
    );

    final var holder = generated.resolve("demo/LateBuiltFieldOptics.java");
    assertTrue(Files.exists(holder), () -> "no holder generated under " + generated);
    final var text = Files.readString(holder);
    assertTrue(text.contains("LateBuilt.builder()"), () -> "construct() should call the builder; saw " + text);
    assertFalse(text.contains("c.setName("), () -> "and not the setter; saw " + text);
  }
}
