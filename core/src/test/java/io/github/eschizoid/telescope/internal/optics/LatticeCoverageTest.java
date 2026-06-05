package io.github.eschizoid.telescope.internal.optics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.optics.collections.Traversals;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Direct coverage tests for the optic-lattice primitives — {@link Fold} / {@link Getter} default
 * views, {@link Iso} lift helpers + composition diamonds, {@link Lens} / {@link Prism} / {@link
 * Affine} composition arms, {@link Focus} static factories, and {@link Traversals} runtime
 * dispatch. Complements {@link OpticLawsTest} (which pins the laws); this file pins behavior of the
 * surface methods rung-by-rung so a regression in any one default or factory surfaces independently
 * from the end-to-end DSL.
 */
class LatticeCoverageTest {

  // ----- Fixtures -----

  record Address(String city, String zip) {}

  record User(String name, int age, Address address) {}

  sealed interface Event permits Created, Updated, Deleted {}

  record Created(String id) implements Event {}

  record Updated(String id, String diff) implements Event {}

  record Deleted(String id) implements Event {}

  static final User ALICE = new User("Alice", 30, new Address("NYC", "10001"));

  static final Lens<User, String> userName = Lens.of(User::name, (u, n) -> new User(n, u.age(), u.address()));

  static final Lens<User, Address> userAddress = Lens.of(User::address, (u, a) -> new User(u.name(), u.age(), a));

  static final Lens<Address, String> addressCity = Lens.of(Address::city, (a, c) -> new Address(c, a.zip()));

  // ----- Fold defaults -----

  @Nested
  @DisplayName("Fold defaults — toList, findFirst, any, count over a multi-focus")
  class FoldDefaults {

    @Test
    @DisplayName("a multi-element Fold yields each focused value through every default method")
    void multiElementFold() {
      final Fold<List<Integer>, Integer> all = List::stream;
      final var src = List.of(1, 2, 3, 4);
      assertEquals(List.of(1, 2, 3, 4), all.toList(src));
      assertEquals(Optional.of(3), all.findFirst(src, n -> n > 2));
      assertEquals(Optional.empty(), all.findFirst(src, n -> n > 99));
      assertTrue(all.any(src, n -> n == 2));
      assertFalse(all.any(src, n -> n == 99));
      assertEquals(4L, all.count(src));
    }

    @Test
    @DisplayName("an empty Fold returns empty defaults — no NPE from null source on a non-default Fold")
    void emptyFold() {
      final Fold<List<Integer>, Integer> none = src -> java.util.stream.Stream.empty();
      final var src = List.<Integer>of();
      assertEquals(List.of(), none.toList(src));
      assertEquals(Optional.empty(), none.findFirst(src, n -> true));
      assertFalse(none.any(src, n -> true));
      assertEquals(0L, none.count(src));
    }
  }

  // ----- Getter -----

  @Nested
  @DisplayName("Getter — single-focus read + Fold view")
  class GetterTests {

    @Test
    @DisplayName("get returns the single A; getAll yields a singleton stream")
    void getAndGetAll() {
      final Getter<User, String> name = User::name;
      assertEquals("Alice", name.get(ALICE));
      assertEquals(List.of("Alice"), name.toList(ALICE));
      assertEquals(1L, name.count(ALICE));
      assertTrue(name.any(ALICE, "Alice"::equals));
    }
  }

  // ----- Lens composition arms -----

  @Nested
  @DisplayName("Lens composition — Lens.then(Lens|Iso|Prism)")
  class LensComposition {

    @Test
    @DisplayName("Lens.then(Lens) reads through both and writes through both")
    void lensThenLens() {
      final Lens<User, String> userCity = userAddress.then(addressCity);
      assertEquals("NYC", userCity.get(ALICE));
      assertEquals("SF", userCity.get(userCity.set(ALICE, "SF")));
      // The set-set law on the composition:
      assertEquals(userCity.set(ALICE, "B"), userCity.set(userCity.set(ALICE, "A"), "B"));
    }

