package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Telescope.edits;
import static io.github.eschizoid.telescope.Telescope.path;
import static io.github.eschizoid.telescope.mapping.Mapping.SETTERS;
import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.constant;
import static io.github.eschizoid.telescope.mapping.Mapping.drop;
import static io.github.eschizoid.telescope.mapping.Mapping.keep;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.toOrElse;
import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static io.github.eschizoid.telescope.mapping.Mapping.when;
import static io.github.eschizoid.telescope.mapping.Mapping.writeBean;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.EditMapper;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.introspection.OpticNode;
import io.github.eschizoid.telescope.mapping.MapStep;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code Telescope.edits} on the runtime path: rows read from the partial and write into the
 * target, {@code patchInto} writes only what the partial sends, in place, {@code into} every
 * property the mapper accounts for, and the mapper is strict on both sides with {@code keep} for a
 * property no edit writes. The cross-path corpus holds the generated bridge to the same rows.
 */
class EditMapperTest {

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

  public record Plain(String name, int size) {}

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

  /** An item with a price the edit computes, a code no edit changes, and a label with a default. */
  public static class Item {

    private String code;
    private String sku;
    private int price;
    private String label;

    public Item() {}

    public String getCode() {
      return code;
    }

    public void setCode(final String code) {
      this.code = code;
    }

    public String getSku() {
      return sku;
    }

    public void setSku(final String sku) {
      this.sku = sku;
    }

    public int getPrice() {
      return price;
    }

    public void setPrice(final int price) {
      this.price = price;
    }

    public String getLabel() {
      return label;
    }

    public void setLabel(final String label) {
      this.label = label;
    }
  }

  public record ItemChanges(String sku, String label, Integer price) {}

  /** Prices an item from its SKU, as a catalog would. */
  public static final class Prices {

    private Prices() {}

    public static Integer of(final ItemChanges changes) {
      return changes.sku() == null ? null : changes.sku().length() * 10;
    }
  }

  public static class Basket {

    private String owner;
    private final List<Item> items = new ArrayList<>();

    public Basket() {}

    public String getOwner() {
      return owner;
    }

    public void setOwner(final String owner) {
      this.owner = owner;
    }

    public List<Item> getItems() {
      return items;
    }

    public void addItem(final Item item) {
      items.add(item);
    }
  }

  public record BasketChanges(List<ItemChanges> items) {}

  private static final EditMapper<ItemChanges, Item> ITEM = edits(
    ItemChanges.class,
    Item.class,
    compute(Item::getPrice, Prices::of),
    drop(ItemChanges::price),
    toOrElse(ItemChanges::label, Item::getLabel, "none"),
    keep(Item::getCode)
  );

  private static Shelf shelf() {
    final var shelf = new Shelf();
    shelf.setName("old");
    shelf.setRank(3);
    shelf.getTags().add("stale");
    return shelf;
  }

  private static Item item() {
    final var item = new Item();
    item.setCode("C-1");
    item.setSku("old");
    item.setPrice(1);
    item.setLabel("held");
    return item;
  }

