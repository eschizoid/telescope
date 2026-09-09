package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code read} and {@code find} answer with the first focus and stop. The cost of a head-grab is
 * the depth of the path, never the size of the focused tree — a many-focus traversal must touch one
 * element, not all of them, and the terminals must keep agreeing with their eager siblings while
 * doing so.
 */
class ReadShortCircuitTest {

  private static final AtomicInteger LEAF_READS = new AtomicInteger();

  /** Counts every read of {@code email}, so a terminal's real traversal work is observable. */
  record User(String email) {
    @Override
    public String email() {
      LEAF_READS.incrementAndGet();
      return email;
    }
  }

  record Team(List<User> users) {}

  record Org(List<Team> teams) {}

  private static Org orgOf(final int userCount) {
    final var users = new ArrayList<User>(userCount);
    for (var i = 0; i < userCount; i++) users.add(new User("user" + i + "@example.com"));
    return new Org(List.of(new Team(users)));
  }

  private static Telescope<Org, String> emails() {
    return Telescope.of(Org.class).each(Org::teams).each(Team::users).field(User::email);
  }

  @BeforeEach
  void resetCounter() {
    LEAF_READS.set(0);
  }

  @Test
  @DisplayName("read touches exactly one focus regardless of how many the path has")
  void readVisitsOneFocus() {
    final var path = emails();
    for (final var focusCount : List.of(1, 2, 50, 500)) {
      final var org = orgOf(focusCount);
      LEAF_READS.set(0);
      assertEquals("user0@example.com", path.read(org));
      assertEquals(1, LEAF_READS.get(), "read of a " + focusCount + "-focus path must read one leaf");
    }
  }

  @Test
  @DisplayName("find touches exactly one focus regardless of how many the path has")
  void findVisitsOneFocus() {
    final var path = emails();
    for (final var focusCount : List.of(1, 2, 50, 500)) {
      final var org = orgOf(focusCount);
      LEAF_READS.set(0);
      assertEquals("user0@example.com", path.find(org).orElseThrow());
      assertEquals(1, LEAF_READS.get(), "find of a " + focusCount + "-focus path must read one leaf");
    }
  }

  @Test
  @DisplayName("a head-grab never costs more leaf reads than materialising every focus")
  void headGrabIsNeverMoreWorkThanTheEagerTerminal() {
    final var path = emails();
    final var org = orgOf(100);

    LEAF_READS.set(0);
    path.toList(org);
    final var eagerReads = LEAF_READS.get();

    LEAF_READS.set(0);
    path.read(org);
    final var headReads = LEAF_READS.get();

    assertEquals(100, eagerReads, "toList reads every focus");
    assertTrue(headReads < eagerReads, "read (" + headReads + ") must do less work than toList (" + eagerReads + ")");
  }

  @Test
  @DisplayName("a null focus is a found value, not an absent one")
  void nullFocusIsDistinctFromNoFocus() {
    final var path = Telescope.of(Team.class).each(Team::users).field(User::email);
    final var withNullEmail = new Team(List.of(new User(null)));

    assertNull(path.read(withNullEmail), "a focused null reads as null rather than throwing");
    assertTrue(path.find(withNullEmail).isEmpty(), "find maps a focused null to empty");
    assertTrue(path.exists(withNullEmail), "the focus exists even though its value is null");
    assertEquals(1, path.count(withNullEmail));
  }

  @Test
  @DisplayName("an empty traversal has no head to grab")
  void emptyTraversalHasNoHead() {
    final var path = emails();
    final var empty = new Org(List.of(new Team(List.of())));

    assertThrows(NoSuchElementException.class, () -> path.read(empty));
    assertTrue(path.find(empty).isEmpty());
    assertFalse(path.exists(empty));
  }

  @Test
  @DisplayName("read and find agree with the eager terminals on the same path")
  void headAgreesWithTheEagerTerminals() {
    final var path = emails();
    for (final var focusCount : List.of(0, 1, 7)) {
      final var org = orgOf(focusCount);
      final var all = path.toList(org);
      final var found = path.find(org);

      // find collapses a null head to empty, so the terminals agree on emptiness only for a
      // non-null head; count is the terminal that always tracks toList's size.
      assertEquals(all.size(), path.count(org), "count and toList agree on cardinality");
      if (!all.isEmpty()) {
        assertEquals(all.getFirst(), found.orElseThrow(), "find returns the head of toList");
        assertEquals(all.getFirst(), path.read(org), "read returns the head of toList");
      } else {
        assertTrue(found.isEmpty(), "no focuses: find is empty");
      }
    }
  }

  @Test
  @DisplayName("a filtered path answers with the first match, not the first focus")
  void filterHeadIsTheFirstMatch() {
    final var org = orgOf(50);
    final var lastOnly = emails().filter(e -> e.equals("user49@example.com"));

    LEAF_READS.set(0);
    assertEquals("user49@example.com", lastOnly.read(org));
    // Reaching the 50th focus reads 50 leaves; the point is that it stops there rather than
    // completing the walk, and that a skipped focus never becomes the head.
    assertEquals(50, LEAF_READS.get());
    assertEquals("user49@example.com", lastOnly.find(org).orElseThrow());
  }

  @Test
  @DisplayName("a filter that matches nothing has no head")
  void filterMissHasNoHead() {
    final var org = orgOf(50);
    final var noMatch = emails().filter(e -> e.startsWith("nobody"));

    assertThrows(NoSuchElementException.class, () -> noMatch.read(org));
    assertTrue(noMatch.find(org).isEmpty());
    assertFalse(noMatch.exists(org));
  }
}
