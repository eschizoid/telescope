package io.github.eschizoid.telescope.introspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    @DisplayName("an empty fan-out renders the each node with no children")
    void emptyFanOut() {
      final var empty = new Company("Empty", List.of());
      final var trace = Telescope.of(Company.class).each(Company::teams).field(Team::label).trace(empty);
      assertEquals("each teams", trace.roots().get(0).label());
      assertTrue(trace.roots().get(0).children().isEmpty(), trace::toString);
    }

    record Addr(String city) {}

    record Person(String name, Addr home) {}

    @Test
    @DisplayName("a null intermediate field does not throw — the leaf renders null")
    void nullIntermediate() {
      final var person = new Person("Ada", null);
      final var trace = Telescope.of(Person.class).field(Person::home).field(Addr::city).trace(person);
      // home is null, so reading city off it must render null, not throw.
      assertEquals("home\n └ city → null", trace.toString());
    }
  }

  @Nested
  @DisplayName("Container families and mid-path filter")
  class ContainerFamilies {

    record Member(String name) {}

    record Registry(Map<String, Member> members) {}

    record Profile(String name, Optional<String> nickname) {}

    @Test
    @DisplayName("eachValue fans out over a map's values, one child per value")
    void eachValueFanOut() {
      final var registry = new Registry(Map.of("a", new Member("Ada")));
      final var trace = Telescope.of(Registry.class).eachValue(Registry::members).field(Member::name).trace(registry);
      assertEquals("each members", trace.roots().get(0).label());
      assertEquals(1, trace.roots().get(0).children().size(), trace::toString);
      assertTrue(trace.toString().contains("name → \"Ada\""), trace::toString);
    }

    @Test
    @DisplayName("whenPresent fans out a present optional to one child, and an empty optional to none")
    void whenPresentFanOut() {
      final var present = Telescope.of(Profile.class)
        .whenPresent(Profile::nickname)
        .trace(new Profile("Ada", Optional.of("Legend")));
      assertEquals(1, present.roots().get(0).children().size(), present::toString);
      assertTrue(present.toString().contains("\"Legend\""), present::toString);
      final var absent = Telescope.of(Profile.class)
        .whenPresent(Profile::nickname)
        .trace(new Profile("Bo", Optional.empty()));
      assertTrue(absent.roots().get(0).children().isEmpty(), absent::toString);
    }

    @Test
    @DisplayName("a filter in the middle of a path is annotated and passed through, not applied")
    void filterMidPath() {
      final var trace = Telescope.of(Profile.class)
        .field(Profile::name)
        .filter(n -> n.isEmpty())
        .trace(new Profile("Ada", Optional.empty()));
      // The predicate would exclude "Ada", but trace does not apply it — the value flows through.
      assertTrue(trace.toString().contains("filter"), trace::toString);
      assertTrue(trace.toString().contains("Ada"), trace::toString);
    }
  }

  @Nested
  @DisplayName("Bean read path")
  class BeanPath {

    public static final class Bean {

      private final String label;

      public Bean(final String label) {
        this.label = label;
      }

      public String getLabel() {
        return label;
      }
    }

    @Test
    @DisplayName("a bean-backed telescope traces through the getter, reading the property value")
    void beanFieldTrace() {
      final var trace = Telescope.ofBean(Bean.class).field(Bean::getLabel).trace(new Bean("Ada"));
      // The bean read path (Reflective bean branch) must resolve the value, not surface (n/a).
      assertTrue(trace.toString().contains("\"Ada\""), trace::toString);
      assertFalse(trace.toString().contains("(n/a)"), trace::toString);
    }
  }

  @Nested
  @DisplayName("Uncapped limits and constructor guards")
  class Limits {

    record Post(String title, List<String> tags) {}

    @Test
    @DisplayName("TraceLimits.none() shows every element of a wide fan-out with no truncation marker")
    void uncappedShowsAll() {
      final var post = new Post("Hello", List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"));
      final var trace = Telescope.of(Post.class).each(Post::tags).trace(post, TraceLimits.none());
      assertEquals(12, trace.roots().get(0).children().size(), trace::toString);
      assertFalse(trace.toString().contains("more)"), trace::toString);
    }

    @Test
    @DisplayName("a non-positive breadth or depth is rejected at construction")
    void guardsRejectNonPositive() {
      assertThrows(IllegalArgumentException.class, () -> new TraceLimits(0, 10));
      assertThrows(IllegalArgumentException.class, () -> new TraceLimits(10, 0));
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
