package io.github.eschizoid.telescope.containerparity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.SynchronousQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A field declared as the general container interfaces rather than as a {@code List}, {@code Set}
 * or {@code Map}. Widening a collection field to {@code Collection} is an ordinary shape, and so is
 * keeping a {@code Deque} on one side of a pair; neither converted before.
 *
 * <p>The interfaces are what is answerable here, and the last row says why it stops there. A
 * concrete queue may be capacity-bounded, and one that cannot hold its source has to refuse while
 * the plan is built rather than on every conversion.
 */
class GeneralContainerKindTest {

  record Elem(String v) {}

  record Dto(String v) {}

  record CollectionSrc(Collection<Elem> items) {}

  record CollectionDst(Collection<Dto> items) {}

  record CollectionBoth(Collection<Elem> items) {}

  record CollectionBothDto(Collection<Dto> items) {}

  record SetSrc(Set<Elem> items) {}

  record SetDst(Set<Dto> items) {}

  record DequeSrc(Deque<Elem> items) {}

  record DequeDst(Deque<Dto> items) {}

  record ListSrc(List<Elem> items) {}

  record SyncQueueDst(SynchronousQueue<Dto> items) {}

  @Test
  @DisplayName("a Collection-declared field converts, keeping its elements in order")
  void collectionConverts() {
    final Collection<Elem> items = new LinkedHashSet<>();
    items.add(new Elem("a"));
    items.add(new Elem("b"));

    final var out = Telescope.mapper(CollectionSrc.class, CollectionDst.class).forward(new CollectionSrc(items));

    assertEquals(List.of(new Dto("a"), new Dto("b")), List.copyOf(out.items()));
  }

  @Test
  @DisplayName("a set widens into a Collection target, and is rebuilt as a set rather than a list")
  void setWidensIntoCollection() {
    // The Collection side has no shape of its own, so it takes the set's: what is built is a set,
    // which is why the round trip below holds. Rebuilding a list here would let a duplicate into a
    // field whose other side cannot hold one.
    final var items = new LinkedHashSet<Elem>();
    items.add(new Elem("a"));
    items.add(new Elem("b"));

    final var mapper = Telescope.mapper(SetSrc.class, CollectionDst.class);
    final var out = mapper.forward(new SetSrc(items));

    assertEquals(List.of(new Dto("a"), new Dto("b")), List.copyOf(out.items()));
    assertInstanceOf(Set.class, out.items(), "a Collection settled against a set should be a set");
    assertEquals(items, mapper.backward(out).items(), "and the round trip should return what it was given");
  }

  @Test
  @DisplayName("a list widens into a Collection target, and is rebuilt as a list")
  void listWidensIntoCollection() {
    final var out = Telescope.mapper(ListSrc.class, CollectionDst.class).forward(
      new ListSrc(List.of(new Elem("a"), new Elem("a")))
    );

    assertEquals(List.of(new Dto("a"), new Dto("a")), List.copyOf(out.items()));
    assertInstanceOf(List.class, out.items(), "a Collection settled against a list keeps its duplicates");
  }

  @Test
  @DisplayName("two Collection sides settle on a list, so a set handed to one does not round-trip equal")
  void collectionToCollectionSettlesOnAList() {
    // The consequence of the rule rather than an exception to it. With neither side naming a
    // shape there is nothing to settle against, so a list is what a Collection guarantees on its
    // own -- and a set handed in comes back as a list, which is not equal to it. Equality on a
    // Collection-declared field was never well defined; this says so rather than leaving an
    // adopter to find it, since assertEquals(orig, roundTrip(orig)) is what they write first.
    final Collection<Elem> asSet = new LinkedHashSet<>(List.of(new Elem("a"), new Elem("b")));

    final var mapper = Telescope.mapper(CollectionBoth.class, CollectionBothDto.class);
    final var back = mapper.backward(mapper.forward(new CollectionBoth(asSet))).items();

    assertInstanceOf(List.class, back, "neither side named a shape, so a list is what is built");
    assertEquals(List.copyOf(asSet), List.copyOf(back), "the elements survive, in order");
    assertNotEquals(asSet, back, "but a set and a list are not equal, which is the part to know");
  }

  @Test
  @DisplayName("a duplicate on the Collection side cannot reach a Set side, and is dropped")
  void aDuplicateIsDroppedGoingBackIntoASet() {
    // The other half of settling against the set. Forward it cannot arise, because what is built
    // is a set; backward from a Collection somebody assembled themselves it can, and the element
    // is collapsed rather than refused. The declaration is what decides that -- a field declared
    // as a Set has said duplicates are not meaningful in it.
    final Collection<Dto> withDuplicate = List.of(new Dto("a"), new Dto("a"), new Dto("b"));

    final var back = Telescope.mapper(SetSrc.class, CollectionDst.class)
      .backward(new CollectionDst(withDuplicate))
      .items();

    assertEquals(2, back.size(), "the duplicate is collapsed by the set that receives it");
  }

  @Test
  @DisplayName("a list and a set are still a mismatch, which is the rule Collection is not an exception to")
  void listAndSetStillDoNotPair() {
    // Collection widens because it names no shape. A List and a Set each name one, and they differ,
    // so nothing here relaxes that.
    final var thrown = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(ListSrc.class, SetDst.class).forward(new ListSrc(List.of(new Elem("a"))))
    );

    assertTrue(thrown.getMessage().contains("shapes"), thrown::getMessage);
  }

  @Test
  @DisplayName("a Deque-declared field converts, and is rebuilt as something a Deque can hold")
  void dequeConverts() {
    final var items = new ArrayDeque<Elem>();
    items.add(new Elem("a"));
    items.add(new Elem("b"));

    final var out = Telescope.mapper(DequeSrc.class, DequeDst.class).forward(new DequeSrc(items));

    assertEquals(List.of(new Dto("a"), new Dto("b")), List.copyOf(out.items()));
    assertInstanceOf(ArrayDeque.class, out.items(), "an ArrayList would not satisfy the declaration");
  }

  @Test
  @DisplayName("an empty source yields an empty container rather than nothing")
  void emptySourceConverts() {
    // Nothing about a deque rejecting a zero size: ArrayDeque reads a zero as one slot. What this
    // holds is that an empty source still produces a container to hand back.
    final var out = Telescope.mapper(DequeSrc.class, DequeDst.class).forward(new DequeSrc(new ArrayDeque<>()));

    assertTrue(out.items().isEmpty());
  }

  @Test
  @DisplayName("a concrete queue that cannot hold its source is still refused while the plan is built")
  void capacityBoundedQueueIsStillRefusedAtPlanTime() {
    // The reason the general kinds are matched by name rather than by subtype. A SynchronousQueue
    // holds nothing at all, so accepting it here would move a refusal from plan time to every
    // conversion -- and the registry the starters build at startup would stop catching it.
    final var items = List.of(new Elem("a"));

    final var thrown = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(ListSrc.class, SyncQueueDst.class).forward(new ListSrc(items))
    );

    assertTrue(
      thrown.getMessage().contains("shapes"),
      () -> "and it is the shape mismatch that says so: " + thrown.getMessage()
    );
  }
}
