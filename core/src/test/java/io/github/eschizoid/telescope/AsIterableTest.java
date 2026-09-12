package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fifth container companion, for a focus that is an {@code Iterable} but neither a {@code List}
 * nor a {@code Set}. Its four siblings each name one container; this one stands for the rest, and
 * that difference is what gives it a contract the others do not have.
 *
 * <p>Reading and writing are not symmetric through it. Every {@code Iterable} enumerates, so a read
 * works whatever the source turns out to be. A write has to rebuild, and only a {@code List} or a
 * {@code Set} source can be rebuilt — so a path that reads cleanly can still throw on update. The
 * tests below pin both halves, because a suite that only reads would report the write as working.
 */
class AsIterableTest {

  record Bag(Collection<String> items, String label) {}

  private static Telescope<Bag, String> elements() {
    return Telescope.asIterable(Telescope.of(Bag.class).field(Bag::items)).each();
  }

  @Test
  @DisplayName("reads focus every element, whatever Iterable the field turns out to hold")
  void readsEnumerateAnySource() {
    // Three sources of different container kinds behind one declared type. A read has no rebuild
    // to do, so the runtime class of the value cannot matter, and an ArrayDeque proves it.
    assertEquals(List.of("a", "b"), elements().toList(new Bag(List.of("a", "b"), "l")));
    assertEquals(List.of("a"), elements().toList(new Bag(new LinkedHashSet<>(List.of("a")), "l")));
    assertEquals(List.of("q"), elements().toList(new Bag(new ArrayDeque<>(List.of("q")), "l")));
  }

  @Test
  @DisplayName("a write through a List source rebuilds it and leaves its siblings alone")
  void writeRebuildsAListSource() {
    final var out = elements().update(new Bag(List.of("a", "b"), "keep"), String::toUpperCase);

    assertEquals(List.of("A", "B"), List.copyOf(out.items()));
    assertEquals("keep", out.label(), "the edit must not disturb an unrelated component");
  }

  @Test
  @DisplayName("a write through a Set source rebuilds it as a set, not as a list")
  void writeRebuildsASetSource() {
    final var out = elements().update(new Bag(new LinkedHashSet<>(List.of("a", "b")), "l"), String::toUpperCase);

    assertTrue(out.items() instanceof java.util.Set<String>, "the source's container kind survives the rebuild");
    assertEquals(List.of("A", "B"), List.copyOf(out.items()));
  }

  @Test
  @DisplayName("a write through a source that is neither refuses, where the matching read succeeded")
  void writeRefusesAnUnrebuildableSource() {
    // The asymmetry the javadoc names. This is the case a read-only test would report as healthy:
    // the same source enumerated fine above.
    final var deque = new Bag(new ArrayDeque<>(List.of("q")), "l");

    final var thrown = assertThrows(IllegalArgumentException.class, () ->
      elements().update(deque, String::toUpperCase)
    );
    assertTrue(
      thrown.getMessage().contains("supports only List and Set sources"),
      () -> "the refusal should name what it can rebuild; saw " + thrown.getMessage()
    );
  }

  @Test
  @DisplayName("a null container reads empty and writes through untouched")
  void nullContainerIsInert() {
    assertEquals(List.of(), elements().toList(new Bag(null, "l")));
    assertEquals(new Bag(null, "l"), elements().update(new Bag(null, "l"), String::toUpperCase));
  }
}
