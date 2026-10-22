package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.optics.Iso;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link ResultCheck}, the slot that checks a conversion's result against the class the member it
 * is written through takes: run as an {@link Iso}, as the array leaf runs it, and fused into the
 * composed handle {@link MhIso} builds, as the runtime mapper builds it.
 */
class ResultCheckTest {

  public static final class Src {

    private String value;

    public Src() {}

    public String getValue() {
      return value;
    }

    public void setValue(final String value) {
      this.value = value;
    }
  }

  public static final class StringTgt {

    private String value;

    public StringTgt() {}

    public String getValue() {
      return value;
    }

    public void setValue(final String value) {
      this.value = value;
    }
  }

  public static final class IntTgt {

    private int value = 7;

    public IntTgt() {}

    public int getValue() {
      return value;
    }

    public void setValue(final int value) {
      this.value = value;
    }
  }

  public record IntRec(int value) {}

  /** A conversion that hands back {@code result} whatever it is given. */
  private static Iso<Object, Object> giving(final Function<Object, Object> result) {
    return Iso.of(result, v -> v);
  }

  private static ResultCheck checking(
    final Iso<Object, Object> conversion,
    final Class<?> expected,
    final Class<?> owner,
    final Object targetNull
  ) {
    return new ResultCheck(
      conversion,
      expected,
      owner,
      "value",
      targetNull,
      null,
      Src.class,
      "value",
      null,
      null,
      null
    );
  }

  @Nested
  @DisplayName("as a slot of the array leaf")
  class AsAnIso {

    @Test
    @DisplayName("a result of the class the member takes is handed on as it is")
    void aFittingResultPasses() {
      final var check = checking(giving(v -> v + "!"), String.class, StringTgt.class, null);
      assertEquals("a!", check.to("a"));
    }

    @Test
    @DisplayName("a result of another class is refused naming the property, the class found and the class taken")
    void aWrongClassIsRefusedByName() {
      final var check = checking(giving(v -> 42), String.class, StringTgt.class, null);
      final var refused = assertThrows(ClassCastException.class, () -> check.to("a"));
      assertEquals(
        PairingMessages.resultClassMismatch(
          StringTgt.class.getTypeName(),
          "value",
          "java.lang.Integer",
          "java.lang.String"
        ),
        refused.getMessage()
      );
    }

    @Test
    @DisplayName("a null result takes what the member takes in place of null")
    void aNullResultTakesTheMembersNullValue() {
      assertEquals(0, checking(giving(v -> null), Integer.class, IntRec.class, 0).to("a"));
      assertNull(checking(giving(v -> null), Integer.class, IntTgt.class, null).to("a"));
    }

    @Test
    @DisplayName("a null value passes the check, which only a non-null value of another class fails")
    void aNullValuePassesTheCheck() {
      assertNull(ResultCheck.checked(null, String.class, StringTgt.class, "value"));
    }

    @Test
    @DisplayName("a side with nothing to check hands the result on unchecked, in either direction")
    void aSideWithNothingToCheckIsNotChecked() {
      final var check = new ResultCheck(
        giving(v -> 42),
        null,
        StringTgt.class,
        "value",
        null,
        null,
        Src.class,
        "value",
        null,
        null,
        null
      );
      assertEquals(42, check.to("a"));
      assertEquals("b", check.from("b"));
    }

    @Test
    @DisplayName("a backward result is checked against the source's member")
    void aBackwardResultIsChecked() {
      final var check = new ResultCheck(
        Iso.of(v -> v, v -> 42),
        null,
        StringTgt.class,
        "value",
        null,
        String.class,
        Src.class,
        "value",
        null,
        null,
        null
      );
      final var refused = assertThrows(ClassCastException.class, () -> check.from("a"));
      assertEquals(
        PairingMessages.resultClassMismatch(Src.class.getTypeName(), "value", "java.lang.Integer", "java.lang.String"),
        refused.getMessage()
      );
    }
  }

  /**
   * A slot whose conversion is {@code conversion}, checking the value it reads forward against
   * {@code fromSource} and backward against {@code fromTarget}, and no result.
   */
  private static ResultCheck reading(
    final Iso<Object, Object> conversion,
    final Class<?> fromSource,
    final Class<?> fromTarget
  ) {
    return new ResultCheck(
      conversion,
      null,
      StringTgt.class,
      "value",
      null,
      null,
      Src.class,
      "value",
      null,
      fromSource,
      fromTarget
    );
  }

  @Nested
  @DisplayName("checking the value a conversion reads")
  class ReadChecks {

    @Test
    @DisplayName(
      "a value of the class the forward conversion takes is converted, and one of another class is refused by name"
    )
    void aForwardReadIsChecked() {
      final var check = reading(giving(v -> v + "!"), String.class, null);
      assertEquals("a!", check.to("a"));
      final var refused = assertThrows(ClassCastException.class, () -> check.to(42));
      assertEquals(
        PairingMessages.argumentClassMismatch(
          Src.class.getTypeName(),
          "value",
          "java.lang.Integer",
          "java.lang.String"
        ),
        refused.getMessage()
      );
      assertEquals(42, check.from(42), "a backward read with nothing to check is not checked");
    }

    @Test
    @DisplayName("a backward read is checked against the class the backward conversion takes, naming the target")
    void aBackwardReadIsChecked() {
      final var check = reading(Iso.of(v -> v, v -> "<" + v + ">"), null, Integer.class);
      assertEquals("<5>", check.from(5));
      final var refused = assertThrows(ClassCastException.class, () -> check.from("b"));
      assertEquals(
        PairingMessages.argumentClassMismatch(
          StringTgt.class.getTypeName(),
          "value",
          "java.lang.String",
          "java.lang.Integer"
        ),
        refused.getMessage()
      );
    }

