package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Edit.over;
import static io.github.eschizoid.telescope.Edit.overIfPresent;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code Telescope.all}'s fusion pass against the sequential fold it replaces: every fused
 * composition must produce the exact result the edit-by-edit application produces, the documented
 * fallbacks must stay correct, and the one observable difference — how many structural passes a
 * shared prefix runs — is pinned via a counting filter predicate.
 */
class AllOverFusionTest {

  record Flat(String a, String b, String c, int d) {}

  record User(String name, String email, int age) {}

  record Team(String label, List<User> users) {}

  record Wrap(String tag, Team team) {}

  static final Flat FLAT = new Flat("a", "b", "c", 4);
  static final Team TEAM = new Team(
    "core",
    List.of(new User("Ann", "ANN@x.io", 30), new User("Bo", "BO@x.io", 25), new User("Cy", "CY@x.io", 41))
  );

  /** The spec: what the sequential fold produces, edit by edit. */
  @SafeVarargs
  private static <S> S sequential(final S source, final Edit<S>... edits) {
    var s = source;
    for (final var e : edits) s = e.apply(s);
    return s;
  }

  @Nested
  @DisplayName("fused result == sequential result")
  class Equivalence {

    @Test
    @DisplayName("flat disjoint fields — the record-level slot plan")
    void flatDisjointFields() {
      final var ea = over(Telescope.of(Flat.class).field(Flat::a), String::toUpperCase);
      final var eb = over(Telescope.of(Flat.class).field(Flat::b), (final String v) -> v + "!");
      final var ed = over(Telescope.of(Flat.class).field(Flat::d), (final Integer v) -> v * 10);

      assertEquals(sequential(FLAT, ea, eb, ed), Telescope.all(ea, eb, ed).apply(FLAT));
      assertEquals(new Flat("A", "b!", "c", 40), Telescope.all(ea, eb, ed).apply(FLAT));
    }

    @Test
    @DisplayName("shared each(...) prefix with distinct leaf fields — trie sharing + per-element slot plan")
    void sharedEachPrefix() {
      final var names = over(Telescope.of(Team.class).each(Team::users).field(User::name), String::toLowerCase);
      final var emails = over(Telescope.of(Team.class).each(Team::users).field(User::email), String::toLowerCase);
      final var ages = over(Telescope.of(Team.class).each(Team::users).field(User::age), (final Integer a) -> a + 1);

      assertEquals(sequential(TEAM, names, emails, ages), Telescope.all(names, emails, ages).apply(TEAM));
    }

    @Test
    @DisplayName("deep nesting — a field hop above the shared each prefix")
    void deepSharedPrefix() {
      final var wrap = new Wrap("t", TEAM);
      final var names = over(
        Telescope.of(Wrap.class).field(Wrap::team).each(Team::users).field(User::name),
        String::toUpperCase
      );
      final var label = over(Telescope.of(Wrap.class).field(Wrap::team).field(Team::label), String::toUpperCase);
      final var tag = over(Telescope.of(Wrap.class).field(Wrap::tag), (final String t) -> t + t);

      assertEquals(sequential(wrap, names, label, tag), Telescope.all(names, label, tag).apply(wrap));
    }

    @Test
    @DisplayName("equal full paths compose in edit order — sequential semantics preserved")
    void equalPathsComposeInOrder() {
      final var first = over(Telescope.of(Flat.class).field(Flat::a), (final String v) -> v + "1");
      final var second = over(Telescope.of(Flat.class).field(Flat::a), (final String v) -> v + "2");

      // Order-sensitive on purpose: "a" -> "a1" -> "a12" only if fn1 runs before fn2.
      assertEquals("a12", Telescope.all(first, second).apply(FLAT).a());
      assertEquals(sequential(FLAT, first, second), Telescope.all(first, second).apply(FLAT));
    }

    @Test
    @DisplayName("separately-built paths share by (owner, component) identity, not by instance")
    void separatelyBuiltPathsShare() {
      // Both edits rebuild each(users) — built as fully independent Telescope instances. Semantic
      // keys must unify them; the result must still match sequential application.
      final var e1 = over(Telescope.of(Team.class).each(Team::users).field(User::name), (final String n) -> n + "-x");
      final var e2 = over(Telescope.of(Team.class).each(Team::users).field(User::email), String::toLowerCase);

      assertEquals(sequential(TEAM, e1, e2), Telescope.all(e1, e2).apply(TEAM));
    }

