package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Telescope.path;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.ADDERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBean;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code Mapper.patchInto} on the runtime path: it writes into the target exactly the
 * properties {@code patch} would overlay, and only those, as {@code into} writes them. The
 * cross-path corpus holds the generated bridge to the same rows.
 */
class MapperPatchIntoTest {

  public static class Shelf {

    private String name;
    private int rank;
    private List<String> tags = new ArrayList<>();
    private int nameWrites;

    public Shelf() {}

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      nameWrites++;
      this.name = name;
    }

    public int getRank() {
      return rank;
    }

    public void setRank(final int rank) {
      this.rank = rank;
    }

    public List<String> getTags() {
      return tags;
    }

    public void setTags(final List<String> tags) {
      this.tags = tags;
    }

    int nameWrites() {
      return nameWrites;
    }
  }

  public record ShelfChanges(String name, Integer rank, List<String> tags) {}

  /** A shelf whose name has no setter: it is built through its constructor. */
  public static class FixedShelf {

    private final String name;
    private List<String> tags;

    public FixedShelf(final String name, final List<String> tags) {
      this.name = name;
      this.tags = tags;
    }

    public String getName() {
      return name;
    }

    public List<String> getTags() {
      return tags;
    }

    public void setTags(final List<String> tags) {
      this.tags = tags;
    }
  }

  public record FixedChanges(String name, List<String> tags) {}

  public static class AddedShelf {

    private final List<String> tags = new ArrayList<>();

    public AddedShelf() {}

    public List<String> getTags() {
      return tags;
    }

    public void addTag(final String tag) {
      tags.add(tag + "!");
    }
  }

  public record TagChanges(List<String> tags) {}

  public record Plain(String name) {}

  public static class Owner {

    private Plain plain;

    public Owner() {}

    public Plain getPlain() {
      return plain;
    }

    public void setPlain(final Plain plain) {
      this.plain = plain;
    }
  }

  public record OwnerChanges(String name) {}

  private static Shelf shelf() {
    final var shelf = new Shelf();
    shelf.setName("old");
    shelf.setRank(3);
    shelf.getTags().add("stale");
    return shelf;
  }

  @Test
  @DisplayName("writes the properties the partial sends, and only those, in place")
  void writesOnlyWhatThePartialSends() {
    final var mapper = Telescope.mapper(Shelf.class, ShelfChanges.class);
    final var target = shelf();
    final var tags = target.getTags();
    final var writesBefore = target.nameWrites();
    assertSame(target, mapper.patchInto(target, new ShelfChanges(null, 7, List.of("x", "y"))));
    assertEquals("old", target.getName());
    assertEquals(writesBefore, target.nameWrites(), "a property the partial leaves out is never written");
    assertEquals(7, target.getRank());
    assertSame(tags, target.getTags(), "the held list keeps its identity");
    assertEquals(List.of("x", "y"), tags);
  }

  @Test
  @DisplayName("leaves a list the partial leaves out as it was, and writes a scalar it sends")
  void leavesAnUnsentListAlone() {
    final var mapper = Telescope.mapper(Shelf.class, ShelfChanges.class);
    final var target = shelf();
    mapper.patchInto(target, new ShelfChanges("new", null, null));
    assertEquals("new", target.getName());
    assertEquals(3, target.getRank());
    assertEquals(List.of("stale"), target.getTags());
  }

  @Test
  @DisplayName("leaves the target as it was for a null partial, or one that sends nothing")
  void writesNothingForAnEmptyPartial() {
    final var mapper = Telescope.mapper(Shelf.class, ShelfChanges.class);
    final var target = shelf();
    final var writesBefore = target.nameWrites();
    assertSame(target, mapper.patchInto(target, null));
    assertSame(target, mapper.patchInto(target, new ShelfChanges(null, null, null)));
    assertEquals(writesBefore, target.nameWrites());
    assertEquals(List.of("stale"), target.getTags());
  }

  @Test
  @DisplayName("binds each property once per target class, and writes a second target as it wrote the first")
  void writesASecondTargetTheSameWay() {
    final var mapper = Telescope.mapper(Shelf.class, ShelfChanges.class);
    final var first = shelf();
    final var second = shelf();
    mapper.patchInto(first, new ShelfChanges("a", null, List.of("p")));
    mapper.patchInto(second, new ShelfChanges("b", null, List.of("q")));
    assertEquals("a", first.getName());
    assertEquals("b", second.getName());
    assertEquals(List.of("q"), second.getTags());
  }

  @Test
  @DisplayName("refuses a sent property the target has no setter for, by name, before writing anything")
  void refusesASentPropertyWithNoSetter() {
    final var mapper = Telescope.mapper(FixedShelf.class, FixedChanges.class);
    final var target = new FixedShelf("old", new ArrayList<>(List.of("stale")));
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      mapper.patchInto(target, new FixedChanges("new", List.of("x")))
    );
    assertEquals(
      PairingMessages.patchIntoPropertyUnwritable("FixedShelf", "FixedChanges", "FixedShelf", "name"),
      refused.getMessage()
    );
    assertEquals(List.of("stale"), target.getTags());
    mapper.patchInto(target, new FixedChanges(null, List.of("x")));
    assertEquals(List.of("x"), target.getTags(), "a property with no setter that the partial leaves out is fine");
  }

  @Test
  @DisplayName("fills a collection through its adder, once per element")
  void fillsThroughTheAdder() {
    final var mapper = Telescope.mapper(AddedShelf.class, TagChanges.class, writeBean(AddedShelf.class, ADDERS));
    final var target = new AddedShelf();
    target.getTags().add("stale");
    mapper.patchInto(target, new TagChanges(List.of("x")));
    assertEquals(List.of("x!"), target.getTags());
  }

  @Test
  @DisplayName("refuses a record target, naming it")
  void refusesARecord() {
    final var mapper = Telescope.mapper(Plain.class, OwnerChanges.class);
    final var refused = assertThrows(UnsupportedOperationException.class, () ->
      mapper.patchInto(new Plain("a"), new OwnerChanges("b"))
    );
    assertTrue(refused.getMessage().contains("Plain"), refused::getMessage);
    assertThrows(NullPointerException.class, () -> mapper.patchInto(null, new OwnerChanges("b")));
  }

  @Test
  @DisplayName("refuses a mapper with a row that writes through a source path, which names no property of the target")
  void refusesAPathRow() {
    final var mapper = Telescope.mapper(
      Owner.class,
      OwnerChanges.class,
      to(path(Owner::getPlain, Plain::name), OwnerChanges::name)
    );
    final var refused = assertThrows(UnsupportedOperationException.class, () ->
      mapper.patchInto(new Owner(), new OwnerChanges("b"))
    );
    assertTrue(refused.getMessage().contains("source path"), refused::getMessage);
  }

  @Test
  @DisplayName("refuses a mapper with nothing to overlay, such as one a lift builds")
  void refusesAMapperWithNothingToOverlay() {
    final var lifted = Telescope.mapper(Shelf.class, ShelfChanges.class).liftList();
    assertThrows(UnsupportedOperationException.class, () -> lifted.patchInto(List.of(), List.of()));
  }
}
