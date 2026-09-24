package io.github.eschizoid.telescope.containerparity;

import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
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

  // Deliberately not Comparable, and deliberately identical on both sides: when the element type
  // does not change the conversion carries the source's comparator into the new container, and a
  // container ordered by a comparator never asks its elements to order themselves. Every other
  // fixture here changes the element type, so this is the only one that reaches that path.
  //
  // The declared container types have to differ for the pair below to reach a lift at all. Same
  // element type AND same raw container is short-circuited before one is built, so squaring the
  // two sides up would leave this reaching none of the code it is here to hold.
  record Unordered(String name) {}

  record CarriedComparatorSrc(SortedSet<Unordered> items) {}

  record CarriedComparatorDst(TreeSet<Unordered> items) {}

  // Stamped by a supplier that counts, so the number of times an element is converted is readable
  // off the result rather than inferred. Comparable, so a sorted target accepts it.
  record Stamped(String v, int seq) implements Comparable<Stamped> {
    @Override
    public int compareTo(final Stamped other) {
      return v.compareTo(other.v);
    }
  }

  record StampedSrc(Set<Ordered> items) {}

  record StampedSortedDst(SortedSet<Stamped> items) {}

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
    // Asked of the first converted element before anything is inserted, so the refusal carries no
    // cause: there is no cast to keep, because none was allowed to happen. What it names instead is
    // the element class, which the cast it replaces never did.
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
    assertTrue(
      thrown.getMessage().contains(Renamed.class.getName()),
      () -> "and which element type is missing it: " + thrown.getMessage()
    );
  }

  @Test
  @DisplayName("a cast raised for another reason keeps its own cause, and is not called an ordering problem")
  void unrelatedCastIsNotReportedAsAnOrderingProblem() {
    // Both element types here implement Comparable, so ordering is not what fails. The cast comes
    // from the element conversion reading a component off a value that is not the type it was
    // declared as — the same shape a bridge handing back the wrong type produces. Naming Comparable
    // here would name a cause that is not the cause, and send the reader to add a comparator that
    // would change nothing.
    final var raw = new LinkedHashSet<Object>();
    raw.add("not an Ordered");
    @SuppressWarnings("unchecked")
    final var polluted = (Set<Ordered>) (Set<?>) raw;

    final var thrown = assertThrows(ClassCastException.class, () ->
      Telescope.mapper(OrderedSrc.class, OrderedSortedDst.class).forward(new OrderedSrc(polluted))
    );

    assertFalse(
      String.valueOf(thrown.getMessage()).contains("Comparable"),
      () -> "a cast from elsewhere should not be dressed up as an ordering refusal: " + thrown.getMessage()
    );
  }

  @Test
  @DisplayName("a cast from an element past the first is not called an ordering problem either")
  void aLaterElementsCastIsNotReportedAsAnOrderingProblem() {
    // The sibling above pollutes the first element, so the refusal it proves is the one guarding
    // the element the check looks at. This pollutes a later one, which the check never sees: the
    // cast comes from the insert loop itself. Without this, an implementation that wrapped the
    // whole build back up in the old blanket catch would pass the sibling and go unnoticed, since
    // nothing would ever reach the catch.
    final var raw = new LinkedHashSet<Object>();
    raw.add(new Ordered("a"));
    raw.add("not an Ordered");
    @SuppressWarnings("unchecked")
    final var polluted = (Set<Ordered>) (Set<?>) raw;

    final var thrown = assertThrows(ClassCastException.class, () ->
      Telescope.mapper(OrderedSrc.class, OrderedSortedDst.class).forward(new OrderedSrc(polluted))
    );

    assertFalse(
      String.valueOf(thrown.getMessage()).contains("Comparable"),
      () -> "a cast from the build should keep its own cause: " + thrown.getMessage()
    );
  }

  @Test
  @DisplayName("building a sorted target converts each element exactly once")
  void aSortedTargetConvertsEachElementOnce() {
    // The ordering question is asked of a converted element, so where it is asked decides how many
    // times that element is converted. Asked from outside the loop, the first element would be
    // converted twice -- once to test and once to insert -- and a conversion that counts, stamps an
    // id or reads a clock would give the first element a different value than it would have had.
    // The seq values are the count: a doubled first conversion reads 2 and 3 rather than 1 and 2.
    final var counter = new AtomicInteger();
    final var elementMapper = Telescope.mapper(
      Ordered.class,
      Stamped.class,
      to(Ordered::v, Stamped::v),
      compute(Stamped::seq, counter::incrementAndGet)
    );
    final var items = new LinkedHashSet<Ordered>();
    items.add(new Ordered("a"));
    items.add(new Ordered("b"));

    final var out = Telescope.mapper(
      StampedSrc.class,
      StampedSortedDst.class,
      via(StampedSrc::items, StampedSortedDst::items, elementMapper)
    ).forward(new StampedSrc(items));

    assertEquals(List.of(new Stamped("a", 1), new Stamped("b", 2)), List.copyOf(out.items()));
    assertEquals(2, counter.get(), "two elements should mean two conversions");
  }

  @Test
  @DisplayName("a sorted target built with a carried comparator does not ask its elements to be Comparable")
  void carriedComparatorRemovesTheOrderingQuestion() {
    // The element type is unchanged, so nothing is converted and the source's comparator crosses
    // into the new container. A container ordered by a comparator never calls compareTo, so an
    // element type that implements nothing is still fine -- and a refusal here would tell the
    // reader to supply the comparator they already supplied.
    final var byName = new TreeSet<Unordered>(Comparator.comparing(Unordered::name));
    byName.add(new Unordered("beth"));
    byName.add(new Unordered("al"));

    final var out = Telescope.mapper(CarriedComparatorSrc.class, CarriedComparatorDst.class).forward(
      new CarriedComparatorSrc(byName)
    );

    assertEquals(List.of(new Unordered("al"), new Unordered("beth")), List.copyOf(out.items()));
    assertNotNull(out.items().comparator(), "the comparator should cross with the elements");
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
    // sentence also says "comparator" -- so deleting the comparator check entirely would leave it
    // green. Neither refusal carries a cause any more, both being reached before anything is
    // inserted, so what separates them is that only the one about ordering names Comparable.
    assertFalse(
      thrown.getMessage().contains("Comparable"),
      () -> "this is the comparator refusal, not the one about ordering: " + thrown.getMessage()
    );
    assertTrue(
      thrown.getMessage().contains("cannot be reused"),
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
