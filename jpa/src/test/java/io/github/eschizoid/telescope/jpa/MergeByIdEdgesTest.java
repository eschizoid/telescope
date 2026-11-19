package io.github.eschizoid.telescope.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.jpa.fixtures.edges.Alpha;
import io.github.eschizoid.telescope.jpa.fixtures.edges.AlphaEdit;
import io.github.eschizoid.telescope.jpa.fixtures.edges.AlphaForm;
import io.github.eschizoid.telescope.jpa.fixtures.edges.BetaEdit;
import io.github.eschizoid.telescope.jpa.fixtures.edges.BetaForm;
import io.github.eschizoid.telescope.jpa.fixtures.edges.Box;
import io.github.eschizoid.telescope.jpa.fixtures.edges.BoxEdit;
import io.github.eschizoid.telescope.jpa.fixtures.edges.BoxForm;
import io.github.eschizoid.telescope.jpa.fixtures.edges.Cart;
import io.github.eschizoid.telescope.jpa.fixtures.edges.CartEdit;
import io.github.eschizoid.telescope.jpa.fixtures.edges.CartForm;
import io.github.eschizoid.telescope.jpa.fixtures.edges.CartMappers;
import io.github.eschizoid.telescope.jpa.fixtures.edges.CellEdit;
import io.github.eschizoid.telescope.jpa.fixtures.edges.CellForm;
import io.github.eschizoid.telescope.jpa.fixtures.edges.Edges;
import io.github.eschizoid.telescope.jpa.fixtures.edges.FriendMappers;
import io.github.eschizoid.telescope.jpa.fixtures.edges.Group;
import io.github.eschizoid.telescope.jpa.fixtures.edges.GroupEdit;
import io.github.eschizoid.telescope.jpa.fixtures.edges.GroupForm;
import io.github.eschizoid.telescope.jpa.fixtures.edges.GroupMappers;
import io.github.eschizoid.telescope.jpa.fixtures.edges.Label;
import io.github.eschizoid.telescope.jpa.fixtures.edges.LabelEdit;
import io.github.eschizoid.telescope.jpa.fixtures.edges.LabelForm;
import io.github.eschizoid.telescope.jpa.fixtures.edges.Note;
import io.github.eschizoid.telescope.jpa.fixtures.edges.NoteEdit;
import io.github.eschizoid.telescope.jpa.fixtures.edges.NoteForm;
import io.github.eschizoid.telescope.jpa.fixtures.edges.Pin;
import io.github.eschizoid.telescope.jpa.fixtures.edges.PinEdit;
import io.github.eschizoid.telescope.jpa.fixtures.edges.PinForm;
import io.github.eschizoid.telescope.jpa.fixtures.edges.RowEdit;
import io.github.eschizoid.telescope.jpa.fixtures.edges.RowForm;
import io.github.eschizoid.telescope.jpa.fixtures.edges.StepEdit;
import io.github.eschizoid.telescope.jpa.fixtures.edges.StepForm;
import jakarta.persistence.PersistenceException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The edges of a merge by identifier, on the generated path and the runtime path alike, counted by
 * Hibernate's statistics: a primitive identifier at its default, a boxed one at zero, a set whose
 * sent elements convert equal, a refusal two levels down, elements a {@code via} row's mapper
 * converts, and a versioned owner.
 */
class MergeByIdEdgesTest {

  private static Edges edges;

  @BeforeAll
  static void open() {
    edges = Edges.open("merge-by-id-edges");
  }

  @AfterAll
  static void close() {
    edges.close();
  }

  /** An element as a test describes it: its identifier and its one text property. */
  record Sent(Long id, String text) {}

  /** A row of a group as a test describes it, with its cells. */
  record SentRow(Long id, String text, List<Sent> cells) {}