    @Test
    @DisplayName("Lens.then(Iso) lifts the iso through and stays a Lens")
    void lensThenIso() {
      final Iso<String, String> reverse = Iso.of(
        s -> new StringBuilder(s).reverse().toString(),
        s -> new StringBuilder(s).reverse().toString()
      );
      final Lens<User, String> reversedName = userName.then(reverse);
      assertEquals("ecilA", reversedName.get(ALICE));
      // Setting through the iso applies the backward direction:
      assertEquals("Bob", reversedName.set(ALICE, "boB").name());
    }

    @Test
    @DisplayName("Lens.then(Prism) widens to Affine — a Prism miss leaves the source untouched")
    void lensThenPrismIsAffine() {
      // A Lens<User, Event> via a synthetic event field; compose with a Prism that targets only
      // Updated.
      final Lens<Holder, Event> lens = Lens.of(Holder::event, (h, e) -> new Holder(h.id(), e));
      final Prism<Event, Updated> prism = Prism.downcast(Updated.class);
      final Affine<Holder, Updated> composed = lens.then(prism);
      assertInstanceOf(Affine.class, composed);

      final Holder withUpdated = new Holder("h1", new Updated("u1", "diff"));
      final Holder withCreated = new Holder("h1", new Created("c1"));
      assertEquals(Optional.of(new Updated("u1", "diff")), composed.getOption(withUpdated));
      assertEquals(Optional.empty(), composed.getOption(withCreated));

      // miss → source unchanged
      assertEquals(withCreated, composed.modify(withCreated, u -> new Updated(u.id(), "updated")));
      // hit → flows through
      assertEquals(
        new Holder("h1", new Updated("u1", "new")),
        composed.modify(withUpdated, u -> new Updated(u.id(), "new"))
      );
    }
  }

  record Holder(String id, Event event) {}

  // ----- Prism composition + behavior -----

  @Nested
  @DisplayName("Prism — modify on hit/miss, downcast, then(Prism|Iso|Lens)")
  class PrismTests {

    @Test
    @DisplayName("downcast Prism returns getOption hit on instance, empty otherwise; reverseGet widens")
    void downcastBehavior() {
      final Prism<Event, Updated> p = Prism.downcast(Updated.class);
      final Event upd = new Updated("u1", "d");
      final Event crd = new Created("c1");
      assertEquals(Optional.of(upd), p.getOption(upd));
      assertEquals(Optional.empty(), p.getOption(crd));
      assertSame(upd, p.reverseGet((Updated) upd)); // identity widening
    }

    @Test
    @DisplayName("modify on a miss returns the source unchanged (the partial round-trip law)")
    void modifyMiss() {
      final Prism<Event, Updated> p = Prism.downcast(Updated.class);
      final Event crd = new Created("c1");
      assertSame(crd, p.modify(crd, u -> new Updated(u.id(), "x")));
    }

    @Test
    @DisplayName("modify on a hit applies the function and rebuilds via reverseGet")
    void modifyHit() {
      final Prism<Event, Updated> p = Prism.downcast(Updated.class);
      final Event upd = new Updated("u1", "d");
      assertEquals(new Updated("u1", "new"), p.modify(upd, u -> new Updated(u.id(), "new")));
    }

    @Test
    @DisplayName("getAll yields a singleton when the prism matches, empty otherwise")
    void prismGetAll() {
      final Prism<Event, Updated> p = Prism.downcast(Updated.class);
      assertEquals(1L, p.getAll(new Updated("u1", "d")).count());
      assertEquals(0L, p.getAll(new Created("c1")).count());
    }

    @Test
    @DisplayName("Prism.then(Prism) composes two narrowings; misses short-circuit")
    void prismThenPrism() {
      // Outer Prism: Event → Updated. Inner Prism: Updated → an Updated whose diff is "real".
      final Prism<Event, Updated> outer = Prism.downcast(Updated.class);
      final Prism<Updated, Updated> realDiff = Prism.of(
        u -> "real".equals(u.diff()) ? Optional.of(u) : Optional.empty(),
        u -> u
      );
      final Prism<Event, Updated> composed = outer.then(realDiff);
      assertEquals(Optional.of(new Updated("u1", "real")), composed.getOption(new Updated("u1", "real")));
      assertEquals(Optional.empty(), composed.getOption(new Updated("u1", "fake")));
      assertEquals(Optional.empty(), composed.getOption(new Created("c1")));
      assertEquals(new Updated("u1", "x"), composed.reverseGet(new Updated("u1", "x")));
    }

