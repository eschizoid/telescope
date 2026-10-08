package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The shared rule for a sorted map target whose keys nothing can order. */
class UnorderableSortedKeysTest {

  private final PairingRules<Type> rules = new PairingRules<>(new ReflectionProps());

  record Unordered(String v) {}

  record Ordered(String v) implements Comparable<Ordered> {
    @Override
    public int compareTo(final Ordered other) {
      return v.compareTo(other.v());
    }
  }

  interface Shape {}

  /** Open to subclassing, so a key declared as it can be a subclass that implements Comparable. */
  abstract static class Base {}

  /** A sorted map subtype that declares no constructor taking a comparator. */
  public static class Plain<K, V> extends TreeMap<K, V> {

    @Serial
    private static final long serialVersionUID = 1L;

    public Plain() {}
  }

  /** A sorted map subtype that can be handed a comparator over its keys. */
  public static class Told<K, V> extends TreeMap<K, V> {

    @Serial
    private static final long serialVersionUID = 1L;

    public Told() {}

    public Told(final Comparator<? super K> order) {
      super(order);
    }
  }

  /** A sorted map subtype fixing its own key and value types, with no comparator constructor. */
  public static class FixedUnordered extends TreeMap<Unordered, String> {

    @Serial
    private static final long serialVersionUID = 1L;

    public FixedUnordered() {}
  }

  /** The same shape over keys declared as Object. */
  public static class FixedObject extends TreeMap<Object, String> {

    @Serial
    private static final long serialVersionUID = 1L;

    public FixedObject() {}
  }

  /** A second sorted map subtype over the same keys and values, which a copy reads from. */
  public static class SourceUnordered extends TreeMap<Unordered, String> {

    @Serial
    private static final long serialVersionUID = 1L;

    public SourceUnordered() {}
  }

  /** The same shape over keys declared as Object. */
  public static class SourceObject extends TreeMap<Object, String> {

    @Serial
    private static final long serialVersionUID = 1L;

    public SourceObject() {}
  }

  @SuppressWarnings("unused")
  static class Fields {

    Plain<Unordered, String> plainUnordered;
    Plain<Unordered[], String> plainArray;
    Plain<Ordered, String> plainOrdered;
    Plain<Object, String> plainObject;
    Plain<Base, String> plainBase;
    Plain<Shape, String> plainShape;
    Plain<? extends Unordered, String> plainWildcard;
    Told<Unordered, String> toldUnordered;
    SortedMap<Unordered, String> sortedUnordered;
    Map<Unordered, String> mapUnordered;
    Map<Object, String> mapObject;
  }

  private static Type field(final String name) throws NoSuchFieldException {
    return Fields.class.getDeclaredField(name).getGenericType();
  }

  private boolean unorderable(final String name) throws NoSuchFieldException {
    final var type = field(name);
    return rules.unorderableSortedKeys(((ParameterizedType) type).getActualTypeArguments()[0], type);
  }

  @Test
  @DisplayName("a sorted map class that cannot take a comparator, over a record or array key, is refused")
  void aKeyWithNoComparableSubtypeIsRefused() throws NoSuchFieldException {
    assertTrue(unorderable("plainUnordered"));
    assertTrue(unorderable("plainArray"));
  }

  @Test
  @DisplayName("a key type whose values may still be comparable is let through")
  void aKeyThatMayHoldComparableValuesIsLetThrough() throws NoSuchFieldException {
    for (final var name : List.of("plainObject", "plainBase", "plainShape", "plainWildcard")) {
      assertFalse(unorderable(name), name);
    }
  }

  @Test
  @DisplayName("a comparable key, or a target that can be handed a comparator or keeps no order, is let through")
  void anOrderableTargetIsLetThrough() throws NoSuchFieldException {
    for (final var name : List.of("plainOrdered", "toldUnordered", "sortedUnordered", "mapUnordered")) {
      assertFalse(unorderable(name), name);
    }
  }

  @Test
  @DisplayName("decidePair refuses the unorderable pair by name, for a map lift and for a map copy alike")
  void decidePairRefusesTheKeyByName() throws NoSuchFieldException {
    final var lifted = rules.decidePair(field("mapUnordered"), field("plainUnordered"), "items");
    assertTrue(
      lifted instanceof PairDecision.Incompatible<Type> refused &&
        refused.message().contains("nothing can order its keys"),
      lifted::toString
    );
    assertFalse(
      rules.decidePair(field("mapObject"), field("plainObject"), "items") instanceof PairDecision.Incompatible,
      "an Object key converts, as the String keys it may hold are ordered naturally"
    );
    final var copied = rules.decidePair(SourceUnordered.class, FixedUnordered.class, "items");
    assertTrue(
      copied instanceof PairDecision.Incompatible<Type> refused &&
        refused.message().contains("nothing can order its keys"),
      copied::toString
    );
    assertFalse(
      rules.decidePair(SourceObject.class, FixedObject.class, "items") instanceof PairDecision.Incompatible,
      "a copy over Object keys converts"
    );
  }
}
