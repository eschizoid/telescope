package io.github.eschizoid.telescope.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FixedClass}, the checks a generated bridge calls inside the cast it writes: of a value
 * handed across into a property that fixes its type variable, of a conversion's result written
 * through the member its property's type chooses, and of a value read for a conversion that takes a
 * class narrower than its property's type variable.
 */
class FixedClassTest {

  /** A class whose property the checks are named after. */
  static final class Owner {}

  @Test
  @DisplayName("a value of the class fixed, or null, is handed back as it is")
  void aFittingValueIsHandedBack() {
    final var value = "a";
    assertSame(value, FixedClass.checked(value, String.class, Owner.class, "first"));
    assertNull(FixedClass.checked(null, String.class, Owner.class, "first"));
  }

  @Test
  @DisplayName("a value of another class is refused naming the property, the class found and the class fixed")
  void aValueOfAnotherClassIsRefused() {
    final var refused = assertThrows(ClassCastException.class, () ->
      FixedClass.checked(42, String.class, Owner.class, "first")
    );
    assertEquals(
      PairingMessages.fixedClassMismatch(Owner.class.getTypeName(), "first", "java.lang.Integer", "java.lang.String"),
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("a conversion's result of the class the member takes, or null, is handed back as it is")
  void aFittingResultIsHandedBack() {
    final var value = "a";
    assertSame(value, FixedClass.result(value, String.class, Owner.class, "value"));
    assertNull(FixedClass.result(null, String.class, Owner.class, "value"));
  }

  @Test
  @DisplayName("a conversion's result of another class is refused naming the property and both classes")
  void aResultOfAnotherClassIsRefused() {
    final var refused = assertThrows(ClassCastException.class, () ->
      FixedClass.result(42, String.class, Owner.class, "value")
    );
    assertEquals(
      PairingMessages.resultClassMismatch(Owner.class.getTypeName(), "value", "java.lang.Integer", "java.lang.String"),
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("a value read of the class a conversion takes, or null, is handed back, and another is refused by name")
  void aValueReadIsCheckedAgainstTheConversion() {
    final var value = "a";
    assertSame(value, FixedClass.argument(value, String.class, Owner.class, "value"));
    assertNull(FixedClass.argument(null, String.class, Owner.class, "value"));
    final var refused = assertThrows(ClassCastException.class, () ->
      FixedClass.argument(7L, Integer.class, Owner.class, "value")
    );
    assertEquals(
      PairingMessages.argumentClassMismatch(Owner.class.getTypeName(), "value", "java.lang.Long", "java.lang.Integer"),
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("a null result for a primitive argument takes its default, and any other is checked")
  void aNullResultTakesTheFallback() {
    assertEquals(0, FixedClass.resultOr(null, Integer.class, Owner.class, "value", 0));
    assertEquals(5, FixedClass.resultOr(5, Integer.class, Owner.class, "value", 0));
    final var refused = assertThrows(ClassCastException.class, () ->
      FixedClass.resultOr(1.5, Integer.class, Owner.class, "value", 0)
    );
    assertEquals(
      PairingMessages.resultClassMismatch(Owner.class.getTypeName(), "value", "java.lang.Double", "java.lang.Integer"),
      refused.getMessage()
    );
  }
}
