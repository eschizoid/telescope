package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.backReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * How a mapper ties the elements of a collection it writes to the owner holding it: the
 * back-reference a {@code backReference} row names, which each element the owner holds afterwards
 * is pointed at it through, the remover each element taken out goes through or the back-reference
 * it has cleared, and nothing read off a property's type alone.
 */
class CollectionTiesTest {

  public record Item(String label) {}

  public record Form(String name, List<Item> items) {}

  /** An element pointing back at its basket through the one property of the basket's type. */
  public static class Entry {

    private String label;
    private Basket basket;

    public Entry() {}

    public String getLabel() {
      return label;
    }

    public void setLabel(final String label) {
      this.label = label;
    }

    public Basket getBasket() {
      return basket;
    }

    public void setBasket(final Basket basket) {
      this.basket = basket;
    }
  }

  /** A basket with neither adder nor remover, whose list has a setter. */
  public static class Basket {

    private String name;
    private List<Entry> items = new ArrayList<>();

    public Basket() {}

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public List<Entry> getItems() {
      return items;
    }

    public void setItems(final List<Entry> items) {
      this.items = items;
    }
  }

  private static final Form FORM = new Form("weekly", List.of(new Item("a"), new Item("b")));

  private static Entry entry(final String label, final Basket basket) {
    final var entry = new Entry();
    entry.setLabel(label);
    entry.setBasket(basket);
    return entry;
  }

  @Nested
  @DisplayName("The back-reference a backReference row names")
  class Named {

    private static final Mapper<Form, Basket> NAMED = Telescope.mapper(
      Form.class,
      Basket.class,
      backReference(Entry::getBasket)
    );

    @Test
    @DisplayName("with no row, no adder and no mappedBy, a property of the owner's type is left as it is")
    void noRowSetsNothing() {
      final var basket = Telescope.mapper(Form.class, Basket.class).forward(FORM);
      for (final var item : basket.getItems()) assertNull(item.getBasket());
      final var held = new Basket();
      final var elsewhere = new Basket();
      final var stale = entry("stale", elsewhere);
      held.getItems().add(stale);
      Telescope.mapper(Form.class, Basket.class).into(held, FORM);
      for (final var item : held.getItems()) assertNull(item.getBasket());
      assertSame(elsewhere, stale.getBasket());
    }

    @Test
    @DisplayName("forward points each element it builds at the owner")
    void forwardPointsEachElementBack() {
      final var basket = NAMED.forward(FORM);
      assertEquals(List.of("a", "b"), basket.getItems().stream().map(Entry::getLabel).toList());
      for (final var item : basket.getItems()) assertSame(basket, item.getBasket());
    }

    @Test
    @DisplayName("into points each new element at the target and clears the one it takes out")
    void intoPointsBackAndClearsWhatItTakesOut() {
      final var basket = new Basket();
      final var stale = entry("stale", basket);
      basket.getItems().add(stale);
      final var held = basket.getItems();
      NAMED.into(basket, FORM);
      assertSame(held, basket.getItems());
      for (final var item : basket.getItems()) assertSame(basket, item.getBasket());
      assertNull(stale.getBasket());
    }

    @Test
    @DisplayName("an element taken out that now points at another owner is left pointing there")
    void anElementMovedElsewhereKeepsItsOwner() {
      final var basket = new Basket();
      final var other = new Basket();
      final var moved = entry("moved", other);
      basket.getItems().add(moved);
      NAMED.into(basket, FORM);
      assertSame(other, moved.getBasket());
    }

    @Test
    @DisplayName("an edit mapper's patchInto writes the sent list into the held one and ties it the same way")
    void patchIntoTiesTheSentList() {
      final var basket = new Basket();
      basket.setName("kept");
      final var stale = entry("stale", basket);
      basket.getItems().add(stale);
      Telescope.edits(Form.class, Basket.class, backReference(Entry::getBasket)).patchInto(
        basket,
        new Form(null, List.of(new Item("x")))
      );
      assertEquals("kept", basket.getName());
      assertEquals(List.of("x"), basket.getItems().stream().map(Entry::getLabel).toList());
      assertSame(basket, basket.getItems().getFirst().getBasket());
      assertNull(stale.getBasket());
    }