    @Test
    @DisplayName("sparse-PATCH identity slots are skipped and contribute nothing")
    void identitySlotsSkipped() {
      final var real = over(Telescope.of(Flat.class).field(Flat::a), String::toUpperCase);
      final var nullSlot = overIfPresent(Telescope.of(Flat.class).field(Flat::b), (String) null);
      final var nullSlot2 = overIfPresent(Telescope.of(Flat.class).field(Flat::c), null, (final String v) -> v);

      final var out = Telescope.all(nullSlot, real, nullSlot2).apply(FLAT);
      assertEquals(new Flat("A", "b", "c", 4), out);
    }
  }

  @Nested
  @DisplayName("documented fallbacks stay correct")
  class Fallbacks {

    @Test
    @DisplayName("strict-prefix overlap (record edit above a field edit) falls back and matches sequential")
    void strictPrefixOverlap() {
      final var wrap = new Wrap("t", TEAM);
      // Edit 1 rewrites the whole team; edit 2 then edits a field inside it. Order is observable —
      // fusion must decline, and the sequential result is the spec.
      final var wholeTeam = over(Telescope.of(Wrap.class).field(Wrap::team), (final Team t) ->
        new Team(t.label() + "-r", t.users())
      );
      final var innerLabel = over(Telescope.of(Wrap.class).field(Wrap::team).field(Team::label), String::toUpperCase);

      assertEquals(sequential(wrap, wholeTeam, innerLabel), Telescope.all(wholeTeam, innerLabel).apply(wrap));
      assertEquals("CORE-R", Telescope.all(wholeTeam, innerLabel).apply(wrap).team().label());
    }

    @Test
    @DisplayName("an unfusible hop (fieldByName) falls back and matches sequential")
    void unfusibleHopFallsBack() {
      final var byName = over(Telescope.of(Flat.class).<String>fieldByName("a"), String::toUpperCase);
      final var typed = over(Telescope.of(Flat.class).field(Flat::b), (final String v) -> v + "!");

      assertEquals(sequential(FLAT, byName, typed), Telescope.all(byName, typed).apply(FLAT));
    }

    @Test
    @DisplayName("field vs each on the same component falls back (not provably order-free) and matches sequential")
    void sameComponentDifferentKinds() {
      final var whole = over(Telescope.of(Team.class).field(Team::users), (final List<User> us) -> us.subList(0, 2));
      final var each = over(Telescope.of(Team.class).each(Team::users).field(User::name), String::toUpperCase);

      assertEquals(sequential(TEAM, whole, each), Telescope.all(whole, each).apply(TEAM));
    }
  }

  @Nested
  @DisplayName("fusion is observable where it should be")
  class PassCounting {

    @Test
    @DisplayName("a shared filter prefix evaluates its predicate once per element, not once per edit")
    void sharedFilterPrefixRunsOnce() {
      final var evaluations = new AtomicInteger();
      // The shared prefix must be ONE instance: filter identity is the predicate reference.
      final var adults = Telescope.of(Team.class)
        .each(Team::users)
        .filter(u -> {
          evaluations.incrementAndGet();
          return u.age() >= 30;
        });
      final var names = over(adults.field(User::name), String::toUpperCase);
      final var emails = over(adults.field(User::email), String::toLowerCase);

      final var fused = Telescope.all(names, emails);
      evaluations.set(0);
      final var out = fused.apply(TEAM);

      // 3 elements, one structural pass: 3 evaluations. The sequential fold would run the filter
      // once per edit — 6. This is the one observable trace of fusion, pinned on purpose.
      assertEquals(3, evaluations.get());
      assertEquals("ANN", out.users().get(0).name());
      assertEquals("BO@x.io", out.users().get(1).email()); // under-30: filtered out of both edits
      assertEquals(sequential(TEAM, names, emails), out);
    }
  }
}
