package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A static method of one parameter as a function: called with the value handed in, a primitive
 * unboxed on the way in and boxed on the way out, and a value of another class refused as a cast
 * refuses it. {@code :internal:imageTest} runs the same tests over the closure an image builds.
 */
class StaticCallsTest {

  public static String shout(final CharSequence s) {
    return s.toString().toUpperCase();
  }

  public static long tenfold(final int n) {
    return n * 10L;
  }

  public String instance(final String s) {
    return s;
  }

  public static String pair(final String a, final String b) {
    return a + b;
  }

  @Test
  @DisplayName("a static method converts the value it is handed")
  void aStaticMethodConverts() throws Exception {
    final var f = StaticCalls.function(StaticCallsTest.class.getMethod("shout", CharSequence.class));
    assertEquals("AB", f.apply("ab"));
    assertEquals("CD", f.apply(new StringBuilder("cd")));
  }

  @Test
  @DisplayName("a primitive parameter is unboxed and a primitive result boxed")
  void primitivesAreUnboxedAndBoxed() throws Exception {
    final var f = StaticCalls.function(StaticCallsTest.class.getMethod("tenfold", int.class));
    assertEquals(30L, f.apply(3));
  }

  @Test
  @DisplayName("a value of another class than the parameter's is refused as a cast refuses it")
  void anotherClassIsRefused() throws Exception {
    final var f = StaticCalls.function(StaticCallsTest.class.getMethod("shout", CharSequence.class));
    assertThrows(ClassCastException.class, () -> f.apply(1));
  }

  @Test
  @DisplayName("a method of a class whose module does not open its package is refused with the remedy")
  void aClassNotOpenedIsRefusedWithTheRemedy() throws Exception {
    final var refused = assertThrows(IllegalStateException.class, () ->
      StaticCalls.function(Math.class.getMethod("abs", int.class))
    );
    assertTrue(refused.getMessage().startsWith("Cannot call java.lang.Math.abs. "), refused::getMessage);
    assertTrue(refused.getMessage().contains("java.lang"), refused::getMessage);
  }

  @Test
  @DisplayName("an instance method, or one taking two values, is refused")
  void otherMethodsAreRefused() throws Exception {
    assertThrows(IllegalArgumentException.class, () ->
      StaticCalls.function(StaticCallsTest.class.getMethod("instance", String.class))
    );
    assertThrows(IllegalArgumentException.class, () ->
      StaticCalls.function(StaticCallsTest.class.getMethod("pair", String.class, String.class))
    );
  }
}