  @Test
  @DisplayName("patchInto writes the properties the partial sends, and only those, in place")
  void writesOnlyWhatThePartialSends() {
    final var mapper = edits(ShelfChanges.class, Shelf.class);
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
  @DisplayName("patchInto leaves a list the partial leaves out as it was, and empties one sent empty")
  void leavesAnUnsentListAlone() {
    final var mapper = edits(ShelfChanges.class, Shelf.class);
    final var target = shelf();
    mapper.patchInto(target, new ShelfChanges("new", null, null));
    assertEquals("new", target.getName());
    assertEquals(3, target.getRank(), "a wrapper the partial leaves null is not written into the primitive");
    assertEquals(List.of("stale"), target.getTags());
    mapper.patchInto(target, new ShelfChanges(null, null, List.of()));
    assertEquals(List.of(), target.getTags(), "a container sent empty empties the collection");
  }

  @Test
  @DisplayName("patchInto leaves the target as it was for a null partial, or one that sends nothing")
  void writesNothingForAnEmptyPartial() {
    final var mapper = edits(ShelfChanges.class, Shelf.class);
    final var target = shelf();
    final var writesBefore = target.nameWrites();
    assertSame(target, mapper.patchInto(target, null));
    assertSame(target, mapper.patchInto(target, new ShelfChanges(null, null, null)));
    assertEquals(writesBefore, target.nameWrites());
    assertEquals(List.of("stale"), target.getTags());
  }

  @Test
  @DisplayName("patchInto binds each property once per target class, and writes a second target as the first")
  void writesASecondTargetTheSameWay() {
    final var mapper = edits(ShelfChanges.class, Shelf.class);
    final var first = shelf();
    final var second = shelf();
    mapper.patchInto(first, new ShelfChanges("a", null, List.of("p")));
    mapper.patchInto(second, new ShelfChanges("b", null, List.of("q")));
    assertEquals("a", first.getName());
    assertEquals("b", second.getName());
    assertEquals(List.of("q"), second.getTags());
  }

  @Test
  @DisplayName("patchInto refuses a sent property the target has no setter for, by name, before writing anything")
  void refusesASentPropertyWithNoSetter() {
    final var mapper = edits(FixedChanges.class, FixedShelf.class);
    final var target = new FixedShelf("old", new ArrayList<>(List.of("stale")));
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      mapper.patchInto(target, new FixedChanges("new", List.of("x")))
    );
    assertEquals(
      PairingMessages.patchIntoPropertyUnwritable("FixedChanges", "FixedShelf", "FixedShelf", "name"),
      refused.getMessage()
    );
    assertEquals(List.of("stale"), target.getTags());
    mapper.patchInto(target, new FixedChanges(null, List.of("x")));
    assertEquals(List.of("x"), target.getTags(), "a property with no setter that the partial leaves out is fine");
  }

  @Test
  @DisplayName("patchInto fills a collection through its adder, once per element")
  void fillsThroughTheAdder() {
    final var mapper = edits(TagChanges.class, AddedShelf.class, writeBean(AddedShelf.class, SETTERS));
    final var target = new AddedShelf();
    target.getTags().add("stale");
    mapper.patchInto(target, new TagChanges(List.of("x")));
    assertEquals(List.of("x!"), target.getTags());
  }

  @Test
  @DisplayName("into, patchInto and patch refuse a record target, naming it")
  void refusesARecord() {
    final var mapper = edits(OwnerChanges.class, Plain.class, to(OwnerChanges::name, Plain::name), keep(Plain::size));
    final var refused = assertThrows(UnsupportedOperationException.class, () ->
      mapper.patchInto(new Plain("a", 1), new OwnerChanges("b"))
    );
    assertTrue(refused.getMessage().contains("Plain"), refused::getMessage);
    assertThrows(UnsupportedOperationException.class, () -> mapper.into(new Plain("a", 1), new OwnerChanges("b")));
    assertThrows(NullPointerException.class, () -> mapper.patchInto(null, new OwnerChanges("b")));
    assertEquals(new Plain("b", 1), mapper.patch(new Plain("a", 1), new OwnerChanges("b")), "patch copies a record");
  }

  @Test
  @DisplayName("a target path is written into the objects the target holds, building one only where it holds null")
  void writesATargetPathInPlace() {
    final var mapper = edits(
      OwnerChanges.class,
      Owner.class,
      to(OwnerChanges::name, path(Owner::getPlain, Plain::name))
    );
    final var target = new Owner();
    target.setPlain(new Plain("old", 4));
    mapper.patchInto(target, new OwnerChanges("new"));
    assertEquals(new Plain("new", 4), target.getPlain(), "the record on the path is rebuilt from the one it holds");
    mapper.patchInto(target, new OwnerChanges(null));
    assertEquals(new Plain("new", 4), target.getPlain(), "a path the partial leaves null is not written");
    final var empty = new Owner();
    mapper.patchInto(empty, new OwnerChanges("built"));
    assertEquals(new Plain("built", 0), empty.getPlain());
  }

