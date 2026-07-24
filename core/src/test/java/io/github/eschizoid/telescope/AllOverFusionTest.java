package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Edit.over;
import static io.github.eschizoid.telescope.Edit.overIfPresent;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code Telescope.all}'s fusion pass against the sequential fold it replaces: every fused
 * composition must produce the exact result the edit-by-edit application produces, the documented
 * fallbacks must stay correct, and filters — whose verdicts are value-dependent — must re-test
 * between edits exactly as the sequential fold does (the two review counterexamples are pinned as
 * regressions).
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
  @DisplayName("filter semantics — the value-dependent re-test is preserved")
  class FilterSemantics {

    @Test
    @DisplayName("an edit that changes what the predicate reads hides the element from later edits")
    void editedValueFailsLaterFilterPass() {
      // Sequential: pass 1 demotes Ann to 20; pass 2 re-tests the filter on the EDITED user, so
      // Ann no longer matches and her name stays "Ann". A fused pass that tested the predicate
      // once on the original element would wrongly uppercase her — the review counterexample.
      final var adults = Telescope.of(Team.class)
        .each(Team::users)
        .filter(u -> u.age() >= 30);
      final var ages = over(adults.field(User::age), (final Integer a) -> a - 10);
      final var names = over(adults.field(User::name), String::toUpperCase);

      final var out = Telescope.all(ages, names).apply(TEAM);
      assertEquals(sequential(TEAM, ages, names), out);
      assertEquals("Ann", out.users().get(0).name()); // demoted to 20 before the name pass
      assertEquals(20, out.users().get(0).age());
      assertEquals("CY", out.users().get(2).name()); // 41 stays >= 30 through both passes
    }

    @Test
    @DisplayName("equal paths below a filter re-test between the composed functions")
    void equalPathsBelowFilterReTest() {
      // Ann: 30 - 10 = 20, then the second edit's filter pass rejects her — 20, not (30-10)*2.
      final var adults = Telescope.of(Team.class)
        .each(Team::users)
        .filter(u -> u.age() >= 30);
      final var demote = over(adults.field(User::age), (final Integer a) -> a - 10);
      final var doubleAge = over(adults.field(User::age), (final Integer a) -> a * 2);

      final var out = Telescope.all(demote, doubleAge).apply(TEAM);
      assertEquals(sequential(TEAM, demote, doubleAge), out);
      assertEquals(20, out.users().get(0).age());
      assertEquals(62, out.users().get(2).age()); // Cy: (41 - 10) = 31, still matching, * 2
    }

    @Test
    @DisplayName("a single edit under a filter takes the fused path and matches sequential")
    void singleEditUnderFilter() {
      final var evaluations = new AtomicInteger();
      final var adults = Telescope.of(Team.class)
        .each(Team::users)
        .filter(u -> {
          evaluations.incrementAndGet();
          return u.age() >= 30;
        });
      final var names = over(adults.field(User::name), String::toUpperCase);
      final var label = over(Telescope.of(Team.class).field(Team::label), String::toUpperCase);

      evaluations.set(0);
      final var out = Telescope.all(names, label).apply(TEAM);
      assertEquals(3, evaluations.get()); // one edit under the filter: one test per element
      assertEquals(sequential(TEAM, names, label), out);
    }
  }

  @Nested
  @DisplayName("container and paradigm coverage")
  class ContainerCoverage {

    record Prefs(Map<String, String> flags, Optional<String> nickname, String plan) {}

    @Test
    @DisplayName("eachValue + whenPresent + field fuse at one record level and match sequential")
    void mapOptionalAndFieldAtOneLevel() {
      final var prefs = new Prefs(Map.of("a", "1", "b", "2"), Optional.of("nick"), "pro");
      final var flags = over(Telescope.of(Prefs.class).eachValue(Prefs::flags), (final String v) -> v + "!");
      final var nick = over(Telescope.of(Prefs.class).whenPresent(Prefs::nickname), String::toUpperCase);
      final var plan = over(Telescope.of(Prefs.class).field(Prefs::plan), String::toUpperCase);

      assertEquals(sequential(prefs, flags, nick, plan), Telescope.all(flags, nick, plan).apply(prefs));
    }

    @Test
    @DisplayName("empty containers and empty Optionals pass through the fused walk unchanged")
    void emptyContainers() {
      final var empty = new Prefs(Map.of(), Optional.empty(), "free");
      final var flags = over(Telescope.of(Prefs.class).eachValue(Prefs::flags), (final String v) -> v + "!");
      final var nick = over(Telescope.of(Prefs.class).whenPresent(Prefs::nickname), String::toUpperCase);

      assertEquals(sequential(empty, flags, nick), Telescope.all(flags, nick).apply(empty));
      assertEquals("free", Telescope.all(flags, nick).apply(empty).plan());
    }

    @Test
    @DisplayName("a shared as(...) prefix narrows once and matches sequential")
    void sharedNarrowPrefix() {
      final var updated = Telescope.of(EventBox.class).field(EventBox::event).as(Updated.class);
      final var diff = over(updated.field(Updated::diff), String::toUpperCase);
      final var rev = over(updated.field(Updated::revision), (final Integer r) -> r + 1);

      final var hit = new EventBox(new Updated("e1", "d", 1));
      final var miss = new EventBox(new Created("e2"));
      assertEquals(sequential(hit, diff, rev), Telescope.all(diff, rev).apply(hit));
      assertEquals(sequential(miss, diff, rev), Telescope.all(diff, rev).apply(miss));
      assertEquals(new Updated("e1", "D", 2), Telescope.all(diff, rev).apply(hit).event());
    }

    @Test
    @DisplayName("bean-rooted paths fuse the shared prefix walk and match sequential")
    void beanPaths() {
      final var box = new MutableBox();
      box.setLeft("l");
      box.setRight("r");
      final var left = over(Telescope.ofBean(MutableBox.class).field(MutableBox::getLeft), String::toUpperCase);
      final var right = over(
        Telescope.ofBean(MutableBox.class).field(MutableBox::getRight),
        (final String v) -> v + "!"
      );

      final var fused = Telescope.all(left, right).apply(box);
      final var seq = sequential(box, left, right);
      assertEquals(seq.getLeft(), fused.getLeft());
      assertEquals(seq.getRight(), fused.getRight());
      assertEquals("L", fused.getLeft());
      assertEquals("r!", fused.getRight());
    }
  }

  sealed interface Event permits Created, Updated {}

  record Created(String id) implements Event {}

  record Updated(String id, String diff, int revision) implements Event {}

  record EventBox(Event event) {}

  static final class MutableBox {

    private String left;
    private String right;

    public MutableBox() {}

    public String getLeft() {
      return left;
    }

    public void setLeft(final String left) {
      this.left = left;
    }

    public String getRight() {
      return right;
    }

    public void setRight(final String right) {
      this.right = right;
    }
  }
}
