package com.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
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
}
