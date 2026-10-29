package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The text of the refusals of a write hint the runtime mapper and the generated bridge's processor
 * both refuse. Each path builds it from here and the two are compared by equality, so it names the
 * hint, the class it is keyed to and the limit in exactly these words.
 */
class WriteHintMessagesTest {

  @Test
  @DisplayName("a second writeBeans default names the hint and the one default a call may give")
  void duplicateDefault() {
    assertEquals(
      "Duplicate writeBeans(...) default — at most one default write strategy per Telescope.map(...) call.",
      PairingMessages.duplicateWriteBeansDefault()
    );
  }

  @Test
  @DisplayName("a second writeBean hint for one class names the class by its binary name")
  void duplicateHint() {
    assertEquals(
      "Duplicate writeBean hint for a.Outer$Bean. Each target class may declare at most one writeBean(...) row" +
        " per Telescope.map(...) call.",
      PairingMessages.duplicateWriteBeanHint("a.Outer$Bean")
    );
  }

  @Test
  @DisplayName("a writeBean hint keyed to a record names the record and the fix")
  void hintOnARecord() {
    assertEquals(
      "writeBean hint targets a record class (a.Point). Records are always reconstructed via the canonical" +
        " constructor; the hint cannot apply. Remove the writeBean(...) row, or move it to the bean side of the" +
        " mapping.",
      PairingMessages.writeBeanTargetsRecord("a.Point")
    );
  }
}
