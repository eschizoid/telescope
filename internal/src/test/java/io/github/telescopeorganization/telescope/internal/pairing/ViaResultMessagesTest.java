package io.github.telescopeorganization.telescope.internal.pairing;

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
      "Deep map Src → Tgt: via row writes its mapper's result, an OtherDto, into the target's 'child', which" +
        " takes ChDto, and no OtherDto is a ChDto. Give the row a mapper whose result is of the class the" +
        " property takes.",
      PairingMessages.viaResultNeverFits("Src", "Tgt", "target", "child", "OtherDto", "ChDto")
    );
  }

  @Test
  @DisplayName("a backward result the source's property never takes names the source side")
  void backwardResultNeverFits() {
    assertEquals(
      "Deep map Src → Tgt: via row writes its mapper's result, an Other, into the source's 'child', which" +
        " takes Ch, and no Other is a Ch. Give the row a mapper whose result is of the class the property" +
        " takes.",
      PairingMessages.viaResultNeverFits("Src", "Tgt", "source", "child", "Other", "Ch")
    );
  }

  @Test
  @DisplayName("a result the member does not take names what the method returns and what the member takes")
  void resultNotTaken() {
    assertEquals(
      "returns java.util.List<java.lang.String>, which the member Tgt writes child through does not take: it takes" +
        " java.util.List<java.lang.Integer>",
      PairingMessages.viaResultNotTaken(
        "java.util.List<java.lang.String>",
        "Tgt",
        "child",
        "java.util.List<java.lang.Integer>"
      )
    );
  }

  @Test
  @DisplayName("a generic result no instantiation fits names the declared result and what the member takes")
  void resultNoInstantiation() {
    assertEquals(
      "returns java.util.List<T>, which no instantiation of its type variables lets the member Tgt writes child" +
        " through take: it takes java.util.List<java.lang.Integer>",
      PairingMessages.viaResultNoInstantiation("java.util.List<T>", "Tgt", "child", "java.util.List<java.lang.Integer>")
    );
  }

  @Test
  @DisplayName("a result a raw argument erases names the raw class, the erasure and what the member takes")
  void resultErased() {
    assertEquals(
      "returns T, which a call handed a raw java.util.List erases to java.lang.Object, and the member Tgt writes" +
        " child through does not take that: it takes java.lang.String",
      PairingMessages.viaResultErased("T", "java.util.List", "java.lang.Object", "Tgt", "child", "java.lang.String")
    );
  }
}