    @Test
    @DisplayName("explain says the list is refilled and the back-reference set and cleared")
    void explainSaysHow() {
      assertEquals(
        List.of(
          "Basket.items: refilled in place, back-reference Entry.basket set by backReference and cleared on removal"
        ),
        NAMED.explain().collections().stream().map(Object::toString).toList()
      );
    }
  }

  /** A basket whose remover records what it was handed, with no adder. */
  public static class Tray {

    private final List<Entry> items = new ArrayList<>();
    private final List<String> removed = new ArrayList<>();

    public Tray() {}

    public List<Entry> getItems() {
      return items;
    }

    public void setItems(final List<Entry> given) {
      items.clear();
      items.addAll(given);
    }

    public void removeEntry(final Entry entry) {
      items.remove(entry);
      removed.add(entry.getLabel());
    }

    public List<String> getRemoved() {
      return removed;
    }
  }

  public record TrayForm(List<Item> items) {}

  @Test
  @DisplayName("each element into takes out goes through the owner's remover, found by the type it takes")
  void anElementTakenOutGoesThroughTheRemover() {
    final var tray = new Tray();
    tray.getItems().add(entry("stale", null));
    Telescope.mapper(TrayForm.class, Tray.class).into(tray, new TrayForm(List.of(new Item("a"))));
    assertEquals(List.of("stale"), tray.getRemoved());
    assertEquals(List.of("a"), tray.getItems().stream().map(Entry::getLabel).toList());
  }

  /** An element with two properties of its owner's type. */
  public static class Leg {

    private String label;
    private Trip trip;
    private Trip previous;

    public Leg() {}

    public String getLabel() {
      return label;
    }

    public void setLabel(final String label) {
      this.label = label;
    }

    public Trip getTrip() {
      return trip;
    }

    public void setTrip(final Trip trip) {
      this.trip = trip;
    }

    public Trip getPrevious() {
      return previous;
    }

    public void setPrevious(final Trip previous) {
      this.previous = previous;
    }
  }

  public static class Trip {

    private List<Leg> legs = new ArrayList<>();

    public Trip() {}

    public List<Leg> getLegs() {
      return legs;
    }

    public void setLegs(final List<Leg> legs) {
      this.legs = legs;
    }
  }

  public record TripForm(List<Item> legs) {}

  private static final TripForm TRIP = new TripForm(List.of(new Item("a")));

  @Nested
  @DisplayName("Properties of the owner's type, and the row naming one")
  class Undecided {

    @Test
    @DisplayName("with nothing naming one, none is set")
    void noneIsSet() {
      final var mapper = Telescope.mapper(TripForm.class, Trip.class);
      final var trip = mapper.forward(TRIP);
      assertNull(trip.getLegs().getFirst().getTrip());
      assertNull(trip.getLegs().getFirst().getPrevious());
      assertEquals(
        List.of("Trip.legs: refilled in place"),
        mapper.explain().collections().stream().map(Object::toString).toList()
      );
    }

    @Test
    @DisplayName("a backReference row names the one to set")
    void aRowNamesIt() {
      final var mapper = Telescope.mapper(TripForm.class, Trip.class, backReference(Leg::getTrip));
      final var trip = mapper.forward(TRIP);
      assertSame(trip, trip.getLegs().getFirst().getTrip());
      assertNull(trip.getLegs().getFirst().getPrevious());
      assertEquals(
        List.of("Trip.legs: refilled in place, back-reference Leg.trip set by backReference and cleared on removal"),
        mapper.explain().collections().stream().map(Object::toString).toList()
      );
    }

    @Test
    @DisplayName("a backReference row on a class of no element the mapper converts is refused by name")
    void anUnusedRowIsRefused() {
      final var thrown = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(TripForm.class, Trip.class, backReference(Entry::getBasket))
      );
      assertEquals(
        PairingMessages.backReferenceRowUnused("TripForm", "Trip", "Entry", "getBasket"),
        thrown.getMessage()
      );
    }

