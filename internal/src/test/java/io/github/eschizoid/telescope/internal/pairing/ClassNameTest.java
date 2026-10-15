package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The name a message gives the class of a value it was handed. */
class ClassNameTest {

  enum Plain {
    A,
  }

  enum Bodied {
    A {
      @Override
      public String toString() {
        return "a";
      }
    },
  }

  @Test
  @DisplayName("a named class and an enum constant without a body are named by their simple name")
  void namedClasses() {
    assertEquals("String", PairingMessages.className(String.class));
    assertEquals("Plain", PairingMessages.className(Plain.A.getClass()));
  }

  @Test
  @DisplayName("an enum constant with a body is named by its enum")
  void bodiedConstant() {
    assertEquals("Bodied", PairingMessages.className(Bodied.A.getClass()));
  }

  @Test
  @DisplayName("an anonymous class is named by its binary name")
  void anonymousClass() {
    final var anonymous = new Object() {};
    assertEquals(anonymous.getClass().getName(), PairingMessages.className(anonymous.getClass()));
  }

  @Test
  @DisplayName("an interface, which has no superclass, is named by its simple name")
  void interfaceType() {
    assertEquals("Runnable", PairingMessages.className(Runnable.class));
  }
}
