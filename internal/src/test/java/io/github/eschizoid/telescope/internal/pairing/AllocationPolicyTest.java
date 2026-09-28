package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
 * <p>The sizing is a separate answer from the implementation because the two renderings need it
 * separately: the same "size it from the source" means an element count to a list and a table
 * capacity to a hash container, which for the same elements is a different number.
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
   * What every declared row is expected to decide, stated where the table is not.
   *
   * <p>A restatement of the table is normally a smell, and here it is the only shape available:
   * deriving the expectation from the table would be circular, and the whole value of the second
   * copy is that it lives where someone editing the first is not looking. So it restates, and it
   * restates completely — a row with no expectation below fails, which is what stops this falling
   * behind the way a hand-picked sample does.
   */
  private static final Map<String, Call> EXPECTED = Map.ofEntries(
    Map.entry("java.util.List", Call.COUNT),
    Map.entry("java.util.ArrayList", Call.COUNT),
    Map.entry("java.util.LinkedList", Call.NO_ARG),
    Map.entry("java.util.Deque", Call.COUNT),
    Map.entry("java.util.Queue", Call.COUNT),
    Map.entry("java.util.Vector", Call.COUNT),
    Map.entry("java.util.Stack", Call.NO_ARG),
    Map.entry("java.util.PriorityQueue", Call.NO_ARG),
    Map.entry("java.util.concurrent.LinkedBlockingQueue", Call.NO_ARG),
    Map.entry("java.util.Set", Call.TABLE_FACTORY),
    Map.entry("java.util.LinkedHashSet", Call.TABLE_FACTORY),
    Map.entry("java.util.HashSet", Call.TABLE_FACTORY),
    Map.entry("java.util.TreeSet", Call.ORDERING),
    Map.entry("java.util.SortedSet", Call.ORDERING),
    Map.entry("java.util.NavigableSet", Call.ORDERING),
    Map.entry("java.util.concurrent.ConcurrentSkipListSet", Call.ORDERING),
    Map.entry("java.util.Map", Call.TABLE_FACTORY),
    Map.entry("java.util.LinkedHashMap", Call.TABLE_FACTORY),
    Map.entry("java.util.HashMap", Call.TABLE_FACTORY),
    Map.entry("java.util.TreeMap", Call.ORDERING),
    Map.entry("java.util.SortedMap", Call.ORDERING),
    Map.entry("java.util.NavigableMap", Call.ORDERING),
    Map.entry("java.util.concurrent.ConcurrentHashMap", Call.COUNT),
    Map.entry("java.util.concurrent.ConcurrentMap", Call.COUNT),
    Map.entry("java.util.concurrent.ConcurrentSkipListMap", Call.ORDERING),
    Map.entry("java.util.IdentityHashMap", Call.COUNT),
    Map.entry("java.util.WeakHashMap", Call.TABLE_ARITHMETIC)
  );

  /** Rows that decide a refusal rather than a build, so they have no constructor to expect. */
  private static final Set<String> EXPECTED_REFUSALS = Set.of("java.util.EnumMap");

  @Test
  @DisplayName("every row in the table decides the constructor this file says it should")
  void everyRowDecidesTheExpectedCall() {
    // The unit that matters is the implementation, not the declared name: moving one to a
    // different constructor means relabelling every row that reaches it, so a sample of rows
    // leaves whichever implementations the sample missed unguarded. Walking the table is what
    // makes a new row a red test until someone says what it should decide.
    for (final var entry : PairingRules.declaredTypes().entrySet()) {
      final var declared = entry.getKey();
      assertTrue(
        EXPECTED.containsKey(declared) || EXPECTED_REFUSALS.contains(declared),
        () -> declared + " is decided by the table and expected by nothing"
      );

      final var decision = RULES.allocationFor(classFor(declared), entry.getValue());
      if (EXPECTED_REFUSALS.contains(declared)) {
        assertInstanceOf(Allocation.Refuse.class, decision, () -> declared + " should carry a refusal");
        continue;
      }
      final var expected = EXPECTED.get(declared);
      final var build = assertInstanceOf(Allocation.Build.class, decision, () -> declared + " should be buildable");
      assertEquals(expected, build.call(), () -> declared + " is built by a different constructor than expected");
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
