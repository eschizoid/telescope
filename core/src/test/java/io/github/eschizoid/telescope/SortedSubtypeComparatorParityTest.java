package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reflective rebuild keeps the order a sorted source carried, or refuses to return a container
 * that is quietly ordered differently. The generated path answers the same way for the same shapes.
 *
 * <p>A rebuild that drops a comparator reorders by the elements' own {@code compareTo} while
 * producing a container of the right type and the right size, so nothing but an order-sensitive
 * assertion can see it.
 */
class SortedSubtypeComparatorParityTest {

  public static class CmpMap<K, V> extends TreeMap<K, V> {

    private static final long serialVersionUID = 1L;

    public CmpMap() {}

    public CmpMap(final Comparator<? super K> c) {
      super(c);
    }
  }

  public static class PlainMap<K, V> extends TreeMap<K, V> {

    private static final long serialVersionUID = 1L;

    public PlainMap() {}
  }

  public static class CmpSet<E> extends TreeSet<E> {

    private static final long serialVersionUID = 1L;

    public CmpSet() {}

    public CmpSet(final Comparator<? super E> c) {
      super(c);
    }
  }

  public static class PlainSet<E> extends TreeSet<E> {

    private static final long serialVersionUID = 1L;

    public PlainSet() {}
  }

  public record SrcMap(SortedMap<String, String> items) {}

  public record ToCmpMap(CmpMap<String, String> items) {}

  public record ToPlainMap(PlainMap<String, String> items) {}

  public record SrcSet(SortedSet<String> items) {}

  public record ToCmpSet(CmpSet<String> items) {}

  public record ToPlainSet(PlainSet<String> items) {}

  private static SortedMap<String, String> reversedMap() {
    final var m = new TreeMap<String, String>(Comparator.<String>reverseOrder());
    m.put("a", "1");
    m.put("b", "2");
    return m;
  }

  @Test
  @DisplayName("a sorted subtype declaring a Comparator constructor is rebuilt in the source's order")
  void aSubtypeThatCanTakeTheComparatorKeepsTheOrder() {
    final var out = Telescope.mapper(SrcMap.class, ToCmpMap.class).forward(new SrcMap(reversedMap()));

    assertNotNull(out.items().comparator(), "the comparator should have been carried");
    assertEquals(List.of("b", "a"), List.copyOf(out.items().keySet()));
  }

  @Test
  @DisplayName("a sorted set subtype declaring a Comparator constructor is rebuilt in the source's order")
  void aSetSubtypeThatCanTakeTheComparatorKeepsTheOrder() {
    final var src = new TreeSet<String>(Comparator.<String>reverseOrder());
    src.add("a");
    src.add("b");
    final var out = Telescope.mapper(SrcSet.class, ToCmpSet.class).forward(new SrcSet(src));

    assertNotNull(out.items().comparator(), "the comparator should have been carried");
    assertEquals(List.of("b", "a"), List.copyOf(out.items()));
  }

  @Test
  @DisplayName("a sorted subtype with nowhere to put the comparator refuses rather than reordering")
  void aSubtypeThatCannotTakeItRefuses() {
    final var mapper = Telescope.mapper(SrcMap.class, ToPlainMap.class);
    final var thrown = assertThrows(IllegalStateException.class, () -> mapper.forward(new SrcMap(reversedMap())));

    assertEquals(true, thrown.getMessage().contains("Comparator"), () -> "saw " + thrown.getMessage());
  }

  @Test
  @DisplayName("the same target is rebuilt without complaint from a naturally ordered source")
  void aNaturallyOrderedSourceLosesNothing() {
    // The refusal is about an order that would be dropped. Natural ordering survives any rebuild of
    // a sorted container, so the same pairing that refuses above is fine here.
    final var natural = new TreeMap<String, String>();
    natural.put("a", "1");
    natural.put("b", "2");

    final var out = Telescope.mapper(SrcMap.class, ToPlainMap.class).forward(new SrcMap(natural));

    assertEquals(List.of("a", "b"), List.copyOf(out.items().keySet()));
  }
}