    @Test
    @DisplayName("a null value read passes the check")
    void aNullReadPasses() {
      assertNull(ResultCheck.argument(null, String.class, Src.class, "value"));
      assertEquals("null!", reading(giving(v -> v + "!"), Integer.class, null).to(null));
    }
  }

  @Nested
  @DisplayName("fused into the composed handle")
  class Fused {

    /**
     * The composed conversion of {@code Src} to {@code target}, with the one slot {@code check} in
     * both directions; the backward direction composes where a bean is rebuilt through its setters.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private <T> Iso<Src, T> composed(final Class<T> target, final ResultCheck check) {
      final var targetWriter = target.isRecord() ? null : Reflective.BEANS.writerFor(target);
      final var sourceWriter = Reflective.BEANS.writerFor(Src.class);
      assertTrue(MhIso.composesBuild(Src.class, target, targetWriter), "the forward direction composes");
      final Iso<Object, Object> identity = Iso.identity();
      return MhIso.pair(
        Src.class,
        target,
        new int[] { 0 },
        new Iso[] { check },
        new int[] { 0 },
        new Iso[] { check },
        identity,
        null,
        true,
        MhIso.composesBuild(target, Src.class, sourceWriter),
        targetWriter,
        sourceWriter
      );
    }

    private Src source(final String value) {
      final var src = new Src();
      src.setValue(value);
      return src;
    }

    @Test
    @DisplayName(
      "a result of the class the setter takes is written, and a direction with nothing to check is not checked"
    )
    void aFittingResultIsWritten() {
      final var iso = composed(StringTgt.class, checking(giving(v -> v + "!"), String.class, StringTgt.class, null));
      assertEquals("a!", iso.to(source("a")).getValue());
      final var back = new StringTgt();
      back.setValue("b");
      assertEquals("b", iso.from(back).getValue());
    }

    @Test
    @DisplayName("a result of another class is refused by name before the setter is reached")
    void aWrongClassIsRefusedByName() {
      final var iso = composed(StringTgt.class, checking(giving(v -> 42), String.class, StringTgt.class, null));
      final var refused = assertThrows(ClassCastException.class, () -> iso.to(source("a")));
      assertEquals(
        PairingMessages.resultClassMismatch(
          StringTgt.class.getTypeName(),
          "value",
          "java.lang.Integer",
          "java.lang.String"
        ),
        refused.getMessage()
      );
    }

    @Test
    @DisplayName("a value read is checked against the class the conversion takes before the conversion runs")
    void aValueReadIsCheckedOnTheComposedHandle() {
      final var fitting = composed(StringTgt.class, reading(giving(v -> v + "!"), CharSequence.class, null));
      assertEquals("a!", fitting.to(source("a")).getValue());
      final var refusing = composed(
        StringTgt.class,
        reading(
          giving(v -> {
            throw new AssertionError("the conversion ran on a value the check refuses");
          }),
          Integer.class,
          null
        )
      );
      final var refused = assertThrows(ClassCastException.class, () -> refusing.to(source("a")));
      assertEquals(
        PairingMessages.argumentClassMismatch(
          Src.class.getTypeName(),
          "value",
          "java.lang.String",
          "java.lang.Integer"
        ),
        refused.getMessage()
      );
    }

    @Test
    @DisplayName("a null result into a record's primitive component takes the default")
    void aNullIntoARecordComponentTakesTheDefault() {
      final var iso = composed(IntRec.class, checking(giving(v -> null), Integer.class, IntRec.class, 0));
      assertEquals(new IntRec(0), iso.to(source("a")));
    }

    @Test
    @DisplayName("a null result into a primitive setter skips the write")
    void aNullIntoAPrimitiveSetterIsSkipped() {
      final var iso = composed(IntTgt.class, checking(giving(v -> null), Integer.class, IntTgt.class, null));
      assertEquals(7, iso.to(source("a")).getValue());
    }

    @Test
    @DisplayName("a backward result is checked against the source's setter, and a forward with none is not")
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void aBackwardResultIsCheckedOnTheComposedHandle() {
      final var check = new ResultCheck(
        Iso.of(v -> 42, v -> 42),
        null,
        StringTgt.class,
        "value",
        null,
        String.class,
        Src.class,
        "value",
        null,
        null,
        null
      );
      final var targetWriter = Reflective.BEANS.writerFor(IntTgt.class);
      final var sourceWriter = Reflective.BEANS.writerFor(Src.class);
      assertTrue(MhIso.composesBuild(IntTgt.class, Src.class, sourceWriter), "the backward direction composes");
      final Iso<Object, Object> identity = Iso.identity();
      final Iso<Src, IntTgt> iso = MhIso.pair(
        Src.class,
        IntTgt.class,
        new int[] { 0 },
        new Iso[] { check },
        new int[] { 0 },
        new Iso[] { check },
        identity,
        null,
        true,
        true,
        targetWriter,
        sourceWriter
      );
      assertEquals(42, iso.to(source("a")).getValue());
      final var refused = assertThrows(ClassCastException.class, () -> iso.from(new IntTgt()));
      assertEquals(
        PairingMessages.resultClassMismatch(Src.class.getTypeName(), "value", "java.lang.Integer", "java.lang.String"),
        refused.getMessage()
      );
    }

    @Test
    @DisplayName("the slot keeps the conversion it wraps")
    void theSlotKeepsItsConversion() {
      final var conversion = giving(v -> v);
      assertSame(conversion, checking(conversion, String.class, StringTgt.class, null).conversion());
    }
  }
}
