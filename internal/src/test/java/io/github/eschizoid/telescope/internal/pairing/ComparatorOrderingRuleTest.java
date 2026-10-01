package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shared rule for whether a sorted container's comparator constructor can be handed a
 * comparator over what the field orders, and the reflection primitives it reads.
 */
class ComparatorOrderingRuleTest {

  private final ReflectionProps props = new ReflectionProps();

  private final PairingRules<Type> rules = new PairingRules<>(props);

  @SuppressWarnings({ "rawtypes", "unused" })
  static class Parameters<E, K, V> {

    Comparator raw;
    Comparator<? super String> superString;
    Comparator<? extends CharSequence> extendsCharSequence;
    Comparator<?> unbounded;
    Comparator<String> exactString;
    Comparator<CharSequence> charSequence;
    Comparator<Object> object;
    Comparator<LocalDate> unrelated;
    Comparator<? super Integer> superInteger;
    Comparator<? super E> superE;
    Comparator<Map.Entry<K, V>> entries;
    List<? super String> listOfSuperString;
    List<CharSequence> listOfCharSequence;
    List<Integer> listOfInteger;
    List<? super CharSequence> listOfSuperCharSequence;
  }

  /** Declares its type parameters in the opposite order to {@link TreeMap}'s. */
  @SuppressWarnings("unused")
  static class Swapped<V, K> extends TreeMap<K, V> {

    private static final long serialVersionUID = 1L;

    Swapped(final Comparator<? super K> order) {
      super(order);
    }
  }

  private static Type field(final String name) throws NoSuchFieldException {
    return Parameters.class.getDeclaredField(name).getGenericType();
  }

  @Test
  @DisplayName("a parameter over a supertype of what the field orders accepts its comparator")
  void aSupertypeParameterAccepts() throws NoSuchFieldException {
    for (final var name : List.of(
      "raw",
      "superString",
      "extendsCharSequence",
      "unbounded",
      "exactString",
      "charSequence",
      "object"
    )) {
      assertTrue(rules.canOrder(field(name), String.class), name);
    }
  }

  @Test
  @DisplayName("a parameter over an unrelated type, or a lower bound the ordered type misses, refuses")
  void anUnrelatedParameterRefuses() throws NoSuchFieldException {
    for (final var name : List.of("unrelated", "superInteger", "entries")) {
      assertFalse(rules.canOrder(field(name), String.class), name);
    }
  }

  @Test
  @DisplayName("a parameter over a class's type variable answers once resolved against the field's arguments")
  void aResolvedVariableAnswersForTheField() throws NoSuchFieldException {
    final var resolved = props.resolve(
      field("superE"),
      Parameters.class,
      List.of(String.class, String.class, Integer.class)
    );
    assertTrue(rules.canOrder(resolved, String.class));
    assertFalse(rules.canOrder(resolved, Integer.class));
  }

  @Test
  @DisplayName("resolution follows the class's own parameter order, not its supertype's")
  void resolutionFollowsTheClassesOwnOrder() {
    final var ctor = Swapped.class.getDeclaredConstructors()[0];
    final var param = props.resolve(
      ctor.getGenericParameterTypes()[0],
      Swapped.class,
      List.of(Integer.class, String.class)
    );
    assertTrue(rules.canOrder(param, String.class), "K is the second argument, String");
    assertFalse(rules.canOrder(param, Integer.class));
  }

  @Test
  @DisplayName("substitution reaches through a wildcard's bounds and keeps JDK wildcard equality")
  void substitutionReachesWildcardBounds() throws NoSuchFieldException {
    final var resolved = (ParameterizedType) props.resolve(
      field("superE"),
      Parameters.class,
      List.of(String.class, String.class, String.class)
    );
    final var wildcard = (WildcardType) resolved.getActualTypeArguments()[0];
    assertEquals(String.class, wildcard.getLowerBounds()[0]);
    final var jdk = ((ParameterizedType) field("superString")).getActualTypeArguments()[0];
    assertEquals(jdk, wildcard);
    assertEquals(wildcard, jdk);
    assertEquals(jdk.hashCode(), wildcard.hashCode());
    assertEquals("? super java.lang.String", wildcard.getTypeName());
  }

  @Test
  @DisplayName("wildcard primitives answer for wildcards and only for them")
  void wildcardPrimitives() throws NoSuchFieldException {
    final var superString = ((ParameterizedType) field("superString")).getActualTypeArguments()[0];
    final var unbounded = ((ParameterizedType) field("unbounded")).getActualTypeArguments()[0];
    assertTrue(props.isWildcard(superString));
    assertFalse(props.isWildcard(String.class));
    assertEquals(String.class, props.lowerBound(superString));
    assertNull(props.lowerBound(unbounded));
    assertNull(props.lowerBound(String.class));
  }

  @Test
  @DisplayName("a lower-bounded wildcard argument contains its bound and the bound's supertypes only")
  void lowerBoundContainment() throws NoSuchFieldException {
    final var target = field("listOfSuperString");
    assertTrue(props.isAssignable(List.class, target), "raw use is the unchecked conversion");
    assertTrue(props.isAssignable(field("listOfSuperString"), target));
    assertTrue(props.isAssignable(field("listOfCharSequence"), target), "CharSequence is a supertype of String");
    assertFalse(props.isAssignable(field("listOfInteger"), target));
    assertTrue(props.isAssignable(field("listOfSuperCharSequence"), target), "a wider lower bound is contained");
    assertFalse(props.isAssignable(target, field("listOfSuperCharSequence")), "a narrower one is not");
  }
}
