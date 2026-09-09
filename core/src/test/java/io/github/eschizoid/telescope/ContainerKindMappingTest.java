package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.Vector;
import java.util.WeakHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deep mapping into the List and Map implementations outside the mainstream ones. Each rebuilds
 * into the target field's own concrete class — writing a different one back would pass the lift and
 * then fail at the constructor or setter — and each survives a null container and an empty one, the
 * two shapes a sizing hint derived from the source has to tolerate.
 *
 * <p>Queue and Deque implementations are absent because the sized allocators never see them: a
 * container view is derived as LIST only for List subtypes, so those components either take the raw
 * same-kind copy path or are rejected outright when their element types differ.
 */
class ContainerKindMappingTest {

  record Tag(String value) {}

  record TagDto(String value) {}

  record VectorHolder(Vector<Tag> tags) {}

  record VectorHolderDto(Vector<TagDto> tags) {}

  record WeakHolder(WeakHashMap<String, Tag> tags) {}

  record WeakHolderDto(WeakHashMap<String, TagDto> tags) {}

  private static Vector<Tag> vectorOf(final int size) {
    final var v = new Vector<Tag>();
    for (var i = 0; i < size; i++) v.add(new Tag("t" + i));
    return v;
  }

  @Test
  @DisplayName("a Vector field rebuilds as a Vector, at every size")
  void vectorTargetsKeepTheirClass() {
    final var mapper = Telescope.mapper(VectorHolder.class, VectorHolderDto.class);
    for (final var size : new int[] { 0, 1, 12, 17 }) {
      final var mapped = mapper.forward(new VectorHolder(vectorOf(size)));
      assertInstanceOf(Vector.class, mapped.tags());
      assertEquals(size, mapped.tags().size());
      // capacity, not size, is what distinguishes a source-sized rebuild from a default-capacity
      // one: the no-arg constructor starts at ten and doubles, so it reports 10 or 20 here.
      assertEquals(size, mapped.tags().capacity(), "the rebuilt Vector is sized from its source");
      if (size > 0) assertEquals(new TagDto("t0"), mapped.tags().getFirst());
      assertEquals(new VectorHolder(vectorOf(size)), mapper.backward(mapped));
    }
    assertNull(mapper.forward(new VectorHolder(null)).tags(), "a null container maps to null");
  }

  @Test
  @DisplayName("a WeakHashMap field rebuilds as a WeakHashMap with its entries intact")
  void weakHashMapTargetsKeepTheirClass() {
    final var mapper = Telescope.mapper(WeakHolder.class, WeakHolderDto.class);
    for (final var size : new int[] { 0, 1, 12, 17 }) {
      // Keys are held locally for the duration of the assertions: a WeakHashMap may drop entries
      // whose keys become unreachable, so the source has to outlive the read.
      final var keys = new ArrayList<String>(size);
      final var source = new WeakHashMap<String, Tag>();
      for (var i = 0; i < size; i++) {
        final var key = "k" + i;
        keys.add(key);
        source.put(key, new Tag("t" + i));
      }

      final var mapped = mapper.forward(new WeakHolder(source));
      assertInstanceOf(WeakHashMap.class, mapped.tags());
      assertEquals(size, mapped.tags().size());
      for (var i = 0; i < size; i++) assertEquals(new TagDto("t" + i), mapped.tags().get(keys.get(i)));
      Reference.reachabilityFence(keys);
    }
    assertNull(mapper.forward(new WeakHolder(null)).tags());
  }

  @Test
  @DisplayName("capacityFor sizes a table that holds its element count without resizing")
  void capacityForHoldsItsElementCount() {
    // The containers this backs take a table capacity, so the count has to be divided by the load
    // factor: a table given the count itself resizes once the count passes three quarters of the
    // next power of two.
    assertEquals(0, ContainerLifts.capacityFor(0));
    assertEquals(2, ContainerLifts.capacityFor(1));
    assertEquals(16, ContainerLifts.capacityFor(12));
    assertEquals(23, ContainerLifts.capacityFor(17));

    for (final var count : new int[] { 1, 12, 13, 16, 17, 100, 1000 }) {
      final var table = new WeakHashMap<Integer, Integer>(ContainerLifts.capacityFor(count));
      for (var i = 0; i < count; i++) table.put(i, i);
      assertEquals(count, table.size(), "every entry survives the fill at count " + count);
    }
  }
}
