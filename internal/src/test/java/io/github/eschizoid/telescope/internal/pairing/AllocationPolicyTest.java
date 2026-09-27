package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.ContainerView.Kind;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.SortedMap;
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
      Arguments.of(List.class, Kind.LIST, "java.util.ArrayList"),
      Arguments.of(ArrayList.class, Kind.LIST, "java.util.ArrayList"),
      // A deque's int argument is an element count, unlike the hash families below.
      Arguments.of(Deque.class, Kind.LIST, "java.util.ArrayDeque"),
      Arguments.of(HashSet.class, Kind.SET, "java.util.HashSet"),
      Arguments.of(SortedMap.class, Kind.MAP_VALUES, "java.util.TreeMap")
    );
  }

  @ParameterizedTest(name = "{0} builds {2}")
  @MethodSource("rows")
  @DisplayName("a declared container names the one implementation it is rebuilt as")
  void declaredTypeDecidesItsImplementation(final Class<?> declared, final Kind kind, final String impl) {
    final var build = assertInstanceOf(Allocation.Build.class, RULES.allocationFor(declared, kind));

    assertEquals(impl, build.implName());
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
  @DisplayName("a type with no constructor to reach carries the sentence to refuse it with")
  void enumMapCarriesItsRefusal() {
    final var refuse = assertInstanceOf(Allocation.Refuse.class, RULES.allocationFor(EnumMap.class, Kind.MAP_VALUES));

    assertTrue(refuse.reason().contains("Class<K>"), refuse::reason);
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
    // Before one table served all three, each family held only its own names and a foreign one was
    // refused while the plan was built. A single table that answers regardless would hand the map
    // family a list and let the cast fail at the first conversion instead.
    assertNull(RULES.allocationFor(List.class, Kind.SET));
    assertNull(RULES.allocationFor(List.class, Kind.MAP_VALUES));
    assertNull(RULES.allocationFor(HashSet.class, Kind.LIST));
    assertNull(RULES.allocationFor(SortedMap.class, Kind.LIST));
    assertNull(RULES.allocationFor(Collection.class, Kind.MAP_VALUES));
  }
}
