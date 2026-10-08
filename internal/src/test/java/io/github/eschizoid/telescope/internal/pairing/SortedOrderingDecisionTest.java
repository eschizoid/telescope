package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.Serial;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * How a rebuilt sorted container is told the order its source kept, decided by {@link
 * PairingRules#orderingFor} over the reflection world's primitives.
 */
class SortedOrderingDecisionTest {

  private final ReflectionProps props = new ReflectionProps();

  private final PairingRules<Type> rules = new PairingRules<>(props);

  /** A sorted set subtype with a comparator constructor over a supertype of its elements. */
  public static class Ordered<E> extends TreeSet<E> {

    @Serial
    private static final long serialVersionUID = 1L;

    public Ordered() {}

    public Ordered(final Comparator<? super E> order) {
      super(order);
    }
  }

  /** A sorted set subtype that declares no constructor taking a comparator. */
  public static class Unordered<E> extends TreeSet<E> {

    @Serial
    private static final long serialVersionUID = 1L;

    public Unordered() {}
  }

  /** A sorted set subtype whose comparator constructor orders a type unrelated to its elements. */
  public static class Unrelated<E> extends TreeSet<E> {

    @Serial
    private static final long serialVersionUID = 1L;

    public Unrelated() {}

    @SuppressWarnings("unused")
    public Unrelated(final Comparator<LocalDate> order) {}
  }

  /** A sorted set subtype whose comparator constructor is not public. */
  public static class Hidden<E> extends TreeSet<E> {

    @Serial
    private static final long serialVersionUID = 1L;

    public Hidden() {}

    protected Hidden(final Comparator<? super E> order) {
      super(order);
    }
  }

  /** A sorted set subtype with a public comparator constructor on a class that is not public. */
  static class Secluded<E> extends TreeSet<E> {

    @Serial
    private static final long serialVersionUID = 1L;

    public Secluded() {}

    public Secluded(final Comparator<? super E> order) {
      super(order);
    }
  }

  /** A sorted map subtype declaring its type parameters value first, the opposite of TreeMap. */
  public static class Swapped<V, K> extends TreeMap<K, V> {

    @Serial
    private static final long serialVersionUID = 1L;

    public Swapped() {}

    public Swapped(final Comparator<? super K> order) {
      super(order);
    }
  }

  /** A sorted set subtype taking one more type parameter than Set does. */
  public static class Tagged<T, E> extends TreeSet<E> {

    @Serial
    private static final long serialVersionUID = 1L;

    public Tagged() {}

    public Tagged(final Comparator<? super E> order) {
      super(order);
    }
  }

  @SuppressWarnings({ "rawtypes", "unused" })
  static class Fields {

    List<String> strings;
    Set<String> plainStrings;
    SortedSet<String> sortedStrings;
    SortedMap<String, Integer> sortedMap;
    Set rawSet;
    Ordered rawOrdered;
    Swapped rawSwapped;
    Ordered<String> ordered;
    Unordered<String> unordered;
    Unrelated<String> unrelated;
    Hidden<String> hidden;
    Secluded<String> secluded;
    Swapped<Integer, String> swapped;
    Tagged<Integer, String> tagged;
    Comparator<? super String> superString;
  }

  private static Type field(final String name) throws NoSuchFieldException {
    return Fields.class.getDeclaredField(name).getGenericType();
  }

  private static Type comparatorConstructorParameter(final Class<?> container) throws NoSuchMethodException {
    return container.getConstructor(Comparator.class).getGenericParameterTypes()[0];
  }

  private static Ordering.Refuse<Type> refused(final Ordering<Type> ordering) {
    @SuppressWarnings("unchecked")
    final Ordering.Refuse<Type> refuse = assertInstanceOf(Ordering.Refuse.class, ordering);
    return refuse;
  }

  private static Ordering.Carry<Type> carried(final Ordering<Type> ordering) {
    @SuppressWarnings("unchecked")
    final Ordering.Carry<Type> carry = assertInstanceOf(Ordering.Carry.class, ordering);
    return carry;
  }

  @Nested
  @DisplayName("orderingFor")
  class OrderingFor {

    @Test
    @DisplayName("a container built without an order has nothing to be told")
    void anUnorderedContainerTakesNone() throws NoSuchFieldException {
      assertInstanceOf(
        Ordering.None.class,
        rules.orderingFor(field("strings"), List.class, ContainerView.Kind.LIST, true)
      );
      assertInstanceOf(
        Ordering.None.class,
        rules.orderingFor(field("plainStrings"), LinkedHashSet.class, ContainerView.Kind.SET, true)
      );
    }

    @Test
    @DisplayName("a sorted set whose elements are converted refuses a comparator in the conversion's words")
    void aConvertedSortedSetRefuses() throws NoSuchFieldException {
      final var refuse = refused(
        rules.orderingFor(field("sortedStrings"), TreeSet.class, ContainerView.Kind.SET, false)
      );
      assertEquals(PairingMessages.comparatorAcrossConversion(), refuse.reason());
    }

    @Test
    @DisplayName("a sorted map's comparator is carried even when its values are converted")
    void aSortedMapCarriesWhateverItsValuesBecome() throws NoSuchFieldException {
      final var carry = carried(
        rules.orderingFor(field("sortedMap"), TreeMap.class, ContainerView.Kind.MAP_VALUES, false)
      );
      assertEquals(field("superString"), carry.parameter());
    }

    @Test
    @DisplayName("a family default carries a comparator resolved against the declared container's arguments")
    void aFamilyDefaultResolvesAgainstTheContainer() throws NoSuchFieldException {
      final var carry = carried(rules.orderingFor(field("sortedStrings"), TreeSet.class, ContainerView.Kind.SET, true));
      assertEquals(field("superString"), carry.parameter());
    }

    @Test
    @DisplayName("a subtype built as declared resolves its comparator against its own parameter order")
    void aDeclaredSubtypeResolvesInItsOwnOrder() throws NoSuchFieldException {
      final var carry = carried(
        rules.orderingFor(field("swapped"), Swapped.class, ContainerView.Kind.MAP_VALUES, true)
      );
      assertEquals(field("superString"), carry.parameter(), "K is Swapped's second argument, String");
    }

    @Test
    @DisplayName("a subtype with more parameters than its family resolves only when built as declared")
    void anExtraParameterResolvesOnlyAsDeclared() throws NoSuchFieldException {
      assertEquals(
        field("superString"),
        carried(rules.orderingFor(field("tagged"), Tagged.class, ContainerView.Kind.SET, true)).parameter()
      );
      assertEquals(
        PairingMessages.noComparatorConstructor(Tagged.class.getCanonicalName()),
        refused(rules.orderingFor(field("sortedStrings"), Tagged.class, ContainerView.Kind.SET, true)).reason()
      );
    }

    @Test
    @DisplayName("a subtype that cannot be handed the comparator is refused, naming it as source spells it")
    void aSubtypeWithNoUsableConstructorIsRefusedByName() throws NoSuchFieldException {
      assertRefusedByName("unordered", Unordered.class);
      assertRefusedByName("unrelated", Unrelated.class);
      assertRefusedByName("hidden", Hidden.class);
      assertRefusedByName("secluded", Secluded.class);
    }

    private void assertRefusedByName(final String declared, final Class<?> impl) throws NoSuchFieldException {
      final var refuse = refused(rules.orderingFor(field(declared), impl, ContainerView.Kind.SET, true));
      assertEquals(PairingMessages.noComparatorConstructor(impl.getCanonicalName()), refuse.reason(), declared);
    }

    @Test
    @DisplayName("a subtype used raw carries a comparator over its own type variables")
    void aRawSubtypeCarriesOverItsOwnVariables() throws ReflectiveOperationException {
      assertEquals(
        comparatorConstructorParameter(Ordered.class),
        carried(rules.orderingFor(field("rawOrdered"), Ordered.class, ContainerView.Kind.SET, true)).parameter()
      );
      assertEquals(
        comparatorConstructorParameter(Swapped.class),
        carried(rules.orderingFor(field("rawSwapped"), Swapped.class, ContainerView.Kind.MAP_VALUES, true)).parameter(),
        "read over Swapped's own K, not the container's arguments paired off with V first"
      );
    }

    @Test
    @DisplayName("a raw Set names nothing to order, and a sorted rebuild of it is refused")
    void aRawSetIsRefused() throws NoSuchFieldException {
      final var refuse = refused(rules.orderingFor(field("rawSet"), TreeSet.class, ContainerView.Kind.SET, true));
      assertEquals(PairingMessages.noComparatorConstructor("java.util.TreeSet"), refuse.reason());
    }
  }

  @Nested
  @DisplayName("ReflectionProps ordering primitives")
  class Primitives {

    @Test
    @DisplayName("the comparator parameter is the public constructor's, with the class's variables substituted")
    void theComparatorParameterIsSubstituted() throws NoSuchFieldException {
      assertEquals(field("superString"), props.comparatorParameter(Ordered.class, List.of(String.class)));
      assertEquals(field("superString"), props.comparatorParameter(field("ordered"), List.of(String.class)));
      assertEquals(
        field("superString"),
        props.comparatorParameter(Swapped.class, List.of(Integer.class, String.class)),
        "arguments pair off with the class's own parameters, value first"
      );
    }

    @Test
    @DisplayName("there is no comparator parameter where this world cannot call the constructor or pair the arguments")
    void noComparatorParameterWhereNoneCanBeCalled() {
      assertNull(props.comparatorParameter(Unordered.class, List.of(String.class)), "no such constructor");
      assertNull(props.comparatorParameter(Hidden.class, List.of(String.class)), "a constructor that is not public");
      assertNull(props.comparatorParameter(Secluded.class, List.of(String.class)), "a class that is not public");
      assertNull(props.comparatorParameter(Tagged.class, List.of(String.class)), "one argument for two parameters");
      assertNull(props.comparatorParameter(Ordered.class.getTypeParameters()[0], List.of()), "not a class");
    }

    @Test
    @DisplayName("no arguments leave the comparator parameter over the class's own type variables")
    void noArgumentsLeaveTheParameterAsDeclared() throws NoSuchMethodException {
      assertEquals(comparatorConstructorParameter(Swapped.class), props.comparatorParameter(Swapped.class, List.of()));
    }

    @Test
    @DisplayName("a class's source name joins a nested class to its enclosing ones by dots")
    void aSourceNameIsDotted() throws NoSuchFieldException {
      final var expected = SortedOrderingDecisionTest.class.getName() + ".Ordered";
      assertEquals(expected, props.sourceName(Ordered.class));
      assertEquals(expected, props.sourceName(field("ordered")));
    }

    @Test
    @DisplayName("a class with no source name, and a type that is not a class, fall back to the type name")
    void aSourceNameFallsBack() {
      final class Local {}
      assertEquals(Local.class.getName(), props.sourceName(Local.class));
      final var variable = Ordered.class.getTypeParameters()[0];
      assertEquals("E", props.sourceName(variable));
    }
  }
}