    @Test
    @DisplayName("a backReference row whose property is of another type than the owner's is refused by name")
    void aRowOfAnotherTypeIsRefused() {
      final var thrown = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(TripForm.class, Trip.class, backReference(Leg::getLabel))
      );
      assertEquals(
        PairingMessages.backReferenceRowNotOwner("TripForm", "Trip", "Leg", "getLabel", "String", List.of("Trip")),
        thrown.getMessage()
      );
    }

    @Test
    @DisplayName("two backReference rows for one element class are refused")
    void twoRowsForOneClassAreRefused() {
      final var thrown = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(TripForm.class, Trip.class, backReference(Leg::getTrip), backReference(Leg::getPrevious))
      );
      assertEquals(PairingMessages.duplicateBackReferenceRow("Leg"), thrown.getMessage());
    }
  }

  /** A box built by its builder, whose entries point back at it. */
  public static final class Box {

    private final String name;
    private final List<Slot> slots;

    private Box(final String name, final List<Slot> slots) {
      this.name = name;
      this.slots = slots;
    }

    public String getName() {
      return name;
    }

    public List<Slot> getSlots() {
      return slots;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private String name;
      private List<Slot> slots;

      public Builder name(final String name) {
        this.name = name;
        return this;
      }

      public Builder slots(final List<Slot> slots) {
        this.slots = slots;
        return this;
      }

      public Box build() {
        return new Box(name, slots);
      }
    }
  }

  public static class Slot {

    private String label;
    private Box box;

    public Slot() {}

    public String getLabel() {
      return label;
    }

    public void setLabel(final String label) {
      this.label = label;
    }

    public Box getBox() {
      return box;
    }

    public void setBox(final Box box) {
      this.box = box;
    }
  }

  public record BoxForm(String name, List<Item> slots) {}

  @Test
  @DisplayName("an owner its builder builds whole has each element pointed back at it once it is built")
  void aBuiltOwnerIsPointedAtOnceBuilt() {
    final var box = Telescope.mapper(BoxForm.class, Box.class, backReference(Slot::getBox)).forward(
      new BoxForm("b", List.of(new Item("a")))
    );
    assertSame(box, box.getSlots().getFirst().getBox());
  }

  @Test
  @DisplayName("elements a copy hands across as the source holds them are not pointed at the copy")
  void handedAcrossElementsAreLeftAlone() {
    final var basket = new Basket();
    basket.setName("source");
    final var item = entry("a", basket);
    basket.getItems().add(item);
    final var copy = Telescope.mapper(Basket.class, Basket.class).forward(basket);
    assertSame(item, copy.getItems().getFirst());
    assertSame(basket, item.getBasket());
  }

  /** An item that names the basket it belongs to itself. */
  public record Claimed(String label, Basket basket) {}

  public record ClaimedForm(String name, List<Claimed> items) {}

  @Test
  @DisplayName("a backReference row naming a property the element's conversion fills by name is refused")
  void aRowOnAFilledPropertyIsRefused() {
    final var thrown = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(ClaimedForm.class, Basket.class, backReference(Entry::getBasket))
    );
    assertEquals(
      PairingMessages.backReferenceRowFilled("ClaimedForm", "Basket", "Entry", "getBasket"),
      thrown.getMessage()
    );
  }

  /** An element equal to another of its label. */
  public static final class Tag {

    private String label;

    public Tag() {}

    public String getLabel() {
      return label;
    }

    public void setLabel(final String label) {
      this.label = label;
    }

    @Override
    public boolean equals(final Object other) {
      return other instanceof Tag tag && label.equals(tag.label);
    }

    @Override
    public int hashCode() {
      return label.hashCode();
    }
  }

  /** A holder of tags whose remover takes the tag out by equality and records it. */
  public static class Shelf {

    private final List<Tag> tags = new ArrayList<>();
    private final List<String> removed = new ArrayList<>();

    public Shelf() {}

    public List<Tag> getTags() {
      return tags;
    }

    public void setTags(final List<Tag> given) {
      tags.clear();
      tags.addAll(given);
    }

    public void removeTag(final Tag tag) {
      tags.remove(tag);
      removed.add(tag.getLabel());
    }

    public List<String> getRemoved() {
      return removed;
    }
  }

  public record ShelfForm(List<Item> tags) {}

  @Test
  @DisplayName(
    "an element taken out whose equal the write put back is not handed to the remover, which would take both"
  )
  void anElementWhoseEqualIsBackSkipsTheRemover() {
    final var shelf = new Shelf();
    final var held = new Tag();
    held.setLabel("a");
    shelf.getTags().add(held);
    Telescope.mapper(ShelfForm.class, Shelf.class).into(shelf, new ShelfForm(List.of(new Item("a"))));
    assertEquals(List.of("a"), shelf.getTags().stream().map(Tag::getLabel).toList());
    assertEquals(List.of(), shelf.getRemoved());
  }
}
