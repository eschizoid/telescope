package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The text of the refusal of a via mapper whose result the property it writes never takes. The
 * runtime mapper and the generated bridge's processor both build it and are compared by equality,
 * so it names the pair, the side, the property, both classes and the fix in exactly these words.
 */
class ViaResultMessagesTest {

  @Test
  @DisplayName("a forward result the target's property never takes names the target side and both classes")
  void forwardResultNeverFits() {
    assertEquals(
      "Deep map Src → Tgt: via row writes its mapper's result, a OtherDto, into the target's 'child', which" +
        " takes ChDto, and no OtherDto is a ChDto. Give the row a mapper whose result is of the class the" +
        " property takes.",
      PairingMessages.viaResultNeverFits("Src", "Tgt", "target", "child", "OtherDto", "ChDto")
    );
  }

  @Test
  @DisplayName("a backward result the source's property never takes names the source side")
  void backwardResultNeverFits() {
    assertEquals(
      "Deep map Src → Tgt: via row writes its mapper's result, a Other, into the source's 'child', which" +
        " takes Ch, and no Other is a Ch. Give the row a mapper whose result is of the class the property" +
        " takes.",
      PairingMessages.viaResultNeverFits("Src", "Tgt", "source", "child", "Other", "Ch")
    );
  }
}