  @Test
  @DisplayName("compute runs on the way in on every operation, and keep leaves the property as the target holds it")
  void computesOnTheWayIn() {
    final var built = ITEM.forward(new ItemChanges("mug", null, 999));
    assertEquals(30, built.getPrice(), "the price comes from the computation, never from the partial");
    assertEquals("none", built.getLabel(), "forward writes the default of toOrElse for a null");
    assertNull(built.getCode(), "forward builds a kept property as a new item holds it");

    final var held = item();
    ITEM.patchInto(held, new ItemChanges("plate", null, 1));
    assertEquals(50, held.getPrice());
    assertEquals("plate", held.getSku());
    assertEquals("held", held.getLabel(), "patchInto writes no default for a property the partial leaves out");
    assertEquals("C-1", held.getCode());

    final var put = item();
    ITEM.into(put, new ItemChanges(null, null, 1));
    assertNull(put.getSku(), "into writes a null the partial gives");
    assertEquals("none", put.getLabel());
    assertEquals(0, put.getPrice(), "a computed null is written into a primitive as into writes it");
    assertEquals("C-1", put.getCode(), "into leaves a kept property alone");

    final var base = item();
    final var copy = ITEM.patch(base, new ItemChanges("cup", null, null));
    assertNotSame(base, copy);
    assertEquals("old", base.getSku(), "patch leaves the base as it was");
    assertEquals("cup", copy.getSku());
    assertEquals(30, copy.getPrice());
    assertEquals("held", copy.getLabel());
    assertEquals("C-1", copy.getCode());
  }

  @Test
  @DisplayName("a via row naming an edit mapper converts each element through it, computing on the way in")
  void convertsElementsThroughAnEditMapper() {
    final var mapper = edits(
      BasketChanges.class,
      Basket.class,
      via(BasketChanges::items, Basket::getItems, ITEM),
      keep(Basket::getOwner)
    );
    final var held = new Basket();
    held.setOwner("ann");
    held.getItems().add(item());
    final var items = held.getItems();
    mapper.patchInto(held, new BasketChanges(List.of(new ItemChanges("bowl", "blue", 1))));
    assertSame(items, held.getItems(), "the held list keeps its identity");
    assertEquals(1, items.size());
    assertEquals("bowl", items.getFirst().getSku());
    assertEquals(40, items.getFirst().getPrice());
    assertEquals("ann", held.getOwner());
  }

  @Test
  @DisplayName("refuses, when built, a target property nothing accounts for, offering keep")
  void refusesAnUnaccountedTargetProperty() {
    final var refused = assertThrows(IllegalStateException.class, () ->
      edits(ItemChanges.class, Item.class, compute(Item::getPrice, Prices::of), drop(ItemChanges::price))
    );
    assertEquals(PairingMessages.editUnfilled("ItemChanges", "Item", "property", "code", false), refused.getMessage());
  }

  @Test
  @DisplayName("refuses, when built, a source property nothing reads")
  void refusesAnUnreadSourceProperty() {
    final var refused = assertThrows(IllegalStateException.class, () ->
      edits(ItemChanges.class, Item.class, compute(Item::getPrice, Prices::of), keep(Item::getCode))
    );
    assertEquals(
      PairingMessages.noSameNameTarget("ItemChanges", "Item", "field", "property", "price"),
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("refuses a keep beside a row that writes the same property, in either order")
  void refusesAKeepBesideAWrite() {
    final var expected = PairingMessages.keepBesideWrite("OwnerChanges", "Plain", "name");
    for (final var rows : List.<List<MapStep>>of(
      List.of(keep(Plain::name), to(OwnerChanges::name, Plain::name), keep(Plain::size)),
      List.of(to(OwnerChanges::name, Plain::name), keep(Plain::name), keep(Plain::size)),
      List.of(constant(Plain::name, "x"), keep(Plain::name), keep(Plain::size), drop(OwnerChanges::name))
    )) {
      final var refused = assertThrows(IllegalArgumentException.class, () ->
        edits(OwnerChanges.class, Plain.class, rows.toArray(MapStep[]::new))
      );
      assertEquals(expected, refused.getMessage());
    }
  }

  @Test
  @DisplayName("every other factory refuses keep, and a via row naming an edit mapper, by name")
  void otherFactoriesRefuseEditRows() {
    final var kept = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapperForward(OwnerChanges.class, Plain.class, keep(Plain::size))
    );
    assertEquals(
      PairingMessages.keepOutsideEdits("Telescope.mapperForward", "OwnerChanges", "Plain", "size"),
      kept.getMessage()
    );
    final var via = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(BasketChanges.class, Basket.class, via(BasketChanges::items, Basket::getItems, ITEM))
    );
    assertEquals(
      PairingMessages.viaEditsOutsideEdits("Telescope.mapper", "BasketChanges", "Basket", "items"),
      via.getMessage()
    );
  }

