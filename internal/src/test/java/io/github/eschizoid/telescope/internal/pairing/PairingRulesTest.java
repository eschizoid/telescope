package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.lang.reflect.Type;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Direct contract tests for {@link PairingRules} — the shared pairing decision spec that both the
 * runtime mapper construction and the compile-time verifier delegate to. Pins the {@code
 * decidePair} branch ordering (the decision lattice), the container-view selection rules, the
 * reflectability exclusions, the same-kind discriminator axes, and the same-name field matching —
 * all through the reflection world's {@link ReflectionProps}, independently of either consumer.
 */
class PairingRulesTest {

  record Point(int x, int y) {}

  record PointDto(int x, int y) {}

  static final class AddressBean {

    private String city;

    public String getCity() {
      return city;
    }

    public void setCity(final String city) {
      this.city = city;
    }
  }

  /** Raw container subclass — the shape the subtype-copy branch exists to intercept. */
  public static class ImageUrls extends ArrayList<String> {

    @Serial
    private static final long serialVersionUID = 1L;
  }

  public static class ImageUrlsDto extends ArrayList<String> {

    @Serial
    private static final long serialVersionUID = 1L;
  }

  public static class Attrs extends HashMap<String, String> {

    @Serial
    private static final long serialVersionUID = 1L;
  }

  public static class AttrsDto extends HashMap<String, String> {

    @Serial
    private static final long serialVersionUID = 1L;
  }

  /** Field declarations whose reflected generic types supply parameterized handles. */
  @SuppressWarnings("unused")
  static final class TypeHolder {

    List<String> listOfString;
    List<Integer> listOfInteger;
    Set<String> setOfString;
    Optional<String> optionalOfString;
    Optional<Integer> optionalOfInteger;
    Map<String, Integer> mapStringToInteger;
    Map<String, String> mapStringToString;
    Map<Integer, String> mapIntegerToString;
    Map<?, String> mapWildcardToString;
  }

  private static Type typeOf(final String fieldName) {
    try {
      return TypeHolder.class.getDeclaredField(fieldName).getGenericType();
    } catch (final NoSuchFieldException e) {
      throw new IllegalStateException(e);
    }
  }

  private final PairingRules<Type> rules = new PairingRules<>(new ReflectionProps());

  @Nested
  @DisplayName("decidePair — the decision lattice, branch by branch")
  class DecidePair {

    @Test
    @DisplayName("same type on both sides decides Identity, for scalars and parameterized types alike")
    void sameTypeIsIdentity() {
      assertInstanceOf(PairDecision.Identity.class, rules.decidePair(String.class, String.class, "f"));
      assertInstanceOf(
        PairDecision.Identity.class,
        rules.decidePair(typeOf("listOfString"), typeOf("listOfString"), "f")
      );
    }

    @Test
    @DisplayName("primitive vs its own wrapper decides PrimitiveWrapper in both directions")
    void primitiveWrapperBothDirections() {
      assertInstanceOf(PairDecision.PrimitiveWrapper.class, rules.decidePair(int.class, Integer.class, "f"));
      assertInstanceOf(PairDecision.PrimitiveWrapper.class, rules.decidePair(Integer.class, int.class, "f"));
    }

    @Test
    @DisplayName("primitive vs a different scalar wrapper is Incompatible with the shape diagnostic")
    void primitiveVsForeignWrapperIsIncompatible() {
      final var decision = rules.decidePair(int.class, Long.class, "count");
      final var incompatible = assertInstanceOf(PairDecision.Incompatible.class, decision);
      assertEquals(PairingMessages.incompatibleShapes("count", "int", "java.lang.Long"), incompatible.message());
    }

    @Test
    @DisplayName("raw same-kind container subclasses decide CollectionCopy before reflectable recursion can claim them")
    void collectionCopyPrecedesRecursion() {
      assertInstanceOf(PairDecision.CollectionCopy.class, rules.decidePair(ImageUrls.class, ImageUrlsDto.class, "f"));
    }

