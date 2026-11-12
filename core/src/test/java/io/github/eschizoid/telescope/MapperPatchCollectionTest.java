package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.ADDERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBean;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins how {@code Mapper.patch} writes a container property the partial supplies: into the
 * container a bean base holds, so the result keeps that instance, and as the converted container
 * where the base is a record, the held one cannot change, or the rebuild fills it through adders.
 */
class MapperPatchCollectionTest {

  public static class Shelf {

    private String name;
    private List<String> tags = new ArrayList<>();
    private Map<String, Integer> counts = new HashMap<>();

    public Shelf() {}

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public List<String> getTags() {
      return tags;
    }

    public void setTags(final List<String> tags) {
      this.tags = tags;
    }

    public Map<String, Integer> getCounts() {
      return counts;
    }

    public void setCounts(final Map<String, Integer> counts) {
      this.counts = counts;
    }
  }

  public record ShelfPatch(String name, List<String> tags, Map<String, Integer> counts) {}

  public static class AddedShelf {

    private final List<String> tags = new ArrayList<>();

    public AddedShelf() {}

    public List<String> getTags() {
      return tags;
    }

    public void addTag(final String tag) {
      tags.add(tag);
    }
  }

  public record TagsPatch(List<String> tags) {}

  public record ShelfValue(String name, List<String> tags) {}

  public record ValuePatch(String name, List<String> tags) {}

  private static Shelf shelf(final List<String> tags) {
    final var shelf = new Shelf();
    shelf.setName("old");
    shelf.setTags(tags);
    shelf.getCounts().put("stale", 0);
    return shelf;
  }

  @Test
  @DisplayName("a list and a map a bean base holds are refilled, so the result holds the base's own instances")
  void refillsTheBeanBasesContainers() {
    final var mapper = Telescope.mapper(Shelf.class, ShelfPatch.class);
    final var base = shelf(new ArrayList<>(List.of("stale")));
    final var tags = base.getTags();
    final var counts = base.getCounts();
    final var patched = mapper.patch(base, new ShelfPatch(null, List.of("x", "y"), Map.of("k", 1)));
    assertNotSame(base, patched);
    assertSame(tags, patched.getTags());
    assertSame(counts, patched.getCounts());
    assertEquals(List.of("x", "y"), tags);
    assertEquals(Map.of("k", 1), counts);
    assertEquals("old", patched.getName());
  }

  @Test
  @DisplayName("an unmodifiable list a bean base holds is passed over, and the base keeps it")
  void passesOverAnUnmodifiableHeldList() {
    final var mapper = Telescope.mapper(Shelf.class, ShelfPatch.class);
    final var base = shelf(List.of("stale"));
    final var patched = mapper.patch(base, new ShelfPatch(null, List.of("x"), null));
    assertEquals(List.of("x"), patched.getTags());
    assertEquals(List.of("stale"), base.getTags());
  }

  @Test
  @DisplayName("a collection the rebuild fills through its adders goes into the new bean's own, and the base keeps its")
  void leavesAnAdderFilledCollectionToTheAdders() {
    final var mapper = Telescope.mapper(AddedShelf.class, TagsPatch.class, writeBean(AddedShelf.class, ADDERS));
    final var base = new AddedShelf();
    base.addTag("stale");
    final var patched = mapper.patch(base, new TagsPatch(List.of("x")));
    assertNotSame(base.getTags(), patched.getTags());
    assertEquals(List.of("x"), patched.getTags());
    assertEquals(List.of("stale"), base.getTags());
  }

  @Test
  @DisplayName("a record base's list is never refilled")
  void neverRefillsARecordBase() {
    final var mapper = Telescope.mapper(ShelfValue.class, ValuePatch.class);
    final var tags = new ArrayList<>(List.of("stale"));
    final var patched = mapper.patch(new ShelfValue("old", tags), new ValuePatch(null, List.of("x")));
    assertEquals(List.of("x"), patched.tags());
    assertEquals(List.of("stale"), tags);
  }
}
