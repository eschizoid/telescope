package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The words both paths refuse a container write and an adders build in. The runtime mapper, the
 * generated bridge and the verifier each read them from here, so the text is pinned once.
 */
class CollectionWriteMessagesTest {

  @Test
  @DisplayName("a container property nothing can write names the container it holds and what would fix it")
  void anUnchangeableContainerWithNoSetter() {
    assertEquals(
      "Deep map Src → Tgt: into writes 'lines' of the Order it is given, which holds a java.util.ListN that cannot" +
        " be changed, and Order has no setter for 'lines' to give it a new one, so into cannot update it. Give" +
        " Order a setter for 'lines' or a modifiable container, or call forward(source) for a new Order.",
      PairingMessages.intoUnwritable("Src", "Tgt", "Order", "lines", "java.util.ListN")
    );
  }

  @Test
  @DisplayName("a container property with no container held and no setter says it holds none")
  void noContainerAndNoSetter() {
    assertEquals(
      "Deep map Src → Tgt: into writes 'lines' of the Order it is given, which holds no container for it, and" +
        " Order has no setter for 'lines' to give it a new one, so into cannot update it. Give Order a setter for" +
        " 'lines' or a modifiable container, or call forward(source) for a new Order.",
      PairingMessages.intoUnwritable("Src", "Tgt", "Order", "lines", null)
    );
  }

  @Test
  @DisplayName("a missing adder lists every name looked for and the element it has to take")
  void aMissingAdderListsTheNames() {
    assertEquals(
      "writeStrategy = ADDERS on Order fills its collection 'lines' through an adder, one element at a time, and" +
        " Order has no public method addLine, addLin or addLines taking a Line. Give Order an adder addLine(Line)," +
        " or name another write strategy for it.",
      PairingMessages.adderMissing("Order", "lines", "Line", List.of("addLine", "addLin", "addLines"))
    );
  }

  @Test
  @DisplayName("a missing adder of one name and no element class names an element and Object")
  void aMissingAdderOfOneNameAndNoElement() {
    assertEquals(
      "writeStrategy = ADDERS on Bag fills its collection 'data' through an adder, one element at a time, and Bag" +
        " has no public method addData taking an element. Give Bag an adder addData(Object), or name another" +
        " write strategy for it.",
      PairingMessages.adderMissing("Bag", "data", null, List.of("addData"))
    );
  }

  @Test
  @DisplayName("an adders build with no no-argument constructor says what it needs")
  void addersNeedANoArgumentConstructor() {
    assertEquals(
      "writeStrategy = ADDERS on Order builds it through a no-argument constructor before its setters and adders" +
        " write it, and Order has none that can be called. Give Order a no-argument constructor, or name another" +
        " write strategy for it.",
      PairingMessages.addersNeedNoArgConstructor("Order")
    );
  }
}
