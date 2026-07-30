package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Edit.over;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins the typed-container tier ({@code .list()/.setField()/.mapField()/.optional()} + terminals)
 * to full parity with the fused {@code each(getter)}/{@code eachValue}/{@code whenPresent} forms:
 * the split form must produce the same introspection trail and participate in multi-edit fusion
 * identically, and a promotion ({@code asList(...)}) must preserve — not erase — the trail it was
 * handed. Before these fixes the whole tier produced partial-but-authoritative-looking {@code
 * explain()} output and was invisible to {@code Telescope.all} fusion.
 */
class ContainerStepParityTest {

  record User(String name, String email) {}

  record Team(String label, List<User> users, Map<String, Integer> scores, Optional<String> motto) {}

  static final Team TEAM = new Team(
    "core",
    List.of(new User("Ann", "ANN@x.io"), new User("Bo", "BO@x.io")),
    Map.of("a", 1),
    Optional.of("go")
  );

  @Nested
  @DisplayName("the split form explains identically to the fused form")
  class TrailParity {

    @Test
    @DisplayName("list(...).each() renders the same trail as each(getter)")
    void listStepTrailMatchesEach() {
      final var fused = Telescope.of(Team.class).each(Team::users).field(User::email);
      final var split = Telescope.of(Team.class).list(Team::users).each().field(User::email);
      assertEquals(fused.explain().hops(), split.explain().hops());
    }

    @Test
    @DisplayName("mapField(...).values() renders the same trail as eachValue(getter)")
    void mapStepTrailMatchesEachValue() {
      final var fused = Telescope.of(Team.class).eachValue(Team::scores);
      final var split = Telescope.of(Team.class).mapField(Team::scores).values();
      assertEquals(fused.explain().hops(), split.explain().hops());
    }

    @Test
    @DisplayName("optional(...).present() renders the same trail as whenPresent(getter)")
    void optionalStepTrailMatchesWhenPresent() {
      final var fused = Telescope.of(Team.class).whenPresent(Team::motto);
      final var split = Telescope.of(Team.class).optional(Team::motto).present();
      assertEquals(fused.explain().hops(), split.explain().hops());
    }

    @Test
    @DisplayName("the un-descended container step itself explains as a field focus")
    void containerStepAloneIsAFocus() {
      final var step = Telescope.of(Team.class).list(Team::users);
      assertFalse(step.explain().hops().isEmpty());
    }
  }

  @Nested
  @DisplayName("the split form participates in fusion like the fused form")
  class FusionParity {

    @Test
    @DisplayName("a split-form edit and a fused-form edit share the container prefix")
    void splitAndFusedShareThePrefix() {
      // Both edits traverse users; if the split form carries the fused form's hop identity, they
      // fuse into one container pass and the result equals sequential application either way.
      final var names = over(Telescope.of(Team.class).list(Team::users).each().field(User::name), String::toLowerCase);
      final var emails = over(Telescope.of(Team.class).each(Team::users).field(User::email), String::toLowerCase);

      final var fusedResult = Telescope.all(names, emails).apply(TEAM);
      var sequential = TEAM;
      sequential = names.apply(sequential);
      sequential = emails.apply(sequential);
      assertEquals(sequential, fusedResult);
      assertEquals("ann", fusedResult.users().get(0).name());
      assertEquals("ann@x.io", fusedResult.users().get(0).email());
    }
  }

  @Nested
  @DisplayName("promotions preserve what they are handed")
  class PromotionPreservation {

    @Test
    @DisplayName("asList keeps the promoted path's trail instead of erasing it")
    void asListKeepsTrail() {
      final var built = Telescope.of(Team.class).field(Team::users);
      final var promoted = Telescope.asList(built);
      assertEquals(built.explain().hops(), promoted.explain().hops());

      // Descending after a promotion keeps the (possibly partial) trail rather than fabricating
      // container nodes it cannot verify; fusion identity is unknown, so such paths fall back.
      final var descended = promoted.each();
      assertEquals(built.explain().hops(), descended.explain().hops());
    }
  }
}