    @Test
    @DisplayName("raw same-kind Map subclasses decide MapCopy before reflectable recursion can claim them")
    void mapCopyPrecedesRecursion() {
      assertInstanceOf(PairDecision.MapCopy.class, rules.decidePair(Attrs.class, AttrsDto.class, "f"));
    }

    @Test
    @DisplayName("two distinct records decide RecursePair")
    void recordPairRecurses() {
      assertInstanceOf(PairDecision.RecursePair.class, rules.decidePair(Point.class, PointDto.class, "f"));
    }

    @Test
    @DisplayName("a record vs a bean decides RecursePair — reflectability spans both rebuild paradigms")
    void recordVsBeanRecurses() {
      assertInstanceOf(PairDecision.RecursePair.class, rules.decidePair(Point.class, AddressBean.class, "f"));
    }

    @Test
    @DisplayName("Optional<X> source vs plain target decides OptionalToNullable carrying the element pair")
    void optionalSourceBridgesToNullable() {
      final var decision = rules.decidePair(typeOf("optionalOfString"), String.class, "f");
      final var bridge = assertInstanceOf(PairDecision.OptionalToNullable.class, decision);
      assertEquals(String.class, bridge.elementSrc());
      assertEquals(String.class, bridge.elementTgt());
    }

    @Test
    @DisplayName("plain source vs Optional<Y> target decides NullableToOptional carrying the element pair")
    void nullableSourceBridgesToOptional() {
      final var decision = rules.decidePair(String.class, typeOf("optionalOfString"), "f");
      final var bridge = assertInstanceOf(PairDecision.NullableToOptional.class, decision);
      assertEquals(String.class, bridge.elementSrc());
      assertEquals(String.class, bridge.elementTgt());
    }

    @Test
    @DisplayName("Optional<X> vs Optional<Y> is a same-kind lift, not a cross-Optional bridge")
    void optionalOnBothSidesLifts() {
      final var decision = rules.decidePair(typeOf("optionalOfString"), typeOf("optionalOfInteger"), "f");
      final var lift = assertInstanceOf(PairDecision.LiftContainer.class, decision);
      assertEquals(ContainerView.Kind.OPTIONAL, lift.src().kind());
      assertEquals(String.class, lift.src().elementType());
      assertEquals(Integer.class, lift.tgt().elementType());
    }

    @Test
    @DisplayName("List<X> vs List<Y> decides LiftContainer carrying both views")
    void sameKindListsLift() {
      final var decision = rules.decidePair(typeOf("listOfString"), typeOf("listOfInteger"), "f");
      final var lift = assertInstanceOf(PairDecision.LiftContainer.class, decision);
      assertEquals(ContainerView.Kind.LIST, lift.src().kind());
      assertEquals(String.class, lift.src().elementType());
      assertEquals(Integer.class, lift.tgt().elementType());
    }

    @Test
    @DisplayName("Map value lift with identical keys decides LiftContainer preserving the key type")
    void mapValueLiftWithMatchingKeys() {
      final var decision = rules.decidePair(typeOf("mapStringToInteger"), typeOf("mapStringToString"), "f");
      final var lift = assertInstanceOf(PairDecision.LiftContainer.class, decision);
      assertEquals(ContainerView.Kind.MAP_VALUES, lift.src().kind());
      assertEquals(String.class, lift.src().keyType());
      assertEquals(Integer.class, lift.src().elementType());
      assertEquals(String.class, lift.tgt().elementType());
    }

    @Test
    @DisplayName("Map pairs with differing key types are Incompatible with the map-key diagnostic verbatim")
    void mapKeyMismatchIsIncompatible() {
      final var decision = rules.decidePair(typeOf("mapStringToInteger"), typeOf("mapIntegerToString"), "attrs");
      final var incompatible = assertInstanceOf(PairDecision.Incompatible.class, decision);
      assertEquals(
        PairingMessages.incompatibleMapKeys("attrs", "java.lang.String", "java.lang.Integer"),
        incompatible.message()
      );
    }

