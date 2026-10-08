package io.github.eschizoid.telescope.containerparity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.Telescope;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An interface used raw on one side of an element copy is built as the default implementation the
 * allocation table names for its family, and a sorted default is handed the source's comparator.
 * The elements are copied as they are, since neither side names a type for them.
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
class RawInterfaceCopyTest {

  public record ListSrc(List items) {}

  public record ArrayListTgt(ArrayList items) {}

  public record SetSrc(Set items) {}

  public record LinkedHashSetTgt(LinkedHashSet items) {}

  public record SortedSetSrc(SortedSet items) {}

  public record TreeSetTgt(TreeSet items) {}

  public record TreeMapSrc(TreeMap items) {}

  public record SortedMapTgt(SortedMap items) {}

  @Test
  @DisplayName("a raw List copies into a raw ArrayList and back into the List's default")
  void rawListCopies() {
    final var mapper = Telescope.mapper(ListSrc.class, ArrayListTgt.class);
    final var out = mapper.forward(new ListSrc(new ArrayList(List.of("b", "a"))));
    assertEquals(List.of("b", "a"), out.items());
    final var back = mapper.backward(out);
    assertEquals(ArrayList.class, back.items().getClass(), "the List is rebuilt as its default");
    assertEquals(List.of("b", "a"), back.items());
  }

  @Test
  @DisplayName("a raw Set copies into a raw LinkedHashSet and back into the Set's default, in order")
  void rawSetCopies() {
    final var mapper = Telescope.mapper(SetSrc.class, LinkedHashSetTgt.class);
    final var out = mapper.forward(new SetSrc(new LinkedHashSet(List.of("b", "a"))));
    assertEquals(List.of("b", "a"), List.copyOf(out.items()));
    final var back = mapper.backward(out);
    assertEquals(LinkedHashSet.class, back.items().getClass(), "the Set is rebuilt as its default");
    assertEquals(List.of("b", "a"), List.copyOf(back.items()));
  }

  @Test
  @DisplayName("a raw SortedSet keeps its comparator into a raw TreeSet, and the default keeps it back")
  void rawSortedSetKeepsItsComparator() {
    final var reversed = new TreeSet(Comparator.reverseOrder());
    reversed.addAll(List.of("a", "b"));
    final var mapper = Telescope.mapper(SortedSetSrc.class, TreeSetTgt.class);
    final var out = mapper.forward(new SortedSetSrc(reversed));
    assertEquals(List.of("b", "a"), List.copyOf(out.items()), "the target keeps the reversing order");
    final var back = mapper.backward(out);
    assertEquals(TreeSet.class, back.items().getClass(), "the SortedSet is rebuilt as its default");
    assertEquals(List.of("b", "a"), List.copyOf(back.items()), "and the default keeps the order too");
  }

  @Test
  @DisplayName("a raw TreeMap keeps its comparator into a raw SortedMap's default, and back")
  void rawSortedMapKeepsItsComparator() {
    final var reversed = new TreeMap(Comparator.reverseOrder());
    reversed.put("a", 1);
    reversed.put("b", 2);
    final var mapper = Telescope.mapper(TreeMapSrc.class, SortedMapTgt.class);
    final var out = mapper.forward(new TreeMapSrc(reversed));
    assertEquals(TreeMap.class, out.items().getClass(), "the SortedMap is built as its default");
    assertEquals(List.of("b", "a"), List.copyOf(out.items().keySet()), "keeping the reversing order");
    final var back = mapper.backward(out);
    assertEquals(List.of("b", "a"), List.copyOf(back.items().keySet()));
  }

  @Test
  @DisplayName("a naturally ordered raw SortedSet copies in natural order")
  void naturallyOrderedSortedSetCopies() {
    final var natural = new TreeSet(List.of("b", "a"));
    final var out = Telescope.mapper(SortedSetSrc.class, TreeSetTgt.class).forward(new SortedSetSrc(natural));
    assertEquals(List.of("a", "b"), List.copyOf(out.items()));
  }
}
