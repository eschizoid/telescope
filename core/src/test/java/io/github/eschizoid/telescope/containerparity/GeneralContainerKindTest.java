package io.github.eschizoid.telescope.containerparity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

  record SetSrc(Set<Elem> items) {}

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
  @DisplayName("widening across kinds is still refused: a Set source does not match a Collection target")
  void setDoesNotWidenIntoCollection() {
    // Deliberately not delivered here. The same-kind rule is what refuses it, and relaxing that
    // needs a decision about the other direction -- a Collection source into a Set target dedupes,
    // which is lossy in a way the forward direction is not.
    final var items = new LinkedHashSet<Elem>();
    items.add(new Elem("a"));

    final var thrown = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(SetSrc.class, CollectionDst.class).forward(new SetSrc(items))
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
  @DisplayName("an empty source still yields a container, since a deque rejects a zero size")
  void emptySourceConverts() {
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