    @Test
    @DisplayName("List<X> vs Set<X> is Incompatible — container kinds never cross-lift")
    void crossKindContainersAreIncompatible() {
      final var decision = rules.decidePair(typeOf("listOfString"), typeOf("setOfString"), "tags");
      assertInstanceOf(PairDecision.Incompatible.class, decision);
    }

    @Test
    @DisplayName("two unrelated scalars are Incompatible with the shape diagnostic verbatim")
    void unrelatedScalarsAreIncompatible() {
      final var decision = rules.decidePair(String.class, Integer.class, "sorId");
      final var incompatible = assertInstanceOf(PairDecision.Incompatible.class, decision);
      assertEquals(
        PairingMessages.incompatibleShapes("sorId", "java.lang.String", "java.lang.Integer"),
        incompatible.message()
      );
    }
  }

  @Nested
  @DisplayName("containerViewOf — container-view selection rules")
  class ContainerViews {

    @Test
    @DisplayName("List<E> yields a LIST view with the element type and the raw handle")
    void listView() {
      final var view = rules.containerViewOf(typeOf("listOfString"));
      assertEquals(ContainerView.Kind.LIST, view.kind());
      assertEquals(String.class, view.elementType());
      assertNull(view.keyType());
      assertEquals(List.class, view.rawType());
    }

    @Test
    @DisplayName("Set<E> yields a SET view")
    void setView() {
      final var view = rules.containerViewOf(typeOf("setOfString"));
      assertEquals(ContainerView.Kind.SET, view.kind());
      assertEquals(String.class, view.elementType());
    }

    @Test
    @DisplayName("Optional<E> yields an OPTIONAL view")
    void optionalView() {
      final var view = rules.containerViewOf(typeOf("optionalOfString"));
      assertEquals(ContainerView.Kind.OPTIONAL, view.kind());
      assertEquals(String.class, view.elementType());
    }

    @Test
    @DisplayName("Map<K, V> yields a MAP_VALUES view carrying both the key and the value type")
    void mapView() {
      final var view = rules.containerViewOf(typeOf("mapStringToInteger"));
      assertEquals(ContainerView.Kind.MAP_VALUES, view.kind());
      assertEquals(String.class, view.keyType());
      assertEquals(Integer.class, view.elementType());
      assertEquals(Map.class, view.rawType());
    }

    @Test
    @DisplayName("a Map whose key argument is not a plain class (wildcard) is not a liftable container")
    void wildcardKeyedMapIsNotLiftable() {
      assertNull(rules.containerViewOf(typeOf("mapWildcardToString")));
    }

    @Test
    @DisplayName("scalars and raw (non-parameterized) containers present no container view")
    void nonParameterizedTypesHaveNoView() {
      assertNull(rules.containerViewOf(String.class));
      assertNull(rules.containerViewOf(ArrayList.class));
    }
  }

  @Nested
  @DisplayName("reflectable — what the bean machinery may decompose")
  class Reflectable {

    @Test
    @DisplayName("records and plain beans are reflectable")
    void recordsAndBeansAreReflectable() {
      assertTrue(rules.reflectable(Point.class));
      assertTrue(rules.reflectable(AddressBean.class));
    }

    @Test
    @DisplayName("primitives, arrays, enums, and interfaces are not reflectable")
    void structuralExclusions() {
      assertFalse(rules.reflectable(int.class));
      assertFalse(rules.reflectable(String[].class));
      assertFalse(rules.reflectable(DayOfWeek.class));
      assertFalse(rules.reflectable(List.class));
    }

    @Test
    @DisplayName("the scalar families — CharSequence, Number, Boolean, Character, Temporal, UUID — are not reflectable")
    void scalarFamilyExclusions() {
      assertFalse(rules.reflectable(String.class));
      assertFalse(rules.reflectable(Integer.class));
      assertFalse(rules.reflectable(Boolean.class));
      assertFalse(rules.reflectable(Character.class));
      assertFalse(rules.reflectable(LocalDate.class));
      assertFalse(rules.reflectable(UUID.class));
    }
  }

  @Nested
  @DisplayName("same-kind discriminator axes")
  class SameKindAxes {

