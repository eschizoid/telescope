package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.Allocation.Call;
import io.github.eschizoid.telescope.internal.pairing.ContainerView.Kind;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What a declared container is rebuilt as is decided here and rendered twice — once as a bound
 * constructor by the runtime lift, once as the text of a {@code new} expression by the processor. A
 * decision that lives in one place cannot drift between them; these pin what it decides.
 *
 * <p>Which constructor is a separate answer from which class, because a renderer needs both and
 * they move independently: the same "size it from the source" passes an element count to a list and
 * a table capacity to a hash container, which for the same elements is a different number, and a
 * row moved between the two produces the right class at the wrong size.
 */
class AllocationPolicyTest {

  private static final PairingRules<Type> RULES = new PairingRules<>(new ReflectionProps());

  private static Stream<Arguments> rows() {
    return Stream.of(
      Arguments.of(List.class, Kind.LIST, "java.util.ArrayList", Call.COUNT),
      Arguments.of(ArrayList.class, Kind.LIST, "java.util.ArrayList", Call.COUNT),
      Arguments.of(Deque.class, Kind.LIST, "java.util.ArrayDeque", Call.COUNT),
      Arguments.of(LinkedList.class, Kind.LIST, "java.util.LinkedList", Call.NO_ARG),
      // The hash families are the rows worth pinning: a count and a table capacity are different
      // numbers for the same elements, and the container that results is the same class either
      // way.
      Arguments.of(HashSet.class, Kind.SET, "java.util.HashSet", Call.TABLE_FACTORY),
      Arguments.of(Set.class, Kind.SET, "java.util.LinkedHashSet", Call.TABLE_FACTORY),
      Arguments.of(HashMap.class, Kind.MAP_VALUES, "java.util.HashMap", Call.TABLE_FACTORY),
      Arguments.of(WeakHashMap.class, Kind.MAP_VALUES, "java.util.WeakHashMap", Call.TABLE_ARITHMETIC),
      // Sized for the count by the container itself, so the call is the same text as a list's.
      Arguments.of(IdentityHashMap.class, Kind.MAP_VALUES, "java.util.IdentityHashMap", Call.COUNT),
      Arguments.of(ConcurrentHashMap.class, Kind.MAP_VALUES, "java.util.concurrent.ConcurrentHashMap", Call.COUNT),
      Arguments.of(SortedMap.class, Kind.MAP_VALUES, "java.util.TreeMap", Call.ORDERING),
      Arguments.of(TreeSet.class, Kind.SET, "java.util.TreeSet", Call.ORDERING)
    );
  }

  @ParameterizedTest(name = "{0} builds {2} by {3}")
  @MethodSource("rows")
  @DisplayName("a declared container names the implementation it is rebuilt as and the constructor that" + " builds it")
  void declaredTypeDecidesImplementationAndCall(
    final Class<?> declared,
    final Kind kind,
    final String impl,
    final Call call
  ) {
    final var build = assertInstanceOf(Allocation.Build.class, RULES.allocationFor(declared, kind));

    assertEquals(impl, build.implName());
    // Asserted separately from the implementation, because moving a class to a different
    // constructor produces the same class at a different size, which nothing downstream can see.
    assertEquals(call, build.call());
  }

  @Test
  @DisplayName("a Collection is rebuilt as whatever the pair settled on, since it names no shape itself")
  void collectionFollowsTheKindItWasPairedAgainst() {
    // The one declaration whose answer is not a property of the declaration. Reading the name alone
    // rebuilds a set widened into a Collection as a list, which loses the shape the other side had.
    final var asSet = assertInstanceOf(Allocation.Build.class, RULES.allocationFor(Collection.class, Kind.SET));
    final var asList = assertInstanceOf(Allocation.Build.class, RULES.allocationFor(Collection.class, Kind.LIST));

    assertEquals("java.util.LinkedHashSet", asSet.implName());
    assertEquals("java.util.ArrayList", asList.implName());
    // The walk below reaches every declared row and these two are not rows, so their constructor
    // is asserted here or nowhere. Each family's fallback allocator happens to render what these
    // name today, which means a relabel changes nothing observable and no behavioural test can see
    // it.
    assertEquals(Call.TABLE_FACTORY, asSet.call());
    assertEquals(Call.COUNT, asList.call());
  }

