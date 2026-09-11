package io.github.eschizoid.telescope.patchfix;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.mapping.Mapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The generated bridge's {@code patch} and the runtime mapper's {@code patch} must agree, and both
 * must honour the documented contract: a non-null partial slot is written whole (nested components
 * are replaced, not sub-merged), a null partial slot keeps the base value, and a {@code @Default}
 * coalesce fires in the forward direction only. Each test asserts the absolute expected value AND
 * codegen/runtime equality, so two implementations agreeing on a wrong answer cannot pass.
 */
class PatchEmissionParityTest {

  @Test
  @DisplayName("a non-null partial nested value lands even when the base's nested slot is null")
  void patchWritesPartialNestedValueOntoNullBaseSlot() {
    final var base = new NestSrcRec("id1", null);
    final var partial = new NestDstRec(null, new NInB("y"));

    final var codegen = NestSrcRecBridge.patch(base, partial);
    final var runtime = Telescope.mapper(NestSrcRec.class, NestDstRec.class).patch(base, partial);

    assertEquals(new NestSrcRec("id1", new NInA("y")), codegen);
    assertEquals(runtime, codegen);
  }

  @Test
  @DisplayName("a non-null partial nested value replaces the base's nested value whole")
  void patchWritesNestedComponentsWhole() {
    final var base = new NestSrcRec("id1", new NInA("x"));
    final var partial = new NestDstRec(null, new NInB(null));

    final var codegen = NestSrcRecBridge.patch(base, partial);
    final var runtime = Telescope.mapper(NestSrcRec.class, NestDstRec.class).patch(base, partial);

    assertEquals(new NestSrcRec("id1", new NInA(null)), codegen);
    assertEquals(runtime, codegen);
  }

  @Test
  @DisplayName("a @Default never fires during patch: null in partial and base stays null")
  void patchDoesNotResurrectTheDefault() {
    final var base = new DefIntSrc(null, 1);
    final var partial = new DefIntDst(null, 2);

    final var codegen = DefIntSrcBridge.patch(base, partial);
    final var runtime = Telescope.mapper(
      DefIntSrc.class,
      DefIntDst.class,
      Mapping.toOrElse(DefIntSrc::count, DefIntDst::count, 42)
    ).patch(base, partial);

    assertEquals(new DefIntSrc(null, 2), codegen);
    assertEquals(runtime, codegen);
  }

  @Test
  @DisplayName("the @Default still coalesces in the forward direction")
  void forwardStillCoalescesTheDefault() {
    final var codegen = DefIntSrcBridge.forward(new DefIntSrc(null, 5));

    assertEquals(new DefIntDst(42, 5), codegen);
  }
}