  /** The two paths an owner is written in place by. */
  enum Path {
    GENERATED {
      @Override
      void cart(
        final Cart cart,
        final String name,
        final List<Sent> notes,
        final Set<Sent> tags,
        final List<Sent> pins
      ) {
        CartMappers.GEN.into(
          cart,
          new CartForm(
            cart.getId(),
            cart.getVersion(),
            name,
            map(notes, sent -> new NoteForm(sent.id(), sent.text())),
            map(steps(cart), sent -> new StepForm(sent.id(), sent.text())),
            tags
              .stream()
              .map(sent -> new LabelForm(sent.id(), sent.text()))
              .collect(Collectors.toCollection(LinkedHashSet::new)),
            map(pins, sent -> new PinForm(sent.id() == null ? 0 : sent.id(), sent.text()))
          )
        );
      }

      @Override
      void box(final Box box, final List<Sent> notes) {
        CartMappers.BOX_GEN.into(
          box,
          new BoxForm(box.getId(), map(notes, sent -> new NoteForm(sent.id(), sent.text())))
        );
      }

      @Override
      void group(final Group group, final List<SentRow> rows) {
        GroupMappers.GEN.into(
          group,
          new GroupForm(
            group.getId(),
            group.getName(),
            map(rows, row ->
              new RowForm(row.id(), row.text(), map(row.cells(), cell -> new CellForm(cell.id(), cell.text())))
            )
          )
        );
      }
    },
    RUNTIME {
      @Override
      void cart(
        final Cart cart,
        final String name,
        final List<Sent> notes,
        final Set<Sent> tags,
        final List<Sent> pins
      ) {
        CartMappers.RT.into(
          cart,
          new CartEdit(
            cart.getId(),
            cart.getVersion(),
            name,
            map(notes, sent -> new NoteEdit(sent.id(), sent.text())),
            map(steps(cart), sent -> new StepEdit(sent.id(), sent.text())),
            tags
              .stream()
              .map(sent -> new LabelEdit(sent.id(), sent.text()))
              .collect(Collectors.toCollection(LinkedHashSet::new)),
            map(pins, sent -> new PinEdit(sent.id() == null ? 0 : sent.id(), sent.text()))
          )
        );
      }

      @Override
      void box(final Box box, final List<Sent> notes) {
        CartMappers.BOX_RT.into(
          box,
          new BoxEdit(box.getId(), map(notes, sent -> new NoteEdit(sent.id(), sent.text())))
        );
      }

      @Override
      void group(final Group group, final List<SentRow> rows) {
        GroupMappers.RT.into(
          group,
          new GroupEdit(
            group.getId(),
            group.getName(),
            map(rows, row ->
              new RowEdit(row.id(), row.text(), map(row.cells(), cell -> new CellEdit(cell.id(), cell.text())))
            )
          )
        );
      }
    };

    /**
     * Writes {@code cart} its name, notes, labels and pins as sent, and its steps as it holds them.
     */
    abstract void cart(Cart cart, String name, List<Sent> notes, Set<Sent> tags, List<Sent> pins);

    abstract void box(Box box, List<Sent> notes);

    abstract void group(Group group, List<SentRow> rows);

    private static <E, F> List<F> map(final List<E> elements, final Function<E, F> each) {
      return elements.stream().map(each).toList();
    }

    private static List<Sent> steps(final Cart cart) {
      return cart
        .getSteps()
        .stream()
        .map(step -> new Sent(step.getId(), step.getText()))
        .toList();
    }
  }

  private static List<Sent> notes(final Cart cart) {
    return cart
      .getNotes()
      .stream()
      .map(note -> new Sent(note.getId(), note.getText()))
      .collect(Collectors.toCollection(ArrayList::new));
  }

