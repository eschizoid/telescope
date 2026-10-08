package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

  /** Declares its parameters in the opposite order to TreeMap, and orders its keys. */
  public static class SwappedByKey<V, K> extends TreeMap<K, V> {

    private static final long serialVersionUID = 1L;

    public SwappedByKey() {}

    public SwappedByKey(final Comparator<? super K> c) {
      super(c);
    }
  }

  /**
   * Declares its parameters in the opposite order to TreeMap, and takes a comparator over values.
   */
  public static class SwappedByValue<V, K> extends TreeMap<K, V> {

    private static final long serialVersionUID = 1L;

    public SwappedByValue() {}

    public SwappedByValue(final Comparator<? super V> ignored) {}
  }

  /** A second sorted set subtype that can take a comparator, so a raw copy has to rebuild. */
  public static class OtherCmpSet<E> extends TreeSet<E> {

    private static final long serialVersionUID = 1L;

    public OtherCmpSet() {}

    public OtherCmpSet(final Comparator<? super E> c) {
      super(c);
    }
  }

  /** A second key-ordering subtype declaring its parameters value first. */
  public static class OtherSwappedByKey<V, K> extends TreeMap<K, V> {

    private static final long serialVersionUID = 1L;

    public OtherSwappedByKey() {}

    public OtherSwappedByKey(final Comparator<? super K> c) {
      super(c);
    }
  }

  @SuppressWarnings("rawtypes")
  public record RawCmpSet(CmpSet items) {}

  @SuppressWarnings("rawtypes")
  public record RawOtherCmpSet(OtherCmpSet items) {}

  @SuppressWarnings("rawtypes")
  public record RawSwapped(SwappedByKey items) {}

  @SuppressWarnings("rawtypes")
  public record RawOtherSwapped(OtherSwappedByKey items) {}

  public record SrcObjectKeys(Map<Object, String> items) {}

  public record ToPlainObjectKeys(PlainMap<Object, String> items) {}

  public record SrcSwapped(SortedMap<String, Integer> items) {}

  public record ToSwappedByKey(SwappedByKey<Integer, String> items) {}

  public record ToSwappedByValue(SwappedByValue<Integer, String> items) {}

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

    assertTrue(thrown.getMessage().contains("Comparator"), () -> "saw " + thrown.getMessage());
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

  @Test
  @DisplayName("a sorted set subtype with nowhere to put the comparator refuses rather than reordering")
  void aSetSubtypeThatCannotTakeItRefuses() {
    // The set and map families reach their allocator through different code, so a rule holds for
    // both only if each is asked.
    final var src = new TreeSet<String>(Comparator.<String>reverseOrder());
    src.add("a");
    src.add("b");
    final var mapper = Telescope.mapper(SrcSet.class, ToPlainSet.class);

    final var thrown = assertThrows(IllegalStateException.class, () -> mapper.forward(new SrcSet(src)));

    assertTrue(thrown.getMessage().contains("Comparator"), thrown::getMessage);
  }

  @Test
  @DisplayName("a naturally ordered set converts into the same target that refuses a custom order")
  void aNaturallyOrderedSetLosesNothing() {
    final var src = new TreeSet<String>();
    src.add("a");
    src.add("b");

    final var out = Telescope.mapper(SrcSet.class, ToPlainSet.class).forward(new SrcSet(src));

    assertEquals(List.of("a", "b"), List.copyOf(out.items()));
  }

  private static SortedMap<String, Integer> reversedIntMap() {
    final var m = new TreeMap<String, Integer>(Comparator.<String>reverseOrder());
    m.put("a", 1);
    m.put("b", 2);
    return m;
  }

  @Test
  @DisplayName("a subtype declaring its parameters out of TreeMap's order keeps a key comparator")
  void swappedParametersKeepAKeyComparator() {
    final var out = Telescope.mapper(SrcSwapped.class, ToSwappedByKey.class).forward(new SrcSwapped(reversedIntMap()));
    assertEquals(List.of("b", "a"), List.copyOf(out.items().keySet()));
  }

  @Test
  @DisplayName("a subtype declaring its parameters out of TreeMap's order refuses a value comparator")
  void swappedParametersRefuseAValueComparator() {
    final var mapper = Telescope.mapper(SrcSwapped.class, ToSwappedByValue.class);
    final var thrown = assertThrows(IllegalStateException.class, () ->
      mapper.forward(new SrcSwapped(reversedIntMap()))
    );
    assertTrue(thrown.getMessage().contains("declares no constructor taking a Comparator"), thrown::getMessage);
  }

  @Test
  @DisplayName("a sorted set subtype used raw is rebuilt in the source's order")
  @SuppressWarnings("unchecked")
  void aRawSetSubtypeKeepsTheOrder() {
    final var src = new CmpSet<String>(Comparator.<String>reverseOrder());
    src.add("a");
    src.add("b");
    final var out = Telescope.mapper(RawCmpSet.class, RawOtherCmpSet.class).forward(new RawCmpSet(src));

    assertEquals(List.of("b", "a"), List.copyOf(out.items()));
  }

  @Test
  @DisplayName("a sorted map subtype used raw is rebuilt in the source's order, whatever order it declares")
  @SuppressWarnings("unchecked")
  void aRawMapSubtypeKeepsTheOrder() {
    final var src = new SwappedByKey<Integer, String>(Comparator.<String>reverseOrder());
    src.put("a", 1);
    src.put("b", 2);
    final var out = Telescope.mapper(RawSwapped.class, RawOtherSwapped.class).forward(new RawSwapped(src));

    assertEquals(List.of("b", "a"), List.copyOf(out.items().keySet()));
  }

  @Test
  @DisplayName("a sorted map over keys declared as Object builds, and orders the comparable keys it holds")
  void keysDeclaredAsObjectAreOrderedByWhatTheyHold() {
    final var src = new LinkedHashMap<Object, String>();
    src.put("b", "2");
    src.put("a", "1");

    final var out = Telescope.mapper(SrcObjectKeys.class, ToPlainObjectKeys.class).forward(new SrcObjectKeys(src));

    assertEquals(List.of("a", "b"), List.copyOf(out.items().keySet()));
  }
}