    @Test
    @DisplayName("two List implementations agree on every axis")
    void listPairAgrees() {
      assertTrue(rules.sameKindCollection(ArrayList.class, LinkedList.class));
    }

    @Test
    @DisplayName("a List vs a Set disagrees on the List axis")
    void listVsSetDisagrees() {
      assertFalse(rules.sameKindCollection(ArrayList.class, HashSet.class));
    }

    @Test
    @DisplayName("a plain Set vs a SortedSet disagrees on the sorted axis")
    void plainSetVsSortedSetDisagrees() {
      assertFalse(rules.sameKindCollection(HashSet.class, TreeSet.class));
      assertTrue(rules.sameKindCollection(TreeSet.class, TreeSet.class));
    }

    @Test
    @DisplayName("within the Queue residual, a Deque vs a plain Queue disagrees on the Deque axis")
    void dequeVsPlainQueueDisagrees() {
      assertFalse(rules.sameKindCollection(ArrayDeque.class, PriorityQueue.class));
      assertTrue(rules.sameKindCollection(ArrayDeque.class, ArrayDeque.class));
    }

    @Test
    @DisplayName("a non-collection on either side is never same-kind")
    void nonCollectionIsNeverSameKind() {
      assertFalse(rules.sameKindCollection(String.class, ArrayList.class));
      assertFalse(rules.sameKindMap(String.class, HashMap.class));
    }

    @Test
    @DisplayName("Map pairs agree only when both sides sit on the same side of the SortedMap axis")
    void mapSortedAxis() {
      assertTrue(rules.sameKindMap(HashMap.class, LinkedHashMap.class));
      assertFalse(rules.sameKindMap(HashMap.class, TreeMap.class));
      assertTrue(rules.sameKindMap(TreeMap.class, ConcurrentSkipListMap.class));
    }
  }

  @Nested
  @DisplayName("matchFields — same-name matching over claimed rows")
  class MatchFields {

    @Test
    @DisplayName("matches follow target order; leftovers land in unmatchedTargets / unmatchedSources")
    void matchesInTargetOrder() {
      final var result = PairingRules.matchFields(
        List.of("id", "name", "orphanSource"),
        List.of("name", "id", "orphanTarget"),
        Set.of(),
        Set.of()
      );
      assertEquals(List.of("name", "id"), result.matched());
      assertEquals(List.of("orphanTarget"), result.unmatchedTargets());
      assertEquals(List.of("orphanSource"), result.unmatchedSources());
    }

    @Test
    @DisplayName("a claimed target name is skipped entirely — neither matched nor reported unmatched")
    void claimedTargetIsSkipped() {
      final var result = PairingRules.matchFields(
        List.of("id", "name"),
        List.of("id", "name"),
        Set.of(),
        Set.of("name")
      );
      assertEquals(List.of("id"), result.matched());
      assertEquals(List.of(), result.unmatchedTargets());
      assertEquals(List.of("name"), result.unmatchedSources());
    }

    @Test
    @DisplayName("a claimed source name is excluded from unmatchedSources even with no target consumer")
    void claimedSourceNotReportedUnmatched() {
      final var result = PairingRules.matchFields(
        List.of("id", "legacyField"),
        List.of("id"),
        Set.of("legacyField"),
        Set.of()
      );
      assertEquals(List.of("id"), result.matched());
      assertEquals(List.of(), result.unmatchedTargets());
      assertEquals(List.of(), result.unmatchedSources());
    }
  }

  @Nested
  @DisplayName("ContainerView invariant")
  class ContainerViewInvariant {

    @Test
    @DisplayName("MAP_VALUES requires a key type; every other kind forbids one")
    void keyTypeCoupledToMapValuesKind() {
      assertThrows(IllegalArgumentException.class, () ->
        new ContainerView<Type>(ContainerView.Kind.MAP_VALUES, String.class, null, Map.class)
      );
      assertThrows(IllegalArgumentException.class, () ->
        new ContainerView<Type>(ContainerView.Kind.LIST, String.class, String.class, List.class)
      );
    }
  }
}
