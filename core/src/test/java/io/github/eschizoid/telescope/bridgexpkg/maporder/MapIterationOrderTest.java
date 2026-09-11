package io.github.eschizoid.telescope.bridgexpkg.maporder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.github.eschizoid.telescope.Telescope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A conversion that was not asked to reorder should not reorder. An interface-typed {@code Map}
 * field picks its family default on both the generated and the reflective path, and that default
 * decides whether an ordered source survives the rebuild.
 *
 * <p>The keys below are chosen so their hash order differs from their insertion order, and {@link
 * #theKeysUsedHereActuallyReorderUnderAHashMap()} asserts that rather than assuming it — with keys
 * that happened to hash in insertion order, every assertion in this class would hold no matter
 * which container the rebuild picked.
 *
 * <p>The {@code items} Set is the control on each conversion: it has always rebuilt insertion
 * ordered, so it stays green either way and separates "the Map default changed" from "the container
 * rebuild stopped preserving anything".
 */
class MapIterationOrderTest {

  private static final List<String> KEYS = List.of(
    "zulu",
    "yankee",
    "xray",
    "whiskey",
    "victor",
    "uniform",
    "tango",
    "sierra",
    "romeo",
    "quebec"
  );

  private static MapOrderSource source() {
    final Map<String, Leaf> byKey = new LinkedHashMap<>();
    final Set<Leaf> items = new LinkedHashSet<>();
    for (final var key : KEYS) {
      byKey.put(key, new Leaf(key));
      items.add(new Leaf(key));
    }
    return new MapOrderSource(byKey, items);
  }

  private static List<String> valuesOf(final Set<LeafDto> items) {
    return items.stream().map(LeafDto::value).toList();
  }

  @Test
  @DisplayName("the keys used here really do reorder under a HashMap, so the assertions can fail")
  void theKeysUsedHereActuallyReorderUnderAHashMap() {
    final Map<String, String> hashed = new HashMap<>();
    for (final var key : KEYS) {
      hashed.put(key, key);
    }

    assertNotEquals(
      KEYS,
      new ArrayList<>(hashed.keySet()),
      "these keys hash in insertion order, so this class would pass against either container"
    );
  }

  @Test
  @DisplayName("a generated bridge keeps the iteration order of an interface-typed Map field")
  void codegenPreservesMapIterationOrder() {
    final var converted = MapOrderSourceBridge.forward(source());

    assertEquals(KEYS, new ArrayList<>(converted.byKey().keySet()));
    assertEquals(KEYS, valuesOf(converted.items()));
  }

  @Test
  @DisplayName("the reflective mapper keeps it too, so the two paths rebuild the same shape")
  void runtimePreservesMapIterationOrder() {
    final var mapper = Telescope.mapper(MapOrderSource.class, MapOrderTarget.class);

    final var converted = mapper.forward(source());

    assertEquals(KEYS, new ArrayList<>(converted.byKey().keySet()));
    assertEquals(KEYS, valuesOf(converted.items()));
  }

  @Test
  @DisplayName("backward keeps it as well, on both paths")
  void bothPathsPreserveOrderBackward() {
    final var target = MapOrderSourceBridge.forward(source());

    final var codegen = MapOrderSourceBridge.backward(target);
    final var runtime = Telescope.mapper(MapOrderSource.class, MapOrderTarget.class).backward(target);

    assertEquals(KEYS, new ArrayList<>(codegen.byKey().keySet()));
    assertEquals(KEYS, new ArrayList<>(runtime.byKey().keySet()));
  }
}