    @Test
    @DisplayName("Prism.then(Iso) stays a Prism, lifting the iso through both directions")
    void prismThenIso() {
      final Prism<Event, Updated> outer = Prism.downcast(Updated.class);
      final Iso<Updated, String> diffOnly = Iso.of(Updated::diff, d -> new Updated("synth", d));
      final Prism<Event, String> composed = outer.then(diffOnly);
      assertEquals(Optional.of("d"), composed.getOption(new Updated("u1", "d")));
      assertEquals(Optional.empty(), composed.getOption(new Created("c1")));
      assertEquals(new Updated("synth", "x"), composed.reverseGet("x"));
    }

    @Test
    @DisplayName("Prism.then(Lens) widens to Affine — the Lens contributes the read+write half")
    void prismThenLens() {
      final Prism<Event, Updated> outer = Prism.downcast(Updated.class);
      final Lens<Updated, String> diff = Lens.of(Updated::diff, (u, d) -> new Updated(u.id(), d));
      final Affine<Event, String> composed = outer.then(diff);
      assertInstanceOf(Affine.class, composed);
      assertEquals(Optional.of("d"), composed.getOption(new Updated("u1", "d")));
      assertEquals(Optional.empty(), composed.getOption(new Created("c1")));
      // hit → modify flows through; miss → unchanged
      assertEquals(new Updated("u1", "dD"), composed.modify(new Updated("u1", "d"), d -> d + "D"));
      final Event crd = new Created("c1");
      assertSame(crd, composed.modify(crd, d -> d + "!"));
    }
  }

  // ----- Affine -----

  @Nested
  @DisplayName("Affine — of(), modify on hit/miss, then(Affine)")
  class AffineTests {

    @Test
    @DisplayName("Affine.of getOption + modify behaves as documented (miss = source unchanged)")
    void affineModifyHitMiss() {
      final Affine<List<Integer>, Integer> head = Affine.of(
        xs -> xs.isEmpty() ? Optional.empty() : Optional.of(xs.get(0)),
        (xs, n) -> {
          final var c = new ArrayList<>(xs);
          c.set(0, n);
          return c;
        }
      );
      assertEquals(Optional.of(1), head.getOption(List.of(1, 2, 3)));
      assertEquals(List.of(99, 2, 3), head.modify(List.of(1, 2, 3), n -> n + 98));
      // miss: empty list → source unchanged (and same reference under modify with no-op semantics)
      final var empty = List.<Integer>of();
      assertSame(empty, head.modify(empty, n -> n + 1));
    }

    @Test
    @DisplayName("Affine.then(Affine) composes hit-then-hit, otherwise misses through")
    void affineThenAffine() {
      // Outer: Optional<List<Integer>> → List<Integer> (when present)
      final Affine<Optional<List<Integer>>, List<Integer>> outer = Affine.of(
        o -> o,
        (o, v) -> o.isPresent() ? Optional.of(v) : o
      );
      // Inner: List<Integer> → first element (when non-empty)
      final Affine<List<Integer>, Integer> inner = Affine.of(
        xs -> xs.isEmpty() ? Optional.empty() : Optional.of(xs.get(0)),
        (xs, n) -> {
          final var c = new ArrayList<>(xs);
          c.set(0, n);
          return c;
        }
      );
      final Affine<Optional<List<Integer>>, Integer> composed = outer.then(inner);
      assertEquals(Optional.of(1), composed.getOption(Optional.of(List.of(1, 2))));
      assertEquals(Optional.empty(), composed.getOption(Optional.empty()));
      assertEquals(Optional.empty(), composed.getOption(Optional.of(List.of()))); // outer hits, inner misses
      assertEquals(Optional.of(List.of(7, 2)), composed.modify(Optional.of(List.of(1, 2)), n -> n + 6));
      // miss path: outer miss → identity
      assertEquals(Optional.empty(), composed.modify(Optional.empty(), n -> n + 1));
    }
  }

  // ----- Iso lift helpers + composition -----

