package io.github.eschizoid.telescope.introspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.introspection.OpticNode.Filter;
import io.github.eschizoid.telescope.introspection.OpticNode.Focus;
import io.github.eschizoid.telescope.introspection.OpticNode.Narrow;
import io.github.eschizoid.telescope.introspection.OpticNode.Traverse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for navigation {@code explain()} — a telescope built by {@code
 * of(…).field(…).each(…)…} must surface its hop path as an ordered {@link OpticNode} trail, one
 * node per combinator. Pins that {@code field} → {@link Focus}, {@code each}/{@code
 * eachValue}/{@code whenPresent} → {@link Traverse}, {@code as} → {@link Narrow}, that {@code then}
 * concatenates both sides' hops, and that a bare {@code of(…)} is the empty report.
 */
class NavigationExplainTest {

  sealed interface Shape permits Circle {}

  record Circle(int radius) implements Shape {}

  record Team(String name, List<User> users) {}

  record User(String name, Optional<String> nickname) {}

  record Company(String name, List<Team> teams) {}

  record Registry(Map<String, User> members) {}

  @Nested
  @DisplayName("Single and chained hops")
  class Hops {

    @Test
    @DisplayName("a bare of(...) telescope has an empty report")
    void bareIsEmpty() {
      assertTrue(Telescope.of(Company.class).explain().isEmpty());
    }

    @Test
    @DisplayName("field is a Focus hop")
    void fieldIsFocus() {
      final var report = Telescope.of(User.class).field(User::name).explain();
      assertEquals(List.of(new Focus("name")), report.nodes());
    }

    @Test
    @DisplayName("each is a Traverse hop over a collection")
    void eachIsTraverse() {
      final var report = Telescope.of(Team.class).each(Team::users).explain();
      assertEquals(List.of(new Traverse("users", "collection")), report.nodes());
    }

    @Test
    @DisplayName("whenPresent is a Traverse hop over an optional")
    void whenPresentIsTraverse() {
      final var report = Telescope.of(User.class).whenPresent(User::nickname).explain();
      assertEquals(List.of(new Traverse("nickname", "optional")), report.nodes());
    }

    @Test
    @DisplayName("a deep chain records every hop in order")
    void deepChainInOrder() {
      final var report = Telescope.of(Company.class).each(Company::teams).each(Team::users).field(User::name).explain();
      assertEquals(
        List.of(new Traverse("teams", "collection"), new Traverse("users", "collection"), new Focus("name")),
        report.nodes()
      );
    }

    @Test
    @DisplayName("as is a Narrow hop naming the subtype")
    void asIsNarrow() {
      final var report = Telescope.of(Shape.class).as(Circle.class).field(Circle::radius).explain();
      assertEquals(List.of(new Narrow("Circle"), new Focus("radius")), report.nodes());
    }

    @Test
    @DisplayName("eachValue is a Traverse hop over a map's values")
    void eachValueIsTraverse() {
      final var report = Telescope.of(Registry.class).eachValue(Registry::members).explain();
      assertEquals(List.of(new Traverse("members", "map values")), report.nodes());
    }

    @Test
    @DisplayName("filter is a Filter hop with the placeholder description")
    void filterIsFilter() {
      final var report = Telescope.of(User.class)
        .field(User::name)
        .filter(n -> n.length() > 2)
        .explain();
      assertEquals(List.of(new Focus("name"), new Filter("predicate")), report.nodes());
    }
  }

  @Nested
  @DisplayName("then concatenation")
  class Then {

    @Test
    @DisplayName("then concatenates both sides' hops in order")
    void thenConcatenates() {
      final var tail = Telescope.of(User.class).field(User::name);
      final var report = Telescope.of(Team.class).each(Team::users).then(tail).explain();
      assertEquals(List.of(new Traverse("users", "collection"), new Focus("name")), report.nodes());
    }
  }
}
