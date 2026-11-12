package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.MergeStep.auto;
import static io.github.eschizoid.telescope.mapping.MergeStep.from;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A merge {@code from(...)} row reads its source through an accessor of the source's class, which
 * names the source the merge looks up; any other reader names no source, and the merge is refused
 * when it is built rather than failing every forward.
 */
class MergeReaderTest {

  record Customer(String id, String name) {}

  record Profile(String id, String name) {}

  static final class Names {

    static final Names INSTANCE = new Names();

    static String nameOf(final Customer c) {
      return c.name();
    }

    String label(final Customer c) {
      return c.name();
    }
  }

  @Test
  @DisplayName("a static method taking the source is refused when the merge is built, naming the row")
  void aStaticReaderIsRefused() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      Telescope.merge(Profile.class, from(Names::nameOf, Profile::name), auto(Customer.class))
    );
    assertEquals(PairingMessages.mergeReaderNotAnAccessor(0, "Names::nameOf"), refused.getMessage());
  }

  @Test
  @DisplayName("a method of another object taking the source is refused when the merge is built, naming the row")
  void aBoundReaderIsRefused() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      Telescope.merge(Profile.class, auto(Customer.class), from(Names.INSTANCE::label, Profile::name))
    );
    assertEquals(PairingMessages.mergeReaderNotAnAccessor(1, "Names::label"), refused.getMessage());
  }

  @Test
  @DisplayName("an accessor of the source builds the merge, which reads the source it names")
  void anAccessorReads() {
    final var merge = Telescope.merge(Profile.class, from(Customer::id, Profile::id), from(Customer::name, Profile::name));
    assertEquals(new Profile("c", "Ada"), merge.forward(Sources.of(new Customer("c", "Ada"))));
  }
}
