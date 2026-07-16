package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.internal.MhIso;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Isolates the two container-lift loop strategies against each other. {@link
 * MhIsoDifferentialParityTest} pins the MethodHandle loop against the whole array leaf; this
 * harness flips <em>only</em> the loop strategy — {@link MhIso#CONTAINER_DISABLE_PROPERTY} routes a
 * Leaf element back through the Java loop while keeping the same composed-handle Leaf on both sides
 * — and asserts the MethodHandle {@code iteratedLoop} lift is byte-identical to the Java for-loop
 * lift over that identical Leaf, forward and backward, across List / Set / Map with multi-element,
 * null element, empty, and null-container samples.
 */
@DisplayName("MhIso container MethodHandle loop ↔ Java loop parity (same Leaf)")
final class MhContainerLoopParityTest {

  @AfterEach
  void restoreToggle() {
    System.clearProperty(MhIso.CONTAINER_DISABLE_PROPERTY);
  }

  // Distinct-but-structurally-equal element records so the element conversion is a real Leaf, not
  // an identity.
  record Elem(String name, int n) {}

  record Elem2(String name, int n) {}

  record ListHolder(List<Elem> items) {}

  record ListHolder2(List<Elem2> items) {}

  record SetHolder(Set<Elem> items) {}

  record SetHolder2(Set<Elem2> items) {}

  record MapHolder(Map<String, Elem> byKey) {}

  record MapHolder2(Map<String, Elem2> byKey) {}

  @Test
  @DisplayName("List lift: MH loop == Java loop over the same Leaf element")
  void listLoopParity() {
    for (final ListHolder sample : List.of(
      new ListHolder(List.of(new Elem("a", 1), new Elem("b", 2), new Elem("c", 3))),
      new ListHolder(List.of()),
      new ListHolder(nullableList(new Elem("x", 7), null, new Elem("z", 9)))
    )) {
      assertLoopParity(ListHolder.class, ListHolder2.class, sample);
    }
    assertLoopParity(ListHolder.class, ListHolder2.class, new ListHolder(null));
    assertLoopParity(ListHolder.class, ListHolder2.class, null);
  }

  @Test
  @DisplayName("Set lift: MH loop == Java loop over the same Leaf element")
  void setLoopParity() {
    for (final SetHolder sample : List.of(
      new SetHolder(new LinkedHashSet<>(List.of(new Elem("a", 1), new Elem("b", 2)))),
      new SetHolder(Set.of()),
      new SetHolder(nullableSet(new Elem("x", 7), null))
    )) {
      assertLoopParity(SetHolder.class, SetHolder2.class, sample);
    }
    assertLoopParity(SetHolder.class, SetHolder2.class, new SetHolder(null));
  }

  @Test
  @DisplayName("Map-values lift: MH loop == Java loop over the same Leaf element")
  void mapLoopParity() {
    final Map<String, Elem> multi = new LinkedHashMap<>();
    multi.put("k1", new Elem("m1", 11));
    multi.put("k2", new Elem("m2", 12));
    final Map<String, Elem> withNullValue = new LinkedHashMap<>();
    withNullValue.put("present", new Elem("p", 1));
    withNullValue.put("absent", null);
    for (final MapHolder sample : List.of(
      new MapHolder(multi),
      new MapHolder(Map.of()),
      new MapHolder(withNullValue)
    )) {
      assertLoopParity(MapHolder.class, MapHolder2.class, sample);
    }
    assertLoopParity(MapHolder.class, MapHolder2.class, new MapHolder(null));
  }

  /**
   * Build the mapper under each toggle (MH loop cleared, Java loop set) and assert forward — and,
   * when the forward result is a non-null match, backward — are byte-identical. Rebuilds the mapper
   * per toggle so the flag is read at construction time.
   */
  private <A, B> void assertLoopParity(final Class<A> src, final Class<B> tgt, final A sample) {
    System.clearProperty(MhIso.CONTAINER_DISABLE_PROPERTY);
    final B mh = Telescope.mapper(src, tgt).forward(sample);
    System.setProperty(MhIso.CONTAINER_DISABLE_PROPERTY, "true");
    final B java = Telescope.mapper(src, tgt).forward(sample);
    assertEquals(java, mh, "forward diverged for " + sample);

    if (mh != null && Objects.equals(mh, java)) {
      System.clearProperty(MhIso.CONTAINER_DISABLE_PROPERTY);
      final A mhBack = Telescope.mapper(src, tgt).backward(mh);
      System.setProperty(MhIso.CONTAINER_DISABLE_PROPERTY, "true");
      final A javaBack = Telescope.mapper(src, tgt).backward(mh);
      assertEquals(javaBack, mhBack, "backward diverged for " + mh);
    }
  }

  private static List<Elem> nullableList(final Elem... es) {
    return new ArrayList<>(Arrays.asList(es));
  }

  private static Set<Elem> nullableSet(final Elem... es) {
    return new LinkedHashSet<>(Arrays.asList(es));
  }
}
