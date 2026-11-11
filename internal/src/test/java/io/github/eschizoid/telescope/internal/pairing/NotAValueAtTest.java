package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pins how a refused value of a container's element is told where that element is. */
class NotAValueAtTest {

  @Test
  @DisplayName("an element at an index of a list is named by the property and the index")
  void anElementOfAListIsNamedByItsIndex() {
    final var refusal = PairingMessages.notAValue("a[*]", "x", "an Integer");

    assertEquals(
      "'a[1]' got \"x\", not an Integer",
      PairingMessages.notAValueAt(refusal, "a", PairingMessages.elementPosition(1))
    );
  }

  @Test
  @DisplayName("the position of an element within an element follows the position of the outer one")
  void anElementOfAnElementFollowsItsContainersPosition() {
    final var inner = PairingMessages.notAValueAt(
      PairingMessages.notAValue("a[*][*]", "x", "an Integer"),
      "a[*]",
      PairingMessages.elementPosition(0)
    );

    assertEquals(
      "'a[k][0]' got \"x\", not an Integer",
      PairingMessages.notAValueAt(inner, "a", PairingMessages.elementPosition("k"))
    );
  }

  @Test
  @DisplayName("a message that is no refusal of the property's value is left alone")
  void anotherMessageIsNotLocated() {
    assertNull(PairingMessages.notAValueAt("'ab' got \"x\", not an Integer", "a", "[1]"));
    assertNull(PairingMessages.notAValueAt("something else", "a", "[1]"));
    assertNull(PairingMessages.notAValueAt(null, "a", "[1]"));
  }

  @Test
  @DisplayName("the name a refusal opens with is the property's, an element named by its container")
  void theNameIsTheProperty() {
    assertEquals("'total", PairingMessages.notAValueName("total"));
    assertEquals("'a", PairingMessages.notAValueName("a[*][*]"));
  }
}
