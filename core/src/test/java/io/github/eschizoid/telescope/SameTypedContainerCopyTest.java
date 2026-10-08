package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A component whose source and target declare the same container type is copied on the reflective
 * path, so a change to the target's container never reaches the source. The copy is shallow, an
 * input nothing can modify is handed across as itself, and a row with its own functions is applied
 * as written.
 */
class SameTypedContainerCopyTest {

  record Tag(String value) {}

  record Src(List<String> items) {}

  record Tgt(List<String> items) {}

  record SetSrc(Set<String> items) {}

  record SetTgt(Set<String> items) {}

  record MapSrc(Map<String, String> items) {}

  record MapTgt(Map<String, String> items) {}

  record SortedSrc(SortedSet<String> items) {}

  record SortedTgt(SortedSet<String> items) {}

  record TagSrc(List<Tag> items) {}

  record TagTgt(List<Tag> items) {}

  @Test
  @DisplayName("a modifiable list is copied, and a change to the target's list leaves the source's alone")
  void aModifiableListIsCopied() {
    final var items = new ArrayList<>(List.of("b", "a"));
    final var mapped = Telescope.mapper(Src.class, Tgt.class).forward(new Src(items));
    assertNotSame(items, mapped.items());
    mapped.items().add("z");
    assertEquals(List.of("b", "a"), items);
  }

  @Test
  @DisplayName("the copy is shallow: the elements of a same-typed list are the source's own")
  void theCopyIsShallow() {
    final var tag = new Tag("t");
    final var items = new ArrayList<>(List.of(tag));
    final var mapped = Telescope.mapper(TagSrc.class, TagTgt.class).forward(new TagSrc(items));
    assertNotSame(items, mapped.items());
    assertSame(tag, mapped.items().getFirst());
  }

  @Test
  @DisplayName("a sorted set's copy keeps the source's comparator")
  void aSortedCopyKeepsItsComparator() {
    final Comparator<String> reverse = Comparator.reverseOrder();
    final var items = new TreeSet<>(reverse);
    items.addAll(List.of("a", "b"));
    final var mapped = Telescope.mapper(SortedSrc.class, SortedTgt.class).forward(new SortedSrc(items));
    assertNotSame(items, mapped.items());
    assertSame(reverse, mapped.items().comparator());
    assertEquals(List.of("b", "a"), List.copyOf(mapped.items()));
  }

  @Test
  @DisplayName("a container nothing can modify is handed across as itself")
  void anUnmodifiableInputIsHandedAcross() {
    final var lists = List.<List<String>>of(
      List.of(),
      List.of("a"),
      List.of("a", "b", "c"),
      List.of("a", "b", "c").subList(0, 2),
      Stream.of("a", "b").collect(Collectors.toUnmodifiableList()),
      Collections.unmodifiableList(new ArrayList<>(List.of("a"))),
      Collections.emptyList(),
      Collections.singletonList("a")
    );
    final var listMapper = Telescope.mapper(Src.class, Tgt.class);
    for (final var items : lists) {
      assertSame(items, listMapper.forward(new Src(items)).items(), items.getClass().getName());
      assertSame(items, listMapper.backward(new Tgt(items)).items(), items.getClass().getName());
    }
    final var setMapper = Telescope.mapper(SetSrc.class, SetTgt.class);
    for (final var items : List.<Set<String>>of(Set.of("a"), Set.of("a", "b", "c"), Collections.emptySet())) {
      assertSame(items, setMapper.forward(new SetSrc(items)).items(), items.getClass().getName());
    }
    final var mapMapper = Telescope.mapper(MapSrc.class, MapTgt.class);
    for (final var items : List.<Map<String, String>>of(
      Map.of("k", "v"),
      Map.of("k", "v", "l", "w"),
      Collections.unmodifiableMap(new LinkedHashMap<>(Map.of("k", "v"))),
      Collections.emptyMap()
    )) {
      assertSame(items, mapMapper.forward(new MapSrc(items)).items(), items.getClass().getName());
    }
  }

  @Test
  @DisplayName("a null container maps to null in both directions")
  void aNullContainerStaysNull() {
    final var mapper = Telescope.mapper(Src.class, Tgt.class);
    assertNull(mapper.forward(new Src(null)).items());
    assertNull(mapper.backward(new Tgt(null)).items());
  }

  @Test
  @DisplayName("a to(...) row with its own functions hands the source's container across as written")
  void aRowWithItsOwnFunctionsShares() {
    final var items = new ArrayList<>(List.of("b", "a"));
    final var mapper = Telescope.mapper(Src.class, Tgt.class, to(Src::items, Tgt::items, x -> x, x -> x));
    assertSame(items, mapper.forward(new Src(items)).items());
    assertSame(items, mapper.backward(new Tgt(items)).items());
  }
}