  @Nested
  @DisplayName("Iso lifts — list/set/optional/map and composition Iso.then(Iso|Lens|Prism)")
  class IsoLiftsAndComposition {

    static final Iso<Integer, String> intStr = Iso.of(Object::toString, Integer::parseInt);

    @Test
    @DisplayName("liftList: round-trip preserves order and per-element conversion; null pass-through")
    void liftList() {
      final var listIso = Iso.liftList(intStr);
      assertEquals(List.of("1", "2", "3"), listIso.to(List.of(1, 2, 3)));
      assertEquals(List.of(1, 2, 3), listIso.from(List.of("1", "2", "3")));
      assertNull(listIso.to(null));
      assertNull(listIso.from(null));
    }

    @Test
    @DisplayName("liftSet: round-trip preserves Set.equals semantics; null pass-through")
    void liftSet() {
      final var setIso = Iso.liftSet(intStr);
      assertEquals(Set.of("1", "2"), setIso.to(new LinkedHashSet<>(List.of(1, 2))));
      assertEquals(Set.of(1, 2), setIso.from(new LinkedHashSet<>(List.of("1", "2"))));
      assertNull(setIso.to(null));
      assertNull(setIso.from(null));
    }

    @Test
    @DisplayName("liftOptional: empty round-trips to empty; null pass-through")
    void liftOptional() {
      final var optIso = Iso.liftOptional(intStr);
      assertEquals(Optional.of("7"), optIso.to(Optional.of(7)));
      assertEquals(Optional.of(7), optIso.from(Optional.of("7")));
      assertEquals(Optional.empty(), optIso.to(Optional.empty()));
      assertEquals(Optional.empty(), optIso.from(Optional.empty()));
      assertNull(optIso.to(null));
      assertNull(optIso.from(null));
    }

    @Test
    @DisplayName("liftMapValues: keys preserved through both directions; null pass-through")
    void liftMapValues() {
      final var mapIso = Iso.<String, Integer, String>liftMapValues(intStr);
      assertEquals(Map.of("a", "1", "b", "2"), mapIso.to(Map.of("a", 1, "b", 2)));
      assertEquals(Map.of("a", 1, "b", 2), mapIso.from(Map.of("a", "1", "b", "2")));
      assertNull(mapIso.to(null));
      assertNull(mapIso.from(null));
    }

    @Test
    @DisplayName("Iso.reverse swaps to and from")
    void isoReverse() {
      final var reversed = intStr.reverse();
      assertEquals(7, reversed.to("7"));
      assertEquals("7", reversed.from(7));
    }

    @Test
    @DisplayName("Iso.then(Iso) composes both directions through the chain")
    void isoThenIso() {
      final Iso<String, String> upper = Iso.of(String::toUpperCase, String::toLowerCase);
      final Iso<Integer, String> composed = intStr.then(upper);
      assertEquals("7", composed.to(7));
      assertEquals(7, composed.from("7"));
    }

    @Test
    @DisplayName("Iso.then(Lens) drops to a Lens (diamond resolution)")
    void isoThenLens() {
      final Iso<User, User> idIso = Iso.identity();
      final Lens<User, String> composed = idIso.then(userName);
      assertEquals("Alice", composed.get(ALICE));
      assertEquals("Bob", composed.get(composed.set(ALICE, "Bob")));
    }

    @Test
    @DisplayName("Iso.then(Prism) drops to a Prism (diamond resolution)")
    void isoThenPrism() {
      final Iso<Event, Event> idIso = Iso.identity();
      final Prism<Event, Updated> updPrism = Prism.downcast(Updated.class);
      final Prism<Event, Updated> composed = idIso.then(updPrism);
      assertEquals(Optional.of(new Updated("u", "d")), composed.getOption(new Updated("u", "d")));
      assertEquals(Optional.empty(), composed.getOption(new Created("c")));
      assertEquals(new Updated("x", "y"), composed.reverseGet(new Updated("x", "y")));
    }