  @Test
  @DisplayName("a type with no constructor a rebuild can call carries the sentence to refuse it with")
  void enumMapCarriesItsRefusal() {
    final var refuse = assertInstanceOf(Allocation.Refuse.class, RULES.allocationFor(EnumMap.class, Kind.MAP_VALUES));

    // Not the whole sentence, which would teach the next reader to fix a red gate by pasting
    // prose. What it has to convey: the escape hatch, and the path that does build one.
    assertTrue(refuse.reason().contains("Class<K>"), refuse::reason);
    assertTrue(refuse.reason().contains("Mapping.via"), refuse::reason);
    assertTrue(refuse.reason().contains("codegen"), refuse::reason);
    // These are the only refusal words any test reads, which is sound only while this is the only
    // row that refuses. A second one would arrive with its sentence unasserted.
    assertEquals(
      1,
      EXPECTED.values().stream().map(Row::decision).filter(Allocation.Refuse.class::isInstance).count(),
      "a second refusing row needs its own words asserted"
    );
  }

  @Test
  @DisplayName("a type the table does not name is left to the caller rather than refused here")
  void anUnnamedTypeIsNotAnswered() {
    // An adopter's own subclass is reachable in ways this table knows nothing about — a public
    // constructor bound at run time, a family default written into source — so answering null is
    // the difference between "no opinion" and "cannot be built".
    assertNull(RULES.allocationFor(MyOwnList.class, Kind.LIST));
  }

  private static final class MyOwnList<E> extends ArrayList<E> {

    private static final long serialVersionUID = 1L;
  }

  @Test
  @DisplayName("a name the table holds for one family is not an answer for another")
  void aNameDoesNotAnswerForTheWrongFamily() {
    // An entry answers for one family only. A table that answered regardless would hand the map
    // family a list, and the cast would fail at the first conversion rather than while the plan was
    // built, where the type and the escape hatch can still be named.
    assertNull(RULES.allocationFor(List.class, Kind.SET));
    assertNull(RULES.allocationFor(List.class, Kind.MAP_VALUES));
    assertNull(RULES.allocationFor(HashSet.class, Kind.LIST));
    assertNull(RULES.allocationFor(SortedMap.class, Kind.LIST));
    assertNull(RULES.allocationFor(Collection.class, Kind.MAP_VALUES));
  }

  /**
   * Stands for a row that refuses. The words it refuses with are pinned by {@link
   * #enumMapCarriesItsRefusal()}, so repeating them here would give two places to edit and no
   * second opinion.
   */
  private static final Allocation REFUSED = new Allocation.Refuse("");

  /**
   * What every declared row is expected to decide, stated where the table is not.
   *
   * <p>A restatement of the table is normally a smell, and here it is the only shape available:
   * deriving the expectation from the table would be circular, and the whole value of the second
   * copy is that it lives where someone editing the first is not looking. So it restates, and it
   * restates completely — a row with no expectation below fails, which is what stops this falling
   * behind the way a hand-picked sample does.
   *
   * <p>It carries the whole decision rather than the constructor alone. The class half moves on its
   * own: a row repointed at a different implementation of the same family builds a container of the
   * wrong type at the right size, which every count and contents assertion agrees with.
   */
  private static final Map<String, Row> EXPECTED = Map.ofEntries(
    Map.entry("java.util.List", list("java.util.ArrayList", Call.COUNT)),
    Map.entry("java.util.ArrayList", list("java.util.ArrayList", Call.COUNT)),
    Map.entry("java.util.LinkedList", list("java.util.LinkedList", Call.NO_ARG)),
    Map.entry("java.util.Deque", list("java.util.ArrayDeque", Call.COUNT)),
    Map.entry("java.util.Queue", list("java.util.ArrayDeque", Call.COUNT)),
    Map.entry("java.util.Vector", list("java.util.Vector", Call.COUNT)),
    Map.entry("java.util.Stack", list("java.util.Stack", Call.NO_ARG)),
    Map.entry("java.util.Set", set("java.util.LinkedHashSet", Call.TABLE_FACTORY)),
    Map.entry("java.util.LinkedHashSet", set("java.util.LinkedHashSet", Call.TABLE_FACTORY)),
    Map.entry("java.util.HashSet", set("java.util.HashSet", Call.TABLE_FACTORY)),
    Map.entry("java.util.TreeSet", set("java.util.TreeSet", Call.ORDERING)),
    Map.entry("java.util.SortedSet", set("java.util.TreeSet", Call.ORDERING)),
    Map.entry("java.util.NavigableSet", set("java.util.TreeSet", Call.ORDERING)),
    Map.entry(
      "java.util.concurrent.ConcurrentSkipListSet",
      set("java.util.concurrent.ConcurrentSkipListSet", Call.ORDERING)
    ),
    Map.entry("java.util.Map", map("java.util.LinkedHashMap", Call.TABLE_FACTORY)),
    Map.entry("java.util.LinkedHashMap", map("java.util.LinkedHashMap", Call.TABLE_FACTORY)),
    Map.entry("java.util.HashMap", map("java.util.HashMap", Call.TABLE_FACTORY)),
    Map.entry("java.util.TreeMap", map("java.util.TreeMap", Call.ORDERING)),
    Map.entry("java.util.SortedMap", map("java.util.TreeMap", Call.ORDERING)),
    Map.entry("java.util.NavigableMap", map("java.util.TreeMap", Call.ORDERING)),
    Map.entry("java.util.concurrent.ConcurrentHashMap", map("java.util.concurrent.ConcurrentHashMap", Call.COUNT)),
    Map.entry("java.util.concurrent.ConcurrentMap", map("java.util.concurrent.ConcurrentHashMap", Call.COUNT)),
    Map.entry(
      "java.util.concurrent.ConcurrentSkipListMap",
      map("java.util.concurrent.ConcurrentSkipListMap", Call.ORDERING)
    ),
    Map.entry("java.util.IdentityHashMap", map("java.util.IdentityHashMap", Call.COUNT)),
    Map.entry("java.util.WeakHashMap", map("java.util.WeakHashMap", Call.TABLE_ARITHMETIC)),
    Map.entry("java.util.EnumMap", new Row(Kind.MAP_VALUES, REFUSED))
  );

