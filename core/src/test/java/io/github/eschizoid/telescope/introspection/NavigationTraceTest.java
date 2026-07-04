package io.github.eschizoid.telescope.introspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for navigation {@code trace(input)} — running a path against a value and
 * describing what each hop did. Pins the linear single-focus shape, the many-focus tree fan-out,
 * and the {@link TraceLimits} breadth cap with its truncation marker, plus the exact rendered tree
 * via a golden master.
 */
class NavigationTraceTest {

  record User(String name) {}

  record Team(String label, List<User> users) {}

  record Company(String label, List<Team> teams) {}

  @Nested
  @DisplayName("Linear single-focus trace")
  class Linear {

    record Addr(String city) {}

    record Person(String name, Addr home) {}

    @Test
    @DisplayName("a field chain traces linearly, ending in the leaf value")
    void linearChain() {
      final var person = new Person("Ada", new Addr("Paris"));
      final var trace = Telescope.of(Person.class).field(Person::home).field(Addr::city).trace(person);
      assertEquals("home\n └ city → \"Paris\"", trace.toString());
    }
  }

  @Nested
  @DisplayName("Many-focus tree fan-out")
  class FanOut {

    private Company company() {
      return new Company(
        "Acme",
        List.of(new Team("Sales", List.of(new User("Ada"), new User("Bo"))), new Team("Eng", List.of(new User("Cy"))))
      );
    }

    @Test
    @DisplayName("nested each() steps expand into a per-element tree")
    void treeFanOut() {
      final var trace = Telescope.of(Company.class)
        .each(Company::teams)
        .each(Team::users)
        .field(User::name)
        .trace(company());
      // Structure: each teams -> [Team Sales -> each users -> [Ada, Bo]], [Team Eng -> each users
      // -> [Cy]]
      final var root = trace.roots().get(0);
      assertEquals("each teams", root.label());
      assertEquals(2, root.children().size(), root.label());
    }

    @Test
    @DisplayName("the breadth cap truncates a wide fan-out with a marker")
    void breadthCap() {
      final var trace = Telescope.of(Company.class).each(Company::teams).trace(company(), new TraceLimits(1, 20));
      final var root = trace.roots().get(0);
      assertEquals(2, root.children().size(), "one shown element + one truncation marker");
      assertTrue(root.children().get(1).truncated(), "second child is the truncation marker");
      assertTrue(root.children().get(1).label().contains("(+1 more)"), root.children().get(1).label());
    }
  }

  @Nested
  @DisplayName("Leading narrow/filter and degenerate inputs")
  class EdgeCases {

    sealed interface Shape permits Circle {}

    record Circle(int radius) implements Shape {}

    @Test
    @DisplayName("a path that LEADS with as(...) still executes (does not mis-route to the mapping-row render)")
    void narrowFirstExecutes() {
      final Shape shape = new Circle(5);
      final var trace = Telescope.of(Shape.class).as(Circle.class).field(Circle::radius).trace(shape);
      // Regression: the trace guard whitelisted only Focus/Traverse, so an as-first path rendered
      // static node toStrings instead of running. The value must appear.
      assertTrue(trace.toString().contains("radius → 5"), trace::toString);
    }

    @Test
    @DisplayName("the depth cap truncates a deep traversal with a (depth cap) marker")
    void depthCap() {
      final var company = new Company("Acme", List.of(new Team("Sales", List.of(new User("Ada")))));
      final var trace = Telescope.of(Company.class)
        .each(Company::teams)
        .each(Team::users)
        .trace(company, new TraceLimits(10, 1));
      assertTrue(trace.toString().contains("(depth cap)"), trace::toString);
    }

    @Test
    @DisplayName("an empty fan-out renders the each node with no children, and a null intermediate does not throw")
    void emptyAndNull() {
      final var empty = new Company("Empty", List.of());
      final var trace = Telescope.of(Company.class).each(Company::teams).field(Team::label).trace(empty);
      assertEquals("each teams", trace.roots().get(0).label());
      assertTrue(trace.roots().get(0).children().isEmpty(), trace::toString);
    }
  }

  @Nested
  @DisplayName("Golden render — the exact tree, pinned")
  class Golden {

    record Post(String title, List<String> tags) {}

    @Test
    @DisplayName("a scalar-element fan-out renders as a clean ├ / └ tree")
    void scalarFanOutTree() {
      final var post = new Post("Hello", List.of("red", "green", "blue"));
      final var trace = Telescope.of(Post.class).each(Post::tags).trace(post);
      final var expected = String.join("\n", "each tags", " ├ \"red\"", " ├ \"green\"", " └ \"blue\"");
      assertEquals(expected, trace.toString());
    }

    @Test
    @DisplayName("a capped fan-out renders the shown elements then the truncation marker")
    void cappedTree() {
      final var post = new Post("Hello", List.of("red", "green", "blue"));
      final var trace = Telescope.of(Post.class).each(Post::tags).trace(post, new TraceLimits(2, 20));
      final var expected = String.join("\n", "each tags", " ├ \"red\"", " ├ \"green\"", " └ … (+1 more)");
      assertEquals(expected, trace.toString());
    }
  }
}
