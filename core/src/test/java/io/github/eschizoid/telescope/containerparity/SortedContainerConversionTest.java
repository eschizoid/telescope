package io.github.eschizoid.telescope.containerparity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A set that keeps its elements in order needs them orderable, and a conversion changes what the
 * elements are. Two questions follow: whether the new element type can be ordered at all, and
 * whether an ordering the source carried can come across with it.
 *
 * <p>Both are only questions for the side being <em>built</em>. A target that keeps no order needs
 * neither, so asking there refuses a conversion that would have worked.
 *
 * <p>Every source here holds an element. An empty one converts cleanly whatever the implementation
 * does, because nothing is ever inserted and the insert is where both questions are answered — so a
 * fixture written with an empty set pins nothing at all.
 */
class SortedContainerConversionTest {

  record Plain(String v) {}

  record Renamed(String v) {}

  record Ordered(String v) implements Comparable<Ordered> {
    @Override
    public int compareTo(final Ordered other) {
      return v.compareTo(other.v);
    }
  }

  record AlsoOrdered(String v) implements Comparable<AlsoOrdered> {
    @Override
    public int compareTo(final AlsoOrdered other) {
      return v.compareTo(other.v);
    }
  }

  record PlainSetSrc(Set<Plain> items) {}

  record SortedSetDst(SortedSet<Renamed> items) {}

  record PlainSetDst(Set<Renamed> items) {}

  record TreeSrc(TreeSet<Plain> items) {}

  record TreeDst(TreeSet<Renamed> items) {}

  record OrderedSrc(Set<Ordered> items) {}

  record OrderedSortedDst(SortedSet<AlsoOrdered> items) {}

  private static Set<Plain> onePlain() {
    final var one = new LinkedHashSet<Plain>();
    one.add(new Plain("x"));
    return one;
  }

  private static TreeSet<Plain> withComparator() {
    final var byV = new TreeSet<Plain>(Comparator.comparing(Plain::v));
    byV.add(new Plain("x"));
    return byV;
  }

  @Test
  @DisplayName("an element type that cannot be ordered is refused by name, not by a bare cast")
  void unorderableElementIsRefusedByName() {
    // The insert raises a ClassCastException naming the element class and Comparable and nothing
    // else — not the container, not the field, not the library. Next door to two diagnostics this
    // codebase writes carefully for the same family of problem.
    final var thrown = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(PlainSetSrc.class, SortedSetDst.class).forward(new PlainSetSrc(onePlain()))
    );

    assertTrue(
      thrown.getMessage().contains("SortedSet"),
      () -> "the refusal should name the container that needs the ordering: " + thrown.getMessage()
    );
    assertTrue(
      thrown.getMessage().contains("Comparable"),
      () -> "and what the element type is missing: " + thrown.getMessage()
    );
    assertInstanceOf(
      ClassCastException.class,
      thrown.getCause(),
      "with the cast it replaces kept as the cause rather than discarded"
    );
  }

  @Test
  @DisplayName("a target that keeps no order accepts a source that carried a custom comparator")
  void unorderedTargetAcceptsAComparatorItDoesNotNeed() {
    // The comparator cannot come across, because it orders the source's element type and the
    // target's is a different one. That only matters where the target has an order to keep. Here
    // it does not, so there is nothing to carry and nothing to refuse.
    final var out = Telescope.mapper(TreeSrc.class, PlainSetDst.class).forward(new TreeSrc(withComparator()));

    assertEquals(List.of(new Renamed("x")), List.copyOf(out.items()));
    assertFalse(out.items() instanceof SortedSet, "and the target is not silently given an order");
  }

  @Test
  @DisplayName("a target that does keep an order still refuses the comparator it cannot reuse")
  void orderedTargetStillRefusesACarriedComparator() {
    // The control for the row above, and the reason it cannot simply drop the check: this target
    // needs an ordering, the only one on offer is written for the type being converted away from,
    // and quietly falling back to natural ordering would reorder the set without saying so.
    final var thrown = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(TreeSrc.class, TreeDst.class).forward(new TreeSrc(withComparator()))
    );

    // On the word alone this holds whichever refusal was raised, because the other one's remedy
    // sentence also says "comparator" -- so deleting the comparator check entirely left this green.
    // The cause separates them: a refusal about the ordering wraps the cast that raised it, and a
    // refusal about the comparator is reached before anything is inserted and has none.
    assertNull(thrown.getCause(), () -> "this is the comparator refusal, not the one about ordering");
    assertTrue(
      thrown.getMessage().contains("comparator"),
      () -> "and says which of the two problems it is: " + thrown.getMessage()
    );
  }

  @Test
  @DisplayName("an orderable element type converts into a sorted target, in order")
  void orderableElementsConvert() {
    // The other control. A change that refused every sorted target would satisfy the two rows
    // above and break the case the whole shape exists to serve.
    final var src = new LinkedHashSet<Ordered>();
    src.add(new Ordered("b"));
    src.add(new Ordered("a"));

    final var out = Telescope.mapper(OrderedSrc.class, OrderedSortedDst.class).forward(new OrderedSrc(src));

    assertEquals(
      List.of(new AlsoOrdered("a"), new AlsoOrdered("b")),
      List.copyOf(out.items()),
      "the target's own ordering applies, so the insertion order does not survive"
    );
  }
}
