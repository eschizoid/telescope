package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A merge {@code from(...)} row reads its source only through an accessor of the source's class.
 */
class MergeReadersTest {

  @Test
  @DisplayName("an accessor of the source is taken")
  void anAccessorIsTaken() {
    assertNull(MergeReaders.refusal(0, "Customer", "name", true));
  }

  @Test
  @DisplayName("any other reader is refused by its row's place and its reference, pointing at an accessor")
  void anyOtherReaderIsRefused() {
    assertEquals(
      "Telescope.merge: step at index 2 reads through H::nameOf, which is not an accessor of a source: a from(...)" +
        " row reads its source through an instance method of the source's class taking no argument, the class the" +
        " merge looks the source up by, and a static method or a method of another object names no source. Read" +
        " it through an accessor of a source class, as from(Customer::name, Profile::name); a value computed from" +
        " a source belongs in an accessor of that source, or of a holder record given as one more source.",
      MergeReaders.refusal(2, "H", "nameOf", false)
    );
  }
}
