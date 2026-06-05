package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@code updateIndexed} / {@code toListIndexed} and the {@link Indexed} record. */
class IndexedTraversalTest {

  record Team(String name, List<String> members) {}

  static final Telescope<Team, String> MEMBERS = Telescope.of(Team.class).each(Team::members);

  @Test
  @DisplayName("toListIndexed pairs each focused value with its 0-based position")
  void toListIndexed() {
    final var team = new Team("eng", List.of("alice", "bob", "carol"));
    assertEquals(
      List.of(new Indexed<>(0, "alice"), new Indexed<>(1, "bob"), new Indexed<>(2, "carol")),
      MEMBERS.toListIndexed(team)
    );
  }

  @Test
  @DisplayName("updateIndexed transforms with position")
  void updateIndexed() {
    final var team = new Team("eng", List.of("alice", "bob", "carol"));
    final var numbered = MEMBERS.updateIndexed(team, (i, name) -> i + ":" + name);
    assertEquals(List.of("0:alice", "1:bob", "2:carol"), numbered.members());
  }

  @Test
  @DisplayName("index is flat across nested traversals, in getAll order")
  void flatAcrossNesting() {
    record Org(List<Team> teams) {}
    final var org = new Org(List.of(new Team("a", List.of("x", "y")), new Team("b", List.of("z"))));
    final var allMembers = Telescope.of(Org.class).each(Org::teams).each(Team::members);

    final var numbered = allMembers.updateIndexed(org, (i, m) -> i + ":" + m);

    assertEquals(List.of("0:x", "1:y"), numbered.teams().get(0).members());
    assertEquals(List.of("2:z"), numbered.teams().get(1).members());
  }

  @Nested
  @DisplayName("withIndex() — chainable index-aware terminal view")
  class WithIndexChain {

    @Test
    @DisplayName("update matches updateIndexed exactly")
    void updateMirrorsUpdateIndexed() {
      final var team = new Team("eng", List.of("alice", "bob", "carol"));

      final var viaChain = MEMBERS.withIndex().update(team, (i, name) -> i + ":" + name);
      final var viaTerminal = MEMBERS.updateIndexed(team, (i, name) -> i + ":" + name);

      assertEquals(viaTerminal, viaChain);
      assertEquals(List.of("0:alice", "1:bob", "2:carol"), viaChain.members());
    }

    @Test
    @DisplayName("toList matches toListIndexed exactly")
    void toListMirrorsToListIndexed() {
      final var team = new Team("eng", List.of("alice", "bob", "carol"));

      final var viaChain = MEMBERS.withIndex().toList(team);
      final var viaTerminal = MEMBERS.toListIndexed(team);

      assertEquals(viaTerminal, viaChain);
    }

    @Test
    @DisplayName("find returns the first focused value paired with index 0")
    void findFirst() {
      final var team = new Team("eng", List.of("alice", "bob"));
      assertEquals(Optional.of(new Indexed<>(0, "alice")), MEMBERS.withIndex().find(team));
    }

    @Test
    @DisplayName("find returns empty when the path resolves to nothing")
    void findEmpty() {
      final var team = new Team("eng", List.of());
      assertEquals(Optional.empty(), MEMBERS.withIndex().find(team));
    }

    @Test
    @DisplayName("count / exists delegate to the parent telescope")
    void countAndExists() {
      final var populated = new Team("eng", List.of("alice", "bob"));
      final var empty = new Team("eng", List.of());

      final var indexed = MEMBERS.withIndex();
      assertEquals(2L, indexed.count(populated));
      assertEquals(0L, indexed.count(empty));
      assertTrue(indexed.exists(populated));
      assertFalse(indexed.exists(empty));
    }

    @Test
    @DisplayName("each terminal call starts a fresh counter — repeated use is safe")
    void counterResetsPerCall() {
      final var team = new Team("eng", List.of("a", "b", "c"));
      final var indexed = MEMBERS.withIndex();

      final var first = indexed.update(team, (i, name) -> i + ":" + name);
      final var second = indexed.update(team, (i, name) -> i + ":" + name);

      assertEquals(List.of("0:a", "1:b", "2:c"), first.members());
      assertEquals(first, second);
    }

    @Test
    @DisplayName("withIndex preserves flat-across-nesting ordering")
    void flatAcrossNestingChain() {
      record Org(List<Team> teams) {}
      final var org = new Org(List.of(new Team("a", List.of("x", "y")), new Team("b", List.of("z"))));
      final var allMembers = Telescope.of(Org.class).each(Org::teams).each(Team::members);

      final var numbered = allMembers.withIndex().update(org, (i, m) -> i + ":" + m);

      assertEquals(List.of("0:x", "1:y"), numbered.teams().get(0).members());
      assertEquals(List.of("2:z"), numbered.teams().get(1).members());
    }

    @Test
    @DisplayName("withIndex() returns a fresh wrapper each call; both produce identical results")
    void wrapperIsLight() {
      final var first = MEMBERS.withIndex();
      final var second = MEMBERS.withIndex();
      final var team = new Team("eng", List.of("x", "y"));
      assertEquals(first.toList(team), second.toList(team));
    }
  }
}