    @Test
    @DisplayName("Iso default views match Lens (get/set) and Prism (reverseGet/getOption)")
    void isoDefaultViews() {
      assertEquals("7", intStr.get(7));
      assertEquals(7, intStr.set(7, "7")); // backward(value) ignores source
      assertEquals(7, intStr.reverseGet("7"));
      assertEquals(Optional.of("7"), intStr.getOption(7));
      // modify: lift through to-from
      assertEquals(8, intStr.modify(7, s -> String.valueOf(Integer.parseInt(s) + 1)));
      // getAll yields one element
      assertEquals(1L, intStr.getAll(7).count());
    }
  }

  // ----- Focus static factories -----

  @Nested
  @DisplayName("Focus — aggregator factories")
  class FocusTests {

    @Test
    @DisplayName("Focus.lens / prism (downcast & partial) / iso / affine all build a working optic")
    void focusFactories() {
      final var lens = Focus.lens(User::name, (u, n) -> new User(n, u.age(), u.address()));
      assertEquals("Alice", lens.get(ALICE));

      final var prismDown = Focus.prism(Updated.class);
      assertEquals(Optional.of(new Updated("u", "d")), prismDown.getOption(new Updated("u", "d")));

      final Prism<Event, Updated> prismPartial = Focus.prism(
        e -> e instanceof Updated u ? Optional.of(u) : Optional.empty(),
        u -> u
      );
      assertEquals(Optional.of(new Updated("u", "d")), prismPartial.getOption(new Updated("u", "d")));
      assertEquals(Optional.empty(), prismPartial.getOption(new Created("c")));

      final Iso<Integer, String> iso = Focus.iso(Object::toString, Integer::parseInt);
      assertEquals("7", iso.to(7));
      assertEquals(7, iso.from("7"));

      final var affine = Focus.affine(
        (User u) -> Optional.ofNullable(u.address()),
        (u, a) -> new User(u.name(), u.age(), a)
      );
      assertEquals(Optional.of(new Address("NYC", "10001")), affine.getOption(ALICE));
      final var moved = affine.modify(ALICE, a -> new Address("SF", "94016"));
      assertEquals("SF", moved.address().city());
    }
  }

  // ----- Traversals.eachContainer runtime dispatch -----

  @Nested
  @DisplayName("Traversals.eachContainer — runtime dispatch over List/Set/Map/Optional/array/null")
  class EachContainerDispatch {

    @Test
    @DisplayName("List branch: streams elements and rebuilds via modify")
    void listBranch() {
      final Traversal<List<Integer>, Integer> each = Traversals.eachContainer();
      assertEquals(List.of(1, 2, 3), each.getAll(List.of(1, 2, 3)).toList());
      assertEquals(List.of(2, 4, 6), each.modify(List.of(1, 2, 3), n -> n * 2));
    }

    @Test
    @DisplayName("Set branch: streams and rebuilds into LinkedHashSet (order preserved)")
    void setBranch() {
      final Traversal<Set<Integer>, Integer> each = Traversals.eachContainer();
      final var src = new LinkedHashSet<>(List.of(1, 2, 3));
      assertEquals(Set.of(1, 2, 3), each.getAll(src).collect(java.util.stream.Collectors.toSet()));
      assertEquals(Set.of(2, 4, 6), each.modify(src, n -> n * 2));
    }

    @Test
    @DisplayName("Map branch: streams values; rebuilds preserving keys")
    void mapBranch() {
      final Traversal<Map<String, Integer>, Integer> each = Traversals.eachContainer();
      final var src = Map.of("a", 1, "b", 2);
      assertEquals(Set.of(1, 2), each.getAll(src).collect(java.util.stream.Collectors.toSet()));
      final var out = each.modify(src, n -> n + 10);
      assertEquals(11, out.get("a"));
      assertEquals(12, out.get("b"));
    }

    @Test
    @DisplayName("Optional branch: present streams one element; modify lifts through")
    void optionalBranch() {
      final Traversal<Optional<Integer>, Integer> each = Traversals.eachContainer();
      assertEquals(List.of(7), each.getAll(Optional.of(7)).toList());
      assertEquals(Optional.of(8), each.modify(Optional.of(7), n -> n + 1));
      assertEquals(Optional.empty(), each.modify(Optional.empty(), n -> n + 1));
    }

