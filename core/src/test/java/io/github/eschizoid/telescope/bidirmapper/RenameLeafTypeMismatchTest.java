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
 * LUB, so {@code to(Integer-getter, String-getter)} compiles) must be rejected at build with a
 * precise, actionable error — not pass the value through identity into a mismatched setter, which
 * ClassCastExceptions at runtime. The same root surfaces through the reflective writer AND the
 * {@code @BeanFocus} FieldOptics construct; one build-time check covers both.
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
  }

  @Test
  @DisplayName("the documented fix — 4-arg to(src, tgt, forward, backward) — maps the renamed field correctly")
  void fourArgTransformIsTheFix() {
    final var mapper = Telescope.mapperBuilder(DocSrc.class, DocTgt.class)
      .add(
        Mapping.to(
          DocSrc::getDocUpdateAttempts,
          DocTgt::getNumberOfAttempts,
          i -> i == null ? null : String.valueOf(i),
          s -> s == null ? null : Integer.parseInt(s)
        )
      )
      .build();

    assertEquals("5", mapper.forward(new DocSrc(5)).getNumberOfAttempts());
  }
}
