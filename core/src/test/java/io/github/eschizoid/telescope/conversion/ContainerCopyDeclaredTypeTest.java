package io.github.eschizoid.telescope.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A same-typed container whose own class telescope cannot copy is copied into the class the shared
 * allocation rules rebuild its declared type as, in the family the declaration belongs to: a set
 * declared as a set is copied into a set, a map into a map.
 */
class ContainerCopyDeclaredTypeTest {

  /** A set of the adopter's own, which no copy constructor in the JDK builds. */
  static final class OwnSet<E> extends AbstractSet<E> {

    private final Set<E> backing = new LinkedHashSet<>();

    @Override
    public boolean add(final E e) {
      return backing.add(e);
    }

    @Override
    public Iterator<E> iterator() {
      return backing.iterator();
    }

    @Override
    public int size() {
      return backing.size();
    }
  }

  /** A map of the adopter's own. */
  static final class OwnMap<K, V> extends AbstractMap<K, V> {

    private final Map<K, V> backing = new LinkedHashMap<>();

    @Override
    public V put(final K key, final V value) {
      return backing.put(key, value);
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
      return backing.entrySet();
    }
  }

  @Test
  @DisplayName("a set of the adopter's own, declared as a Set, is copied into the class a Set is rebuilt as")
  void anOwnSetIsCopiedAsASet() {
    final Set<String> source = new OwnSet<>();
    source.add("b");
    source.add("a");

    final Set<String> copy = ContainerCopy.of(source, Set.class);

    assertNotSame(source, copy);
    assertInstanceOf(LinkedHashSet.class, copy);
    assertEquals(List.of("b", "a"), List.copyOf(copy));
  }

  @Test
  @DisplayName("a map of the adopter's own, declared as a Map, is copied into the class a Map is rebuilt as")
  void anOwnMapIsCopiedAsAMap() {
    final Map<String, Integer> source = new OwnMap<>();
    source.put("b", 2);
    source.put("a", 1);

    final Map<String, Integer> copy = ContainerCopy.of(source, Map.class);

    assertInstanceOf(LinkedHashMap.class, copy);
    assertEquals(List.of("b", "a"), List.copyOf(copy.keySet()));
  }

  /** A set interface of the adopter's own, which no class the rules build is an instance of. */
  interface OwnSetType<E> extends Set<E> {}

  @Test
  @DisplayName("a container declared as an interface no class the rules build implements is refused by name")
  void anUnbuildableDeclarationIsRefused() {
    final Set<String> source = new OwnSet<>();
    source.add("a");

    final var thrown = assertThrows(IllegalStateException.class, () -> ContainerCopy.of(source, OwnSetType.class));

    assertEquals("telescope rebuilds no copy of " + OwnSetType.class.getName(), thrown.getMessage());
  }
}