  private static Set<Sent> tags(final Cart cart) {
    return cart
      .getTags()
      .stream()
      .map(tag -> new Sent(tag.getId(), tag.getLabel()))
      .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static List<Sent> pins(final Cart cart) {
    return cart
      .getPrims()
      .stream()
      .map(pin -> new Sent(pin.getId(), pin.getText()))
      .collect(Collectors.toCollection(ArrayList::new));
  }

  private static String entities() {
    final var statistics = edges.statistics();
    return (
      statistics.getEntityInsertCount() +
      " INSERT, " +
      statistics.getEntityUpdateCount() +
      " UPDATE, " +
      statistics.getEntityDeleteCount() +
      " DELETE"
    );
  }

  @Test
  @DisplayName(
    "a pin whose primitive identifier arrives at its default is new and inserted, and the held pins are kept"
  )
  void aPrimitiveIdentifierAtItsDefaultIsNew() {
    for (final var path : Path.values()) {
      final var id = edges.cart();
      edges.with(Cart.class, id, cart -> {
        final var held = List.copyOf(cart.getPrims());
        final var pins = pins(cart);
        pins.add(new Sent(null, "p3"));
        path.cart(cart, cart.getName(), notes(cart), tags(cart), pins);
        assertSame(held.get(0), cart.getPrims().get(0), path::name);
        assertSame(held.get(1), cart.getPrims().get(1), path::name);
        assertEquals("p3", cart.getPrims().get(2).getText(), path::name);
        return null;
      });
      assertEquals(1L, edges.statistics().getEntityStatistics(Pin.class.getName()).getInsertCount(), path::name);
    }
  }

  @Test
  @DisplayName("a note whose boxed identifier arrives as zero carries an identifier, and is refused as no held note's")
  void aBoxedIdentifierOfZeroIsAnIdentifier() {
    for (final var path : Path.values()) {
      final var id = edges.cart();
      edges.with(Cart.class, id, cart -> {
        final var notes = notes(cart);
        notes.add(new Sent(0L, "zero"));
        final var refused = assertThrows(IllegalArgumentException.class, () ->
          path.cart(cart, "renamed", notes, tags(cart), pins(cart))
        );
        assertTrue(refused.getMessage().contains("the identifier 0, which no element"), refused::getMessage);
        assertEquals("cart", cart.getName(), path::name);
        return null;
      });
      assertEquals("0 INSERT, 0 UPDATE, 0 DELETE", entities(), path::name);
    }
  }

  @Test
  @DisplayName(
    "two labels sent with their own identifiers that convert equal are refused by name, and nothing is written"
  )
  void sentElementsThatConvertEqualAreRefused() {
    for (final var path : Path.values()) {
      final var id = edges.cart();
      edges.with(Cart.class, id, cart -> {
        final var notes = notes(cart);
        notes.set(0, new Sent(notes.getFirst().id(), "n1-changed"));
        final var tags = new LinkedHashSet<Sent>();
        for (final var tag : cart.getTags()) tags.add(new Sent(tag.getId(), "same"));
        final var refused = assertThrows(IllegalArgumentException.class, () ->
          path.cart(cart, "renamed", notes, tags, pins(cart))
        );
        assertTrue(refused.getMessage().contains("'tags'"), refused::getMessage);
        assertTrue(refused.getMessage().contains("converted into equal values of Label"), refused::getMessage);
        assertEquals("cart", cart.getName(), path::name);
        assertEquals("n1", cart.getNotes().getFirst().getText(), () -> path + ": the notes, written first, were not");
        assertEquals(2, cart.getTags().size(), path::name);
        return null;
      });
      assertEquals("0 INSERT, 0 UPDATE, 0 DELETE", entities(), path::name);
    }
  }

  @Test
  @DisplayName("a refusal in a row's cells is made before the first row is written")
  void aNestedRefusalComesBeforeAnyWrite() {
    for (final var path : Path.values()) {
      final var id = edges.group();
      edges.with(Group.class, id, group -> {
        final var first = group.getLines().get(0);
        final var second = group.getLines().get(1);
        final var rows = List.of(
          new SentRow(first.getId(), "l1-changed", List.of(new Sent(first.getSubs().getFirst().getId(), "l1a"))),
          new SentRow(second.getId(), "l2", List.of(new Sent(777_777L, "unknown")))
        );
        final var refused = assertThrows(IllegalArgumentException.class, () -> path.group(group, rows));
        assertTrue(refused.getMessage().contains("'subs'"), refused::getMessage);
        assertTrue(refused.getMessage().contains("777777"), refused::getMessage);
        assertEquals("l1", first.getText(), () -> path + ": the first row was not written");
        return null;
      });
      assertEquals("0 INSERT, 0 UPDATE, 0 DELETE", entities(), path::name);
    }
  }

  @Test
  @DisplayName(
    "notes a via row's mapper converts are written in place: none issues a statement, a changed one one UPDATE"
  )
  void aViaRowsMapperWritesInPlace() {
    for (final var path : Path.values()) {
      final var id = edges.box();
      edges.with(Box.class, id, box -> {
        final var held = List.copyOf(box.getNotes());
        path.box(
          box,
          box
            .getNotes()
            .stream()
            .map(note -> new Sent(note.getId(), note.getText()))
            .toList()
        );
        assertSame(held.get(0), box.getNotes().get(0), path::name);
        assertSame(held.get(1), box.getNotes().get(1), path::name);
        return null;
      });
      assertEquals("0 INSERT, 0 UPDATE, 0 DELETE", entities(), path::name);
      edges.with(Box.class, id, box -> {
        final var first = box.getNotes().getFirst();
        path.box(box, List.of(new Sent(first.getId(), "b1-changed"), new Sent(box.getNotes().get(1).getId(), "b2")));
        assertSame(first, box.getNotes().getFirst(), path::name);
        assertEquals("b1-changed", first.getText(), path::name);
        return null;
      });
      assertEquals("0 INSERT, 1 UPDATE, 0 DELETE", entities(), path::name);
    }
  }

  @Test
  @DisplayName(
    "notes a via row's method pair converts keep the refill, which a managed owner refuses at flush for a held id"
  )
  void aViaRowsMethodPairKeepsTheRefill() {
    final var id = edges.box();
    final var refused = assertThrows(PersistenceException.class, () ->
      edges.with(Box.class, id, box -> {
        final var held = box.getNotes().getFirst();
        CartMappers.BOX_RT_METHODS.into(
          box,
          new BoxEdit(
            id,
            box
              .getNotes()
              .stream()
              .map(note -> new NoteEdit(note.getId(), note.getText()))
              .toList()
          )
        );
        assertNotSame(held, box.getNotes().getFirst(), "a new instance in place of the held note");
        return null;
      })
    );
    assertTrue(refused.getMessage().contains("Detached entity passed to persist"), refused::getMessage);
  }

  @Test
  @DisplayName(
    "a versioned owner keeps its version when nothing changed, and takes one owner UPDATE for a reordered bag or a" +
      " relabelled set element"
  )
  void aVersionedOwnerIsBumpedOnlyByAChangedCollection() {
    for (final var path : Path.values()) {
      final var id = edges.cart();
      edges.with(Cart.class, id, cart -> {
        path.cart(cart, cart.getName(), notes(cart), tags(cart), pins(cart));
        return null;
      });
      assertEquals("0 INSERT, 0 UPDATE, 0 DELETE", entities(), path::name);
      edges.with(Cart.class, id, cart -> {
        path.cart(cart, cart.getName(), new ArrayList<>(notes(cart).reversed()), tags(cart), pins(cart));
        return null;
      });
      assertEquals(1L, edges.statistics().getEntityStatistics(Cart.class.getName()).getUpdateCount(), path::name);
      assertEquals("0 INSERT, 1 UPDATE, 0 DELETE", entities(), () -> path + ": the owner's version, and nothing else");
      edges.with(Cart.class, id, cart -> {
        final var tags = new LinkedHashSet<Sent>();
        for (final var tag : cart.getTags())
          tags.add(new Sent(tag.getId(), tag.getLabel().equals("red") ? "crimson" : tag.getLabel()));
        path.cart(cart, cart.getName(), notes(cart), tags, pins(cart));
        return null;
      });
      assertEquals(1L, edges.statistics().getEntityStatistics(Cart.class.getName()).getUpdateCount(), path::name);
      assertEquals(1L, edges.statistics().getEntityStatistics(Label.class.getName()).getUpdateCount(), path::name);
      assertEquals(
        Integer.valueOf(2),
        edges.inSession(session -> session.find(Cart.class, id).getVersion()),
        path::name
      );
    }
  }

  @Test
  @DisplayName(
    "a detached cart whose collections were never loaded is written through its setters, as without the module"
  )
  void anUnreadableHeldCollectionIsWrittenThroughTheSetter() {
    final var id = edges.cart();
    // A note sent with a held note's identifier, so the merge reads the held notes to match it.
    final var noteId = edges.with(Cart.class, id, cart -> cart.getNotes().getFirst().getId());
    final var detached = edges.inSession(session -> session.find(Cart.class, id));
    CartMappers.RT.into(
      detached,
      new CartEdit(
        id,
        detached.getVersion(),
        "cart",
        List.of(new NoteEdit(noteId, "x")),
        List.of(),
        Set.of(),
        List.of()
      )
    );
    assertTrue(Hibernate.isInitialized(detached.getNotes()), "the setter wrote a list of its own");
    assertEquals(List.of("x"), detached.getNotes().stream().map(Note::getText).toList());
    final var again = edges.inSession(session -> session.find(Cart.class, id));
    CartMappers.GEN.into(
      again,
      new CartForm(id, again.getVersion(), "cart", List.of(new NoteForm(noteId, "y")), List.of(), Set.of(), List.of())
    );
    assertEquals(List.of("y"), again.getNotes().stream().map(Note::getText).toList());
  }

  @Test
  @DisplayName(
    "a cart whose notes nothing has loaded yet still has them merged inside its session, the check loading them"
  )
  void anUnloadedCollectionIsMergedInsideItsSession() {
    for (final var path : Path.values()) {
      final var id = edges.cart();
      final var notes = edges.with(Cart.class, id, MergeByIdEdgesTest::notes);
      final var held = edges.inSession(session -> {
        final var cart = session.find(Cart.class, id);
        assertTrue(!Hibernate.isInitialized(cart.getNotes()), "the notes are not loaded yet");
        cart.getSteps().size();
        cart.getTags().size();
        cart.getPrims().size();
        edges.statistics().clear();
        path.cart(cart, cart.getName(), notes, tags(cart), pins(cart));
        session.flush();
        return cart.getNotes().stream().map(Note::getId).toList();
      });
      assertEquals(notes.stream().map(Sent::id).toList(), held, path::name);
      assertEquals("0 INSERT, 0 UPDATE, 0 DELETE", entities(), path::name);
    }
  }

  @Test
  @DisplayName(
    "a cart found with none of its collections loaded and sent an unknown identifier in its first, a middle or its" +
      " last collection is refused before any is written: after catching, nothing flushes and its version stays"
  )
  void anUnloadedOwnerIsRefusedBeforeAnyWrite() {
    for (final var unknownIn : List.of("notes", "tags", "prims")) {
      for (final var path : Path.values()) {
        final var id = edges.cart();
        final var held = edges.with(Cart.class, id, cart -> new Sending(notes(cart), tags(cart), pins(cart)));
        final var notes = held.notes();
        notes.set(0, new Sent(notes.getFirst().id(), "n1-changed"));
        final var tags = new LinkedHashSet<Sent>();
        for (final var tag : held.tags()) tags.add(new Sent(tag.id(), tag.text() + "-changed"));
        final var pins = held.pins();
        pins.set(0, new Sent(pins.getFirst().id(), "p1-changed"));
        final var ghost = new Sent(999_999L, "ghost");
        switch (unknownIn) {
          case "notes" -> notes.add(ghost);
          case "tags" -> tags.add(ghost);
          default -> pins.add(ghost);
        }
        final var version = edges.inSession(session -> session.find(Cart.class, id).getVersion());
        final var where = path + ", unknown in " + unknownIn;
        edges.inSession(session -> {
          final var cart = session.find(Cart.class, id);
          assertTrue(!Hibernate.isInitialized(cart.getNotes()), "the notes are not loaded yet");
          assertTrue(!Hibernate.isInitialized(cart.getTags()), "the tags are not loaded yet");
          assertTrue(!Hibernate.isInitialized(cart.getPrims()), "the pins are not loaded yet");
          final var refused = assertThrows(IllegalArgumentException.class, () ->
            path.cart(cart, "renamed", notes, tags, pins)
          );
          assertTrue(refused.getMessage().contains("'" + unknownIn + "'"), refused::getMessage);
          assertEquals("cart", cart.getName(), where);
          assertEquals("n1", cart.getNotes().getFirst().getText(), where);
          edges.statistics().clear();
          session.flush();
          assertEquals("0 INSERT, 0 UPDATE, 0 DELETE", entities(), where);
          return null;
        });
        assertEquals(version, edges.inSession(session -> session.find(Cart.class, id).getVersion()), where);
      }
    }
  }

  @Test
  @DisplayName(
    "a database error loading a cart's unloaded notes reaches the caller, and the notes are not written through" +
      " the setter instead"
  )
  void aFailedLoadReachesTheCaller() {
    for (final var path : Path.values()) {
      final var id = edges.cart();
      final var notes = edges.with(Cart.class, id, MergeByIdEdgesTest::notes);
      try {
        edges.inSession(session -> {
          final var cart = session.find(Cart.class, id);
          final var held = cart.getNotes();
          assertTrue(!Hibernate.isInitialized(held), "the notes are not loaded yet");
          edges.inSession(other ->
            other.createNativeMutationQuery("ALTER TABLE Note RENAME TO NoteGone").executeUpdate()
          );
          final var failed = assertThrows(RuntimeException.class, () ->
            path.cart(cart, "renamed", notes, Set.of(), List.of())
          );
          assertTrue(failed.getClass().getName().startsWith("org.hibernate."), () -> path + ": " + failed);
          assertSame(held, cart.getNotes(), path::name);
          session.getTransaction().setRollbackOnly();
          return null;
        });
      } finally {
        edges.inSession(other ->
          other.createNativeMutationQuery("ALTER TABLE NoteGone RENAME TO Note").executeUpdate()
        );
      }
    }
  }

  /** What a cart is sent for its notes, tags and pins. */
  private record Sending(List<Sent> notes, Set<Sent> tags, List<Sent> pins) {}

  @Test
  @DisplayName(
    "an alpha and a beta that are each other's friends, written from a tree of forms, are written in place on both" +
      " paths, each once, and keep each other"
  )
  void aCyclicGraphIsWrittenInPlace() {
    for (final var path : List.of("GENERATED", "RUNTIME")) {
      final var id = edges.friends();
      edges.with(Alpha.class, id, alpha -> {
        final var beta = alpha.getFriends().getFirst();
        if (path.equals("GENERATED")) FriendMappers.GEN.into(
          alpha,
          new AlphaForm(
            id,
            "a2",
            List.of(new BetaForm(beta.getId(), "b2", List.of(new AlphaForm(id, "a2", List.of()))))
          )
        );
        else FriendMappers.RT.into(
          alpha,
          new AlphaEdit(
            id,
            "a2",
            List.of(new BetaEdit(beta.getId(), "b2", List.of(new AlphaEdit(id, "a2", List.of()))))
          )
        );
        assertSame(beta, alpha.getFriends().getFirst(), path);
        assertSame(alpha, beta.getFriends().getFirst(), path);
        assertEquals(1, alpha.getFriends().size(), path);
        assertEquals(1, beta.getFriends().size(), path);
        assertEquals("a2", alpha.getName(), path);
        assertEquals("b2", beta.getName(), path);
        return null;
      });
      assertEquals("0 INSERT, 2 UPDATE, 0 DELETE", entities(), path);
    }
  }

  @Test
  @DisplayName("an unchanged note of a cart issues no statement, and the note entity is never rewritten")
  void anUnchangedCartIssuesNothing() {
    for (final var path : Path.values()) {
      final var id = edges.cart();
      edges.with(Cart.class, id, cart -> {
        final var held = List.copyOf(cart.getNotes());
        path.cart(cart, cart.getName(), notes(cart), tags(cart), pins(cart));
        for (var i = 0; i < held.size(); i++) assertSame(held.get(i), cart.getNotes().get(i), path::name);
        return null;
      });
      assertEquals(0L, edges.statistics().getEntityStatistics(Note.class.getName()).getUpdateCount(), path::name);
      assertEquals(0L, edges.statistics().getPrepareStatementCount(), path::name);
    }
  }
}
