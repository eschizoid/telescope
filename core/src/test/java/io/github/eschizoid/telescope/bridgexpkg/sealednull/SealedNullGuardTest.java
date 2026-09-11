package io.github.eschizoid.telescope.bridgexpkg.sealednull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A generated bridge's {@code forward}/{@code backward} are null-in/null-out. That contract is what
 * lets a caller hand over a slot it has not inspected, and it is what the generated container
 * helpers rely on when an element is null. A sealed umbrella reaches the same two methods through
 * {@code Match} dispatch rather than a field sequence, so it arrives at that contract by its own
 * emission path and has to arrive at the same place.
 *
 * <p>{@link Label} is the non-sealed control on every assertion below: an auto-derived bridge of
 * the plainest possible shape, called identically. Without it a change that removed null handling
 * from every generated bridge at once would still satisfy the sealed half.
 */
class SealedNullGuardTest {

  @Test
  @DisplayName("a sealed umbrella answers null for null on forward, as a non-sealed bridge does")
  void sealedForwardIsNullInNullOut() {
    assertNull(LabelBridge.forward(null), "the non-sealed control must be null-in/null-out");
    assertNull(ShapeBridge.forward(null), "sealed forward must not dispatch on a null source");
  }

  @Test
  @DisplayName("a sealed umbrella answers null for null on backward, as a non-sealed bridge does")
  void sealedBackwardIsNullInNullOut() {
    assertNull(LabelBridge.backward(null), "the non-sealed control must be null-in/null-out");
    assertNull(ShapeBridge.backward(null), "sealed backward must not dispatch on a null target");
  }

  @Test
  @DisplayName("a present value still dispatches to the permit's own bridge, in both directions")
  void presentValueStillDispatchesPerPermit() {
    assertEquals(new CircleDto(2.0), ShapeBridge.forward(new Circle(2.0)));
    assertEquals(new SquareDto(3.0), ShapeBridge.forward(new Square(3.0)));
    assertEquals(new Circle(2.0), ShapeBridge.backward(new CircleDto(2.0)));
    assertEquals(new Square(3.0), ShapeBridge.backward(new SquareDto(3.0)));
  }
}
