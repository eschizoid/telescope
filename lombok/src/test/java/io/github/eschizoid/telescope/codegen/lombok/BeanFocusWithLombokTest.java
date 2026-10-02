package io.github.eschizoid.telescope.codegen.lombok;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.DataUser;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.DataUserTelescope;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.FocusedBuiltValue;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.FocusedBuiltValueTelescope;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.FocusedChild;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.FocusedChildTelescope;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.FocusedDataOuter;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.FocusedDataOuterTelescope;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.FocusedDataUser;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.FocusedDataUserTelescope;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.FocusedPlainOuter;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.FocusedPlainOuterTelescope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A class carrying {@code @BeanFocus} and a Lombok bean annotation gets one navigator and one
 * holder. This class names the navigators directly, so it compiles only when exactly one processor
 * wrote each and wrote it in time for the compilation that generates it.
 */
class BeanFocusWithLombokTest {

  @Test
  @DisplayName("@BeanFocus @Data: one navigator, written through Lombok's setters as at runtime")
  void beanFocusWithData() {
    final var user = new FocusedDataUser();
    user.setName("a");
    user.setAge(3);

    final var generated = FocusedDataUserTelescope.of().name().set(user, "b");
    final var runtime = Telescope.ofBean(FocusedDataUser.class).field(FocusedDataUser::getName).set(user, "b");

    assertEquals("b", generated.getName());
    assertEquals(3, generated.getAge());
    assertEquals(runtime, generated);
  }

  @Test
  @DisplayName("@BeanFocus @Value @Builder: one navigator, built through Lombok's builder as at runtime")
  void beanFocusWithValueBuilder() throws ReflectiveOperationException {
    final var value = FocusedBuiltValue.builder().name("a").age(3).build();

    final var generated = FocusedBuiltValueTelescope.of().name().set(value, "b");
    final var runtime = Telescope.ofBean(FocusedBuiltValue.class).field(FocusedBuiltValue::getName).set(value, "b");

    assertEquals(FocusedBuiltValue.builder().name("b").age(3).build(), generated);
    assertEquals(runtime, generated);
    final var holder = Class.forName(
      "io.github.eschizoid.telescope.codegen.lombok.fixtures.FocusedBuiltValueFieldOptics"
    );
    final Function<String, Object> values = n -> n.equals("name") ? "c" : 4;
    assertEquals(
      FocusedBuiltValue.builder().name("c").age(4).build(),
      holder.getMethod("construct", Function.class).invoke(null, values)
    );
  }

  @Test
  @DisplayName("both kinds of navigator descend into a @BeanFocus child and into a @Data child")
  void navigatorsDescendIntoBothKindsOfChild() {
    // The typed locals are the assertion: a hop that ended at the child would return a terminal
    // Telescope and this would not compile.
    final FocusedChildTelescope<FocusedDataOuter> dataToChild = FocusedDataOuterTelescope.of().child();
    final DataUserTelescope<FocusedDataOuter> dataToUser = FocusedDataOuterTelescope.of().user();
    final FocusedChildTelescope<FocusedPlainOuter> plainToChild = FocusedPlainOuterTelescope.of().child();
    final DataUserTelescope<FocusedPlainOuter> plainToUser = FocusedPlainOuterTelescope.of().user();

    final var child = new FocusedChild();
    child.setName("a");
    final var outer = new FocusedDataOuter();
    outer.setChild(child);
    outer.setUser(new DataUser("u", "e"));
    assertEquals("b", dataToChild.name().set(outer, "b").getChild().getName());
    assertEquals("f", dataToUser.email().set(outer, "f").getUser().getEmail());

    final var plain = new FocusedPlainOuter();
    plain.setChild(child);
    plain.setUser(new DataUser("u", "e"));
    assertEquals("c", plainToChild.name().set(plain, "c").getChild().getName());
    assertEquals("g", plainToUser.email().set(plain, "g").getUser().getEmail());
  }

  @Test
  @DisplayName("with an explicit processor list that leaves telescope-lombok out, @BeanFocus still writes it")
  void explicitProcessorListWithoutTheLombokProcessor(@TempDir final Path dir) throws IOException {
    final var generated = LombokLastCompiler.compile(
      dir,
      "Listed",
      """
      package demo;
      import io.github.eschizoid.telescope.annotations.BeanFocus;
      import lombok.Builder;
      import lombok.NoArgsConstructor;
      import lombok.AllArgsConstructor;
      @BeanFocus
      @Builder
      @NoArgsConstructor
      @AllArgsConstructor
      public class Listed {
        private String name;
        public String getName() { return name; }
        public void setName(final String name) { this.name = name + "[setters]"; }
      }
      """,
      true,
      List.of(
        "-processor",
        "lombok.launch.AnnotationProcessorHider$AnnotationProcessor," +
          "lombok.launch.AnnotationProcessorHider$ClaimingProcessor," +
          "io.github.eschizoid.telescope.codegen.BeanFocusProcessor"
      )
    );

    assertTrue(Files.exists(generated.resolve("demo/ListedTelescope.java")), "BeanFocusProcessor writes the navigator");
    final var holder = Files.readString(generated.resolve("demo/ListedFieldOptics.java"));
    assertTrue(holder.contains("Listed.builder()"), () -> "and the holder, through the builder; saw " + holder);
  }

  @Test
  @DisplayName("with Lombok last on the processor path, the combination still compiles to one navigator")
  void lombokLastOnTheProcessorPath(@TempDir final Path dir) throws IOException {
    final var generated = LombokLastCompiler.compile(
      dir,
      "Both",
      """
      package demo;
      import io.github.eschizoid.telescope.annotations.BeanFocus;
      import lombok.AllArgsConstructor;
      import lombok.Builder;
      import lombok.NoArgsConstructor;
      @BeanFocus
      @Builder
      @NoArgsConstructor
      @AllArgsConstructor
      public class Both {
        private String name;
        public String getName() { return name; }
        public void setName(final String name) { this.name = name + "[setters]"; }
      }
      """,
      true
    );

    final var holder = Files.readString(generated.resolve("demo/BothFieldOptics.java"));
    assertTrue(holder.contains("Both.builder()"), () -> "the holder should build through the builder; saw " + holder);
    assertFalse(holder.contains("c.setName("), () -> holder);
    assertTrue(Files.exists(generated.resolve("demo/BothTelescope.java")));
  }
}
