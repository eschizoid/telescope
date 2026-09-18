package io.github.eschizoid.telescope.containerparity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether a sorted source can be converted is decided twice — once while a bridge is generated and
 * once while a mapper is planned — and the two are meant to accept the same programs. They decide
 * it from the same facts, so a narrowing applied to one and not the other is a conversion that
 * works when written against the runtime and fails when written against {@code @Bridge}.
 *
 * <p>These run the generated bridge rather than reading its text. The decision is emitted as a
 * runtime test inside the generated method, so the file compiles either way and only running it
 * tells the two apart.
 *
 * <p>The element types deliberately implement nothing. A {@code Comparable} element makes every row
 * here pass whatever either path decides, which is the same shape as writing the fixture with an
 * empty set: the container never has to order anything, so the question is never asked.
 */
class SortedContainerParityTest {

  private static Set<SortedParityA> sortedWithComparator() {
    final var byV = new TreeSet<SortedParityA>(Comparator.comparing(SortedParityA::v));
    byV.add(new SortedParityA("x"));
    return byV;
  }

  @Test
  @DisplayName("a comparator no target needs is refused by neither path")
  void unorderedTargetAcceptsOnBothPaths() {
    // The source holds a TreeSet with a custom comparator behind a plain Set declaration, and the
    // target keeps no order. The comparator cannot come across — it orders the type being
    // converted away from — but nothing is asking it to, so neither path has anything to refuse.
    final var src = new SortedParityPlainSrc(sortedWithComparator());

    final var reflective = Telescope.mapper(SortedParityPlainSrc.class, SortedParityPlainTgt.class).forward(src);
    final var generated = SortedParityPlainSrcBridge.BRIDGE.read(src);

    assertEquals(List.of(new SortedParityB("x")), List.copyOf(reflective.items()), "the reflective path converts");
    assertEquals(List.of(new SortedParityB("x")), List.copyOf(generated.items()), "and so does the generated one");
  }

  @Test
  @DisplayName("a comparator the target does need is refused by both paths")
  void orderedTargetRefusesOnBothPaths() {
    // The control, and what stops the row above from being "delete the check". This target keeps an
    // order, the only ordering on offer is written for the type being converted away from, and
    // falling back to natural ordering would reorder the set without saying so.
    final var src = new SortedParitySortedSrc(sortedWithComparator());

    final var reflective = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(SortedParitySortedSrc.class, SortedParitySortedTgt.class).forward(src)
    );
    final var generated = assertThrows(IllegalStateException.class, () -> SortedParitySortedSrcBridge.BRIDGE.read(src));

    assertTrue(reflective.getMessage().contains("comparator"), () -> reflective.getMessage());
    assertTrue(generated.getMessage().contains("comparator"), () -> generated.getMessage());
  }

  @Test
  @DisplayName("an ordinary unsorted source converts on both paths, carrying no ordering question")
  void plainSourceConvertsOnBothPaths() {
    // The second control. A change that refused every set, or accepted every set, would satisfy one
    // of the rows above and break this.
    final var src = new SortedParityPlainSrc(Set.of(new SortedParityA("x")));

    assertEquals(
      List.of(new SortedParityB("x")),
      List.copyOf(Telescope.mapper(SortedParityPlainSrc.class, SortedParityPlainTgt.class).forward(src).items())
    );
    assertEquals(List.of(new SortedParityB("x")), List.copyOf(SortedParityPlainSrcBridge.BRIDGE.read(src).items()));
  }
}