    @Test
    @DisplayName("Array branch: int[] is dispatched via reflection — modify rebuilds an int[]")
    void arrayPrimitiveBranch() {
      final Traversal<int[], Integer> each = Traversals.eachContainer();
      final int[] src = { 1, 2, 3 };
      assertEquals(List.of(1, 2, 3), each.getAll(src).toList());
      final int[] doubled = each.modify(src, n -> n * 2);
      assertArrayEquals(new int[] { 2, 4, 6 }, doubled);
    }

    @Test
    @DisplayName("Array branch: Object[] is rebuilt as Object[]")
    void arrayObjectBranch() {
      final Traversal<String[], String> each = Traversals.eachContainer();
      final String[] src = { "a", "b" };
      assertEquals(List.of("a", "b"), each.getAll(src).toList());
      final String[] upper = each.modify(src, String::toUpperCase);
      assertArrayEquals(new String[] { "A", "B" }, upper);
    }

    @Test
    @DisplayName("null source streams empty / modify returns null (no NPE)")
    void nullBranch() {
      final Traversal<List<Integer>, Integer> each = Traversals.eachContainer();
      assertEquals(0L, each.getAll(null).count());
      assertNull(each.modify(null, n -> n + 1));
    }

    @Test
    @DisplayName("Unsupported container type throws IllegalArgumentException with a clear message")
    void unsupportedThrows() {
      final Traversal<Object, Object> each = Traversals.eachContainer();
      // Plain Object (not a recognized container, not an array) → throw.
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        each.getAll(Objects.requireNonNull("x")).count()
      );
      assertTrue(ex.getMessage().contains("each()"), ex.getMessage());
      final var ex2 = assertThrows(IllegalArgumentException.class, () -> each.modify("x", o -> o));
      assertTrue(ex2.getMessage().contains("each()"), ex2.getMessage());
    }
  }

  // ----- typed Traversals (List/Set/Map/Optional) -----

  @Nested
  @DisplayName("Typed Traversals — eachList/eachSet/eachMapValue/eachOptional")
  class TypedTraversals {

    @Test
    @DisplayName("eachList rebuilds an unmodifiable list with each function applied")
    void eachList() {
      final var t = Traversals.<Integer>eachList();
      assertEquals(List.of(1, 2, 3), t.getAll(List.of(1, 2, 3)).toList());
      assertEquals(List.of(2, 4, 6), t.modify(List.of(1, 2, 3), n -> n * 2));
    }

    @Test
    @DisplayName("eachSet rebuilds an unmodifiable LinkedHashSet preserving insertion order")
    void eachSet() {
      final var t = Traversals.<Integer>eachSet();
      final var src = new LinkedHashSet<>(List.of(1, 2, 3));
      assertEquals(Set.of(1, 2, 3), t.getAll(src).collect(java.util.stream.Collectors.toSet()));
      assertEquals(Set.of(2, 4, 6), t.modify(src, n -> n * 2));
    }

    @Test
    @DisplayName("eachMapValue rebuilds preserving keys and applying f to each value")
    void eachMapValue() {
      final var t = Traversals.<String, Integer>eachMapValue();
      final var src = Map.of("a", 1, "b", 2);
      final var out = t.modify(src, n -> n + 10);
      assertEquals(11, out.get("a"));
      assertEquals(12, out.get("b"));
    }

    @Test
    @DisplayName("eachOptional is an Affine — empty round-trips to empty; present is set")
    void eachOptional() {
      final var t = Traversals.<Integer>eachOptional();
      assertEquals(Optional.of(7), t.getOption(Optional.of(7)));
      assertEquals(Optional.empty(), t.getOption(Optional.empty()));
      assertEquals(Optional.of(8), t.modify(Optional.of(7), n -> n + 1));
      assertEquals(Optional.empty(), t.modify(Optional.empty(), n -> n + 1));
    }
  }

  // Suppress the static-fixture unused-field warning for fixtures referenced only inside nested
  // classes — javac sees them as unread at the outer level, but they're load-bearing inside.
  @Test
  @DisplayName("smoke: outer-class fixtures are reachable from nested tests")
  void smokeFixtures() {
    assertNotNull(ALICE);
    assertNotNull(userName);
    assertNotNull(userAddress);
    assertNotNull(addressCity);
  }
}