  @Test
  @DisplayName("explain reports a kept property")
  void explainsAKeptProperty() {
    assertTrue(ITEM.explain().nodes().contains(new OpticNode.Skipped("code", OpticNode.Reason.KEPT)), () ->
      ITEM.explain().toString()
    );
  }

  @Test
  @DisplayName("explain and trace print a kept property as one no edit writes")
  void printsAKeptProperty() {
    assertTrue(ITEM.explain().toString().contains("kept, never written by an edit"), () -> ITEM.explain().toString());
    final var trace = ITEM.trace(new ItemChanges("ab", null, null)).toString();
    assertTrue(trace.contains("code") && trace.contains("kept, never written by an edit"), trace);
  }

  @Test
  @DisplayName("patch gives null for a null base, and a copy of the base for a partial that sends nothing")
  void patchOfNothing() {
    final var mapper = edits(
      OwnerChanges.class,
      Owner.class,
      to(OwnerChanges::name, path(Owner::getPlain, Plain::name))
    );
    assertNull(mapper.patch(null, new OwnerChanges("new")));
    final var base = new Owner();
    base.setPlain(new Plain("old", 4));
    for (final var partial : Arrays.asList(null, new OwnerChanges(null))) {
      final var copy = mapper.patch(base, partial);
      assertNotSame(base, copy);
      assertEquals(new Plain("old", 4), copy.getPlain());
    }
  }

  @Test
  @DisplayName("a target path under when is written only where its predicate accepts the partial, on every operation")
  void aGatedTargetPath() {
    final var mapper = edits(
      OwnerChanges.class,
      Owner.class,
      when(changes -> !changes.name().isBlank(), to(OwnerChanges::name, path(Owner::getPlain, Plain::name)))
    );
    final var base = new Owner();
    base.setPlain(new Plain("old", 4));
    assertEquals(new Plain("old", 4), mapper.patch(base, new OwnerChanges(" ")).getPlain(), "patch: rejected");
    assertEquals(new Plain("new", 4), mapper.patch(base, new OwnerChanges("new")).getPlain(), "patch: accepted");
    mapper.patchInto(base, new OwnerChanges(" "));
    assertEquals(new Plain("old", 4), base.getPlain(), "patchInto: rejected");
    mapper.into(base, new OwnerChanges(" "));
    assertEquals(new Plain("old", 4), base.getPlain(), "into: rejected");
    mapper.into(base, new OwnerChanges("new"));
    assertEquals(new Plain("new", 4), base.getPlain(), "into: accepted");
  }

  /** A bean holding a list that cannot be changed, with no setter to give it another. */
  public static class Frozen {

    private String name;
    private List<String> tags = List.of("held");

    public Frozen() {}

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public List<String> getTags() {
      return tags;
    }

    /** Adds a tag by replacing the list with a longer one that cannot be changed either. */
    public void addTag(final String tag) {
      final var more = new ArrayList<>(tags);
      more.add(tag);
      tags = List.copyOf(more);
    }
  }

  public record FrozenChanges(String name, List<String> tags) {}

  @Test
  @DisplayName("a refusal of a container write names the operation that was called, patchInto or into")
  void aContainerRefusalNamesTheOperation() {
    final var mapper = edits(FrozenChanges.class, Frozen.class);
    for (final var operation : List.of("patchInto", "into")) {
      final var refused = assertThrows(IllegalArgumentException.class, () -> {
        if (operation.equals("into")) mapper.into(new Frozen(), new FrozenChanges("n", List.of("sent")));
        else mapper.patchInto(new Frozen(), new FrozenChanges("n", List.of("sent")));
      });
      assertEquals(
        PairingMessages.intoUnwritable(
          operation,
          "FrozenChanges",
          "Frozen",
          "Frozen",
          "tags",
          List.of("held").getClass().getName()
        ),
        refused.getMessage()
      );
    }
  }
}
