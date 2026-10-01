package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The shared rule for a sorted set target whose converted elements nothing can order. */
class UnorderableSortedTargetTest {

  private final PairingRules<Type> rules = new PairingRules<>(new ReflectionProps());

  record Ordered(String v) implements Comparable<Ordered> {
    @Override
    public int compareTo(final Ordered other) {
      return v.compareTo(other.v());
    }
  }

  record Unordered(String v) {}

  record OrderedDto(String v) implements Comparable<OrderedDto> {
    @Override
    public int compareTo(final OrderedDto other) {
      return v.compareTo(other.v());
    }
  }

  interface Shape {}

  @SuppressWarnings("unused")
  static class Fields {

    SortedSet<Ordered> sortedOrdered;
    SortedSet<Unordered> sortedUnordered;
    SortedSet<OrderedDto> sortedOrderedDto;
    TreeSet<Unordered> treeUnordered;
    Set<Unordered> plainUnordered;
    Collection<Ordered> collectionOrdered;
    SortedSet<Shape> sortedShape;
    SortedSet<String> sortedString;
    SortedSet<Object> sortedObject;
    SortedSet<? extends Unordered> sortedWildcard;
  }

  private static Type field(final String name) throws NoSuchFieldException {
    return Fields.class.getDeclaredField(name).getGenericType();
  }

  @Test
  @DisplayName("a sorted set target of a converted element that is not Comparable is refused by decidePair")
  void anUnorderableTargetIsRefused() throws NoSuchFieldException {
    for (final var target : List.of("sortedUnordered", "treeUnordered")) {
      final var decision = rules.decidePair(field("sortedOrdered"), field(target), "items");
      final var refusal = assertInstanceOf(PairDecision.Incompatible.class, decision, target);
      assertTrue(refusal.message().contains("does not implement Comparable"), refusal::message);
      assertTrue(refusal.message().contains(Unordered.class.getName()), refusal::message);
    }
    assertInstanceOf(
      PairDecision.Incompatible.class,
      rules.decidePair(field("collectionOrdered"), field("sortedUnordered"), "items")
    );
  }

  @Test
  @DisplayName("a Comparable element, an unsorted target, or an unchanged element is lifted as before")
  void orderableOrUnsortedTargetsLift() throws NoSuchFieldException {
    assertInstanceOf(
      PairDecision.LiftContainer.class,
      rules.decidePair(field("sortedOrdered"), field("sortedOrderedDto"), "items")
    );
    assertInstanceOf(
      PairDecision.LiftContainer.class,
      rules.decidePair(field("sortedOrdered"), field("plainUnordered"), "items")
    );
  }

  @Test
  @DisplayName("an interface or wildcard element, or the same element on both sides, is not refused by the rule")
  void theRuleLetsThroughWhatItCannotDecide() throws NoSuchFieldException {
    assertFalse(rules.unorderableSortedTarget(Ordered.class, Shape.class, field("sortedShape")));
    assertFalse(rules.unorderableSortedTarget(Unordered.class, Unordered.class, field("sortedUnordered")));
    assertFalse(rules.unorderableSortedTarget(Ordered.class, String.class, field("sortedString")));
    assertFalse(
      rules.unorderableSortedTarget(
        Ordered.class,
        ((ParameterizedType) field("sortedWildcard")).getActualTypeArguments()[0],
        field("sortedWildcard")
      )
    );
    assertFalse(rules.unorderableSortedTarget(Ordered.class, Unordered.class, field("plainUnordered")));
    assertTrue(rules.unorderableSortedTarget(Ordered.class, Unordered.class, field("sortedUnordered")));
  }

  @Test
  @DisplayName("a pairing whose elements cannot pair is left to the element's own refusal")
  void anotherRefusalKeepsItsMessage() throws NoSuchFieldException {
    // String cannot become Object, so the element pair is what refuses, one level down; the
    // container is lifted here rather than refused for an ordering the elements never reach.
    assertInstanceOf(PairDecision.Incompatible.class, rules.decidePair(String.class, Object.class, "items"));
    assertInstanceOf(
      PairDecision.LiftContainer.class,
      rules.decidePair(field("sortedString"), field("sortedObject"), "items")
    );
  }
}
