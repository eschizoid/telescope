package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SourceWritesTest {

  @Test
  @DisplayName("a mapper that converts both ways refuses a second target, naming both in row order")
  void twoWayRefusesASecondTarget() {
    final var writes = new SourceWrites("Src", "Tgt", true);
    assertNull(writes.write("n", "b"));
    assertEquals(PairingMessages.fanOutBothWays("Src", "Tgt", "n", "b", "a"), writes.write("n", "a"));
  }

  @Test
  @DisplayName("a forward-only mapper writes a property into as many targets as its rows name")
  void forwardOnlyTakesEveryTarget() {
    final var writes = new SourceWrites("Src", "Tgt", false);
    assertNull(writes.write("n", "a"));
    assertNull(writes.write("n", "b"));
    assertNull(writes.write("n", "n"));
  }

  @Test
  @DisplayName("distinct source properties each take a target of their own")
  void distinctSourcesAreIndependent() {
    final var writes = new SourceWrites("Src", "Tgt", true);
    assertNull(writes.write("n", "a"));
    assertNull(writes.write("m", "b"));
  }

  @Test
  @DisplayName("a drop and a write of one property are refused in either order, on either factory")
  void dropAndWriteRefusedInEitherOrder() {
    for (final var backward : new boolean[] { true, false }) {
      final var dropFirst = new SourceWrites("Src", "Tgt", backward);
      assertNull(dropFirst.drop("n"));
      assertEquals(PairingMessages.droppedAndWritten("Src", "Tgt", "n"), dropFirst.write("n", "n"));
      final var writeFirst = new SourceWrites("Src", "Tgt", backward);
      assertNull(writeFirst.write("n", "label"));
      assertEquals(PairingMessages.droppedAndWritten("Src", "Tgt", "n"), writeFirst.drop("n"));
    }
  }

  @Test
  @DisplayName("a drop of one property leaves another free to be written")
  void dropLeavesOthersFree() {
    final var writes = new SourceWrites("Src", "Tgt", true);
    assertNull(writes.drop("m"));
    assertNull(writes.write("n", "m"));
  }
}
