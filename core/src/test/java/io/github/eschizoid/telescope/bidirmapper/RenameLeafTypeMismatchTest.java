package io.github.eschizoid.telescope.bidirmapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.mapping.Mapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A 2-arg {@code to(src, tgt)} rename whose leaf types differ (javac infers the shared type as the
 * LUB, so {@code to(Integer-getter, Boolean-getter)} compiles) and that no built-in conversion
 * takes must be rejected at build with a precise, actionable error — not pass the value through
 * identity into a mismatched setter, which ClassCastExceptions at runtime. The same root surfaces
 * through the reflective writer AND the {@code @BeanFocus} FieldOptics construct; one build-time
 * check covers both. A pair a built-in conversion takes converts through the same row.
 */
class RenameLeafTypeMismatchTest {

  @Test
  @DisplayName("2-arg to() rename with mismatched leaf types fails at BUILD with a pointer to the 4-arg form")
  void mismatchedSameTypedToRejectedAtBuild() {
    final var ex = assertThrows(IllegalStateException.class, () ->
      Telescope.mapperBuilder(DocSrc.class, DocTgt.class)
        .add(Mapping.to(DocSrc::getDocUpdateAttempts, DocTgt::getNumberOfAttempts))
        .build()
    );
    assertTrue(ex.getMessage().contains("docUpdateAttempts"), ex.getMessage());
    assertTrue(ex.getMessage().contains("numberOfAttempts"), ex.getMessage());
    assertTrue(ex.getMessage().contains("to(src, tgt, forward, backward)"), ex.getMessage());
  }

  @Test
  @DisplayName("@BeanFocus construct path hits the SAME build-time rejection (one root, two construct paths)")
  void mismatchedSameTypedToRejectedAtBuildBeanFocus() {
    final var ex = assertThrows(IllegalStateException.class, () ->
      Telescope.mapperBuilder(BfDocSrc.class, BfDocTgt.class)
        .add(Mapping.to(BfDocSrc::getDocUpdateAttempts, BfDocTgt::getNumberOfAttempts))
        .build()
    );
    assertTrue(ex.getMessage().contains("incompatible source/target shapes"), ex.getMessage());
    assertTrue(ex.getMessage().contains("docUpdateAttempts"), ex.getMessage());
    assertTrue(ex.getMessage().contains("numberOfAttempts"), ex.getMessage());
    assertTrue(ex.getMessage().contains("to(src, tgt, forward, backward)"), ex.getMessage());
  }

  @Test
  @DisplayName("the documented fix — 4-arg to(src, tgt, forward, backward) — round-trips the renamed field both ways")
  void fourArgTransformIsTheFix() {
    final var mapper = Telescope.mapperBuilder(DocSrc.class, DocTgt.class)
      .add(
        Mapping.to(
          DocSrc::getDocUpdateAttempts,
          DocTgt::getNumberOfAttempts,
          i -> i == null ? null : i > 0,
          b -> b == null ? null : b ? 1 : 0
        )
      )
      .build();

    final var forward = mapper.forward(new DocSrc(5));
    assertEquals(Boolean.TRUE, forward.getNumberOfAttempts());
    assertEquals(Integer.valueOf(1), mapper.backward(forward).getDocUpdateAttempts());
  }

  record Attempts(Integer docUpdateAttempts) {}

  record AttemptsText(String numberOfAttempts) {}

  @Test
  @DisplayName("a 2-arg to() rename between a number and a String converts through the built-in conversion")
  void aRenameABuiltInTakesConverts() {
    final var mapper = Telescope.mapper(
      Attempts.class,
      AttemptsText.class,
      Mapping.to(Attempts::docUpdateAttempts, AttemptsText::numberOfAttempts)
    );
    assertEquals(new AttemptsText("5"), mapper.forward(new Attempts(5)));
    assertEquals(new Attempts(5), mapper.backward(new AttemptsText("5")));
  }
}
