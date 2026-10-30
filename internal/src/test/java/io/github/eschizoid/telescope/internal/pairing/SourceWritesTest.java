package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SourceWritesTest {

  @Test
  @DisplayName("a mapper that converts both ways refuses a second target, naming both in the order written")
  void twoWayRefusesASecondTarget() {
    final var writes = new SourceWrites("Src", "Tgt", true);
    assertNull(writes.write("n", "b"));
    assertNull(writes.fanOutRefusal());
    assertNull(writes.write("n", "a"));
    assertTrue(writes.fannedOut("n"));
    assertEquals(PairingMessages.fanOutBothWays("Src", "Tgt", "n", List.of("b", "a")), writes.fanOutRefusal());
  }

  @Test
  @DisplayName("the refusal names every target the property is written into, each once")
  void everyTargetNamedOnce() {
    final var writes = new SourceWrites("Src", "Tgt", true);
    for (final var target : List.of("a", "b", "a", "addr.city")) assertNull(writes.write("n", target));
    assertEquals(
      PairingMessages.fanOutBothWays("Src", "Tgt", "n", List.of("a", "b", "addr.city")),
      writes.fanOutRefusal()
    );
  }

  @Test
  @DisplayName("the refusal is of the property that reached a second target first")
  void firstFanOutRefused() {
    final var writes = new SourceWrites("Src", "Tgt", true);
    assertNull(writes.write("m", "x"));
    assertNull(writes.write("n", "a"));
    assertNull(writes.write("n", "b"));
    assertNull(writes.write("m", "y"));
    assertEquals(PairingMessages.fanOutBothWays("Src", "Tgt", "n", List.of("a", "b")), writes.fanOutRefusal());
  }

  @Test
  @DisplayName("one target written twice is not a fan-out")
  void oneTargetTwiceIsNoFanOut() {
    final var writes = new SourceWrites("Src", "Tgt", true);
    assertNull(writes.write("n", "a"));
    assertNull(writes.write("n", "a"));
    assertFalse(writes.fannedOut("n"));
    assertNull(writes.fanOutRefusal());
  }

  @Test
  @DisplayName("a forward-only mapper writes a property into as many targets as its rows name")
  void forwardOnlyTakesEveryTarget() {
    final var writes = new SourceWrites("Src", "Tgt", false);
    assertNull(writes.write("n", "a"));
    assertNull(writes.write("n", "b"));
    assertNull(writes.write("n", "n"));
    assertFalse(writes.fannedOut("n"));
    assertNull(writes.fanOutRefusal());
  }

  @Test
  @DisplayName("distinct source properties each take a target of their own")
  void distinctSourcesAreIndependent() {
    final var writes = new SourceWrites("Src", "Tgt", true);
    assertNull(writes.write("n", "a"));
    assertNull(writes.write("m", "b"));
    assertNull(writes.fanOutRefusal());
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
  @DisplayName("a path written into two targets of a two-way mapper is refused by its dotted name")
  void twoWayRefusesAPathInTwoTargets() {
    final var writes = new SourceWrites("Src", "Tgt", true);
    assertNull(writes.write("customer.city", "city"));
    assertNull(writes.write("customer.city", "town"));
    assertEquals(
      PairingMessages.fanOutBothWays("Src", "Tgt", "customer.city", List.of("city", "town")),
      writes.fanOutRefusal()
    );
  }

  @Test
  @DisplayName("two paths through one property, and the property itself, each take a target of their own")
  void pathsThroughOnePropertyAreIndependent() {
    final var writes = new SourceWrites("Src", "Tgt", true);
    assertNull(writes.write("customer.city", "city"));
    assertNull(writes.write("customer.zip", "zip"));
    assertNull(writes.write("customer", "customer"));
    assertNull(writes.fanOutRefusal());
  }

  @Test
  @DisplayName("a drop and a path through the dropped property are refused in either order, on either factory")
  void dropAndPathThroughItRefusedInEitherOrder() {
    for (final var backward : new boolean[] { true, false }) {
      final var dropFirst = new SourceWrites("Src", "Tgt", backward);
      assertNull(dropFirst.drop("customer"));
      assertEquals(
        PairingMessages.droppedAndReadThrough("Src", "Tgt", "customer", "customer.address.city"),
        dropFirst.write("customer.address.city", "city")
      );
      final var writeFirst = new SourceWrites("Src", "Tgt", backward);
      assertNull(writeFirst.write("customer.address.city", "city"));
      assertEquals(
        PairingMessages.droppedAndReadThrough("Src", "Tgt", "customer", "customer.address.city"),
        writeFirst.drop("customer")
      );
    }
  }

  @Test
  @DisplayName("a drop of a property whose name starts another's leaves a path through the other free")
  void dropOfAPrefixNamedPropertyLeavesOthersFree() {
    final var writes = new SourceWrites("Src", "Tgt", true);
    assertNull(writes.drop("cust"));
    assertNull(writes.write("customer.city", "city"));
    final var pathFirst = new SourceWrites("Src", "Tgt", true);
    assertNull(pathFirst.write("customer.city", "city"));
    assertNull(pathFirst.drop("cust"));
  }

  @Test
  @DisplayName("a drop of one property leaves another free to be written")
  void dropLeavesOthersFree() {
    final var writes = new SourceWrites("Src", "Tgt", true);
    assertNull(writes.drop("m"));
    assertNull(writes.write("n", "m"));
  }
}
