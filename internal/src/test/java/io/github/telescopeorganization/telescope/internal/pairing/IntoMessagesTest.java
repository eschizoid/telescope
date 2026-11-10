package io.github.telescopeorganization.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The text of the refusals a mapper's {@code into} gives, which the runtime mapper and the mapper a
 * {@code @Bridge} field declares both raise in these words.
 */
class IntoMessagesTest {

  @Test
  @DisplayName("into handed a bean with no setter for a property it writes names the property and both ways out")
  void intoWithoutASetter() {
    assertEquals(
      "Deep map Dto → Bean: into writes 'v' into the Outer.Bean it is given through a setter, and Outer.Bean has" +
        " no setter that takes it, so into cannot update it in place. Give Outer.Bean a setter for 'v', or call" +
        " forward(source) for a new Outer.Bean.",
      PairingMessages.intoPropertyUnwritable("Dto", "Bean", "Outer.Bean", "v")
    );
  }

  @Test
  @DisplayName("into handed a record names the record and both ways out")
  void intoARecord() {
    assertEquals(
      "Deep map Dto → Rec: into updates the object it is given in place, and Outer.Rec is a record, so it cannot:" +
        " records are immutable. Call forward(source) for a new Outer.Rec, or make Outer.Rec a bean with a setter" +
        " for each property the mapper writes.",
      PairingMessages.intoRecordTarget("Dto", "Rec", "Outer.Rec")
    );
  }
}
