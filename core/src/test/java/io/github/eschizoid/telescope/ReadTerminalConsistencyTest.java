package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.PropertyNames;
import io.github.eschizoid.telescope.introspection.OpticNode;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The suite that would have caught both prior read-terminal divergences before they shipped: every
 * eager terminal must agree with every other on every telescope shape and every input shape. The
 * laws, for any telescope {@code t} and source {@code s}:
 *
 * <ul>
 *   <li>{@code t.count(s) == t.toList(s).size() == t.toListIndexed(s).size()}
 *   <li>{@code t.exists(s) == (t.count(s) > 0)}
 *   <li>{@code t.toListIndexed(s)} is exactly {@code t.toList(s)} zipped with 0-based positions
 *   <li>{@code t.find(s).isPresent()} implies {@code t.exists(s)} (not the converse — {@code find}
 *       rides {@link Optional}, which collapses a null focus to empty; the documented
 *       null-collapse)
 * </ul>
 */
class ReadTerminalConsistencyTest {

  record Address(String city) {}

  record User(String name, Address address) {}

  record Team(String label, List<User> users) {}

  sealed interface Event permits Created, Updated {}

  record Created(String id) implements Event {}

  record Updated(String id, String diff) implements Event {}

  private static <S, A> void assertTerminalsAgree(final Telescope<S, A> t, final S source) {
    final var list = t.toList(source);
    final var indexed = t.toListIndexed(source);
    final var count = t.count(source);
    final var exists = t.exists(source);
    final var found = t.find(source);

    assertEquals(list.size(), count, "count must equal toList().size()");
    assertEquals(list.size(), indexed.size(), "toListIndexed must have toList's cardinality");
    assertEquals(exists, count > 0, "exists must equal count > 0");
    for (var i = 0; i < list.size(); i++) {
      assertEquals(i, indexed.get(i).index(), "indexed positions are 0-based traversal order");
      assertEquals(list.get(i), indexed.get(i).value(), "indexed values mirror toList");
    }
    if (found.isPresent()) {
      assertTrue(exists, "a present find implies existence");
      assertEquals(list.get(0), found.get(), "find is the head of toList when present");
    }
  }

  @Nested
  @DisplayName("every telescope shape, every input shape")
  class TheMatrix {

    @Test
    @DisplayName("Iso root (Telescope.of) — null root, normal root")
    void isoRoot() {
      final var root = Telescope.of(User.class);
      assertTerminalsAgree(root, null);
      assertTerminalsAgree(root, new User("Ann", new Address("nyc")));
    }

    @Test
    @DisplayName("Lens path — null root, normal, null focus, null intermediate")
    void lensPath() {
      final var name = Telescope.of(User.class).field(User::name);
      assertTerminalsAgree(name, null);
      assertTerminalsAgree(name, new User("Ann", null));
      assertTerminalsAgree(name, new User(null, null)); // null focus is still a focus

      final var city = Telescope.of(User.class).field(User::address).field(Address::city);
      assertTerminalsAgree(city, new User("Ann", null)); // null intermediate propagates on reads
    }

    @Test
    @DisplayName("composed traversal — null root, empty container, null container, null elements")
    void traversalPath() {
      final var names = Telescope.of(Team.class).each(Team::users).field(User::name);
      assertTerminalsAgree(names, null);
      assertTerminalsAgree(names, new Team("t", List.of()));
      assertTerminalsAgree(names, new Team("t", null));
      assertTerminalsAgree(names, new Team("t", List.of(new User(null, null), new User("Bo", null))));
    }

    @Test
    @DisplayName("affine path (as / whenPresent) — hit, miss, null root")
    void affinePath() {
      record Box(Optional<String> nick) {}
      final var nick = Telescope.of(Box.class).whenPresent(Box::nick);
      assertTerminalsAgree(nick, null);
      assertTerminalsAgree(nick, new Box(Optional.of("n")));
      assertTerminalsAgree(nick, new Box(Optional.empty()));
      assertTerminalsAgree(nick, new Box(null));
    }

    @Test
    @DisplayName("genuine Affine path (.as narrow) — hit, miss, null root, null field")
    void narrowPath() {
      // whenPresent composes Lens.then(affine) into a Traversal; only .as(...) leaves a genuine
      // Affine as the stored optic — this fixture is what exercises visitFocuses' Affine branch.
      final var diff = Telescope.of(Event.class).as(Updated.class).field(Updated::diff);
      assertTerminalsAgree(diff, null);
      assertTerminalsAgree(diff, new Updated("e1", "d"));
      assertTerminalsAgree(diff, new Created("e2"));
      assertTerminalsAgree(diff, new Updated("e1", null));
    }

    @Test
    @DisplayName("filtered path — hit, miss, null root")
    void filteredPath() {
      final var longNames = Telescope.of(Team.class)
        .each(Team::users)
        .field(User::name)
        .filter(n -> n != null && n.length() > 2);
      assertTerminalsAgree(longNames, null);
      assertTerminalsAgree(longNames, new Team("t", List.of(new User("Ann", null), new User("Bo", null))));
      assertTerminalsAgree(longNames, new Team("t", List.of(new User("Bo", null))));
    }

    @Test
    @DisplayName("split container form — the typed steps agree too")
    void splitContainerForm() {
      final var names = Telescope.of(Team.class).list(Team::users).each().field(User::name);
      assertTerminalsAgree(names, null);
      assertTerminalsAgree(names, new Team("t", null));
      assertTerminalsAgree(names, new Team("t", List.of(new User("Ann", null))));
    }
  }

  @Nested
  @DisplayName("the stored first-hop name never drifts from the trail")
  class FirstHopTrailAgreement {

    // firstHopName is stored per path (it survives because bare codegen-holder lenses have a name
    // but no trail); this pin makes any skew between the stored value and the trail's first
    // Focus/Traverse node a red test instead of a silent misroute. Bean paths store the raw getter
    // name while the trail stores the property name — PropertyNames.property normalizes both.
    private static void assertFirstHopMatchesTrail(final Telescope<?, ?> t) {
      final var trailFirst = t
        .explain()
        .hops()
        .stream()
        .flatMap(h ->
          h instanceof OpticNode.Focus f
            ? Stream.of(f.path())
            : h instanceof OpticNode.Traverse tr
              ? Stream.of(tr.path())
              : Stream.empty()
        )
        .findFirst();
      trailFirst.ifPresent(expected ->
        assertEquals(expected, PropertyNames.property(t.firstHopName()), "stored first hop must match the trail")
      );
    }

    @Test
    @DisplayName("record paths, container steps, filters, bean paths, and fieldByName all agree")
    void allShapesAgree() {
      assertFirstHopMatchesTrail(Telescope.ofBean(MutableUser.class).field(MutableUser::getName));
      assertFirstHopMatchesTrail(Telescope.of(User.class).field(User::name));
      assertFirstHopMatchesTrail(Telescope.of(User.class).field(User::address).field(Address::city));
      assertFirstHopMatchesTrail(Telescope.of(Team.class).each(Team::users).field(User::name));
      assertFirstHopMatchesTrail(Telescope.of(Team.class).list(Team::users).each().field(User::name));
      assertFirstHopMatchesTrail(
        Telescope.of(Team.class)
          .each(Team::users)
          .filter(u -> true)
          .field(User::name)
      );
      assertFirstHopMatchesTrail(Telescope.of(Team.class).fieldByName("label"));
    }

    public static class MutableUser {

      private String name;

      public MutableUser() {}

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }
    }
  }
}