  private static Row list(final String implName, final Call call) {
    return new Row(Kind.LIST, new Allocation.Build(implName, call));
  }

  private static Row set(final String implName, final Call call) {
    return new Row(Kind.SET, new Allocation.Build(implName, call));
  }

  private static Row map(final String implName, final Call call) {
    return new Row(Kind.MAP_VALUES, new Allocation.Build(implName, call));
  }

  /**
   * One expected row: the family it answers for, and what it answers. Stating the family is what
   * stops the walk asking with the row's own, which makes that dimension answer itself.
   */
  private record Row(Kind family, Allocation decision) {}

  @Test
  @DisplayName("every row in the table decides the class and the constructor this file says it should")
  void everyRowDecidesTheExpectedAllocation() {
    // A row and its expectation are added and removed together, in both directions: a new row with
    // nothing expected of it, and an expectation whose row is gone. Asserted as the two differences
    // rather than as set equality, because a gate that prints both twenty-eight-name sets leaves
    // the reader to find the one that moved.
    final var rows = PairingRules.declaredTypes().keySet();
    final var decidedByNothing = new TreeSet<>(EXPECTED.keySet());
    decidedByNothing.removeAll(rows);
    final var expectedByNothing = new TreeSet<>(rows);
    expectedByNothing.removeAll(EXPECTED.keySet());
    assertTrue(
      expectedByNothing.isEmpty(),
      () -> expectedByNothing + " is decided by the table and expected by nothing"
    );
    assertTrue(decidedByNothing.isEmpty(), () -> decidedByNothing + " is expected here and decided by no row");

    // The unit that matters is the implementation, not the declared name: moving one to a
    // different constructor means relabelling every row that reaches it, so a sample of rows
    // leaves whichever implementations the sample missed unguarded. Walking the table is what
    // makes a new row a red test until someone says what it should decide.
    for (final var entry : PairingRules.declaredTypes().entrySet()) {
      final var declared = entry.getKey();
      // Reported here rather than left to the differences above, so the two assertions stay
      // independent of each other and a row with no expectation names itself instead of arriving as
      // a null dereference.
      final var row = EXPECTED.get(declared);
      assertNotNull(row, () -> declared + " is decided by the table and expected by nothing");

      // Asserted before the decision is asked for, because the question carries the family: asking
      // with the row's own answers that dimension with itself. A row in the wrong family builds an
      // allocator that throws at conversion time, which no count or contents assertion reaches.
      assertEquals(row.family(), entry.getValue(), () -> declared + " answers for a different family than expected");

      final var decision = RULES.allocationFor(classFor(declared), entry.getValue());
      if (!(row.decision() instanceof Allocation.Build expected)) {
        assertInstanceOf(Allocation.Refuse.class, decision, () -> declared + " should carry a refusal");
        continue;
      }
      final var build = assertInstanceOf(Allocation.Build.class, decision, () -> declared + " should be buildable");
      assertEquals(
        expected.implName(),
        build.implName(),
        () -> declared + " is rebuilt as a different class than expected"
      );
      assertEquals(
        expected.call(),
        build.call(),
        () -> declared + " is built by a different constructor than expected"
      );
    }
  }

  private static Class<?> classFor(final String name) {
    try {
      return Class.forName(name);
    } catch (final ClassNotFoundException e) {
      throw new IllegalStateException("the table names a class that is not on the classpath: " + name, e);
    }
  }
}
