package io.github.eschizoid.telescope.codegen.lombok;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.FlagUser;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.FlagUserTelescope;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.KindValue;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.KindValueTelescope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Names generated navigators directly, from the compilation that generates them. This class names
 * them through single-type imports from the fixtures' package, and javac resolves no single-type
 * import in a source entered before the final round against a file written in that round, so this
 * class fails to compile unless each fixture's navigator is emitted before then, as soon as its
 * bean is ready.
 */
class LombokSameCompilationTest {

  @Test
  @DisplayName("a @Data bean with an isX boolean is navigable from the same compilation")
  void booleanIsPrefixedFieldDoesNotHoldTheNavigatorBack() {
    final var user = new FlagUser();
    user.setId("a");
    user.setActive(true);

    final var written = FlagUserTelescope.of().id().set(user, "b");

    assertEquals("b", written.getId());
    assertTrue(written.isActive(), "the boolean is carried through the setter Lombok names setActive");
  }

  @Test
  @DisplayName("a @Value @Builder with an initialised field is navigable, and keeps that field")
  void initialisedFinalDoesNotHoldTheNavigatorBack() {
    final var value = KindValue.builder().name("a").build();

    final var generated = KindValueTelescope.of().name().set(value, "b");
    final var runtime = Telescope.ofBean(KindValue.class).field(KindValue::getName).set(value, "b");

    assertEquals("b", generated.getName());
    assertEquals("K", generated.getKind());
    assertEquals(generated, runtime, "the runtime writer builds the same value");
  }
}
