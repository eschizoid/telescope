package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.*;

import io.github.eschizoid.telescope.internal.MhIso;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Contract pins for the runtime mapper: cycles sever only at active back-edges, shared acyclic
 * references map independently, parameterized container subclasses resolve through their generic
 * supertype, sorted containers keep or refuse comparators explicitly, and copy-on-write containers
 * rebuild with their concrete runtime class — across every execution strategy (fused, Java-loop,
 * array leaf).
 */
class RuntimeMappingRegressionTest {

  private static final ThreadLocal<Runnable> ON_READ = new ThreadLocal<>();

  record Node(String name, List<Node> children) {
    @Override
    public String name() {
      final var callback = ON_READ.get();
      if (callback != null) callback.run();
      return name;
    }
  }

  record NodeDto(String name, List<NodeDto> children) {}

  record Value(int n) {}

  record ValueDto(int n) {}

  public static class StringMap<V> extends HashMap<String, V> {

    private static final long serialVersionUID = 1L;
  }

  public static class ReorderedMap<V, K> extends HashMap<K, V> {

    private static final long serialVersionUID = 1L;
  }

  record Fixed(StringMap<Value> values) {}

  record Reordered(ReorderedMap<Value, String> values) {}

  record Plain(HashMap<String, ValueDto> values) {}

  record Unknown(StringMap<?> values) {}

  record Sorted(TreeMap<String, Value> values) {}

  record SortedDto(TreeMap<String, ValueDto> values) {}

  record Key(int n) {}

  record KeyMap(TreeMap<Key, Value> values) {}

  record KeyMapDto(TreeMap<Key, ValueDto> values) {}

  record SortedValues(TreeSet<Value> values) {}

  record SortedValuesDto(TreeSet<ValueDto> values) {}

  record Copies(CopyOnWriteArrayList<Value> values) {}

  record CopiesDto(CopyOnWriteArrayList<ValueDto> values) {}

  record CopySets(CopyOnWriteArraySet<Value> values) {}

  record CopySetsDto(CopyOnWriteArraySet<ValueDto> values) {}

  public static class TaggedList<Tag, E> extends ArrayList<E> {

    private static final long serialVersionUID = 1L;
  }

  public static class NestedList<E> extends TaggedList<String, List<E>> {

    private static final long serialVersionUID = 1L;
  }

  record Nested(NestedList<Value> values) {}

  record NestedDto(List<List<ValueDto>> values) {}

  @AfterEach
  void cleanup() {
    ON_READ.remove();
    System.clearProperty(MhIso.DISABLE_PROPERTY);
    System.clearProperty(MhIso.CONTAINER_DISABLE_PROPERTY);
  }

  @Test
  void sharedSiblingsAreMappedInBothDirections() {
    final var leaf = new Node("leaf", List.of());
    final var root = new Node("root", List.of(new Node("branch", List.of(leaf, leaf))));
    final var mapper = Telescope.mapper(Node.class, NodeDto.class);
    final var mapped = mapper.forward(root);
    final var siblings = mapped.children().getFirst().children();
    assertNotNull(siblings.get(1));
    assertEquals(siblings.getFirst(), siblings.get(1));
    assertNotSame(siblings.getFirst(), siblings.get(1));
    assertEquals(root, mapper.backward(mapped));
    final var dtoLeaf = new NodeDto("leaf", List.of());
    final var backward = mapper.backward(
      new NodeDto("root", List.of(new NodeDto("branch", List.of(dtoLeaf, dtoLeaf))))
    );
    assertNotNull(backward.children().getFirst().children().get(1));
  }

  @Test
  void fixedKeyGenericMapResolvesItsSupertype() {
    final var values = new StringMap<Value>();
    values.put("one", new Value(1));
    final var mapper = Telescope.mapper(Fixed.class, Plain.class);
    final var mapped = mapper.forward(new Fixed(values));
    assertEquals(Map.of("one", new ValueDto(1)), mapped.values());
    assertEquals(new Fixed(values), mapper.backward(mapped));
  }

  @Test
  void reorderedGenericMapResolvesItsSupertype() {
    final var values = new ReorderedMap<Value, String>();
    values.put("one", new Value(1));
    final var mapper = Telescope.mapper(Reordered.class, Plain.class);
    final var mapped = mapper.forward(new Reordered(values));
    assertEquals(Map.of("one", new ValueDto(1)), mapped.values());
    assertEquals(new Reordered(values), mapper.backward(mapped));
  }

  @Test
  void unresolvedContainerElementsFailWithAFieldDiagnostic() {
    final var failure = assertThrows(IllegalStateException.class, () -> Telescope.mapper(Unknown.class, Plain.class));
    assertTrue(failure.getMessage().contains("values"));
  }

  @Test
  void sortedMapsPreserveComparatorsInBothDirections() {
    final var values = new TreeMap<String, Value>(Comparator.reverseOrder());
    values.put("a", new Value(1));
    values.put("z", new Value(2));
    final var mapper = Telescope.mapper(Sorted.class, SortedDto.class);
    final var mapped = mapper.forward(new Sorted(values));
    assertSame(values.comparator(), mapped.values().comparator());
    assertEquals(List.of("z", "a"), new ArrayList<>(mapped.values().keySet()));
    assertSame(values.comparator(), mapper.backward(mapped).values().comparator());
  }

  @Test
  void cyclesStopAtTheFirstActiveBackEdgeInBothDirections() {
    final var children = new ArrayList<Node>();
    final var root = new Node("root", children);
    children.add(root);
    final var mapper = Telescope.mapper(Node.class, NodeDto.class);
    assertNull(mapper.forward(root).children().getFirst());
    final var dtoChildren = new ArrayList<NodeDto>();
    final var dto = new NodeDto("root", dtoChildren);
    dtoChildren.add(dto);
    assertNull(mapper.backward(dto).children().getFirst());
    children.clear();
    children.add(new Node("child", List.of(root)));
    assertNull(mapper.forward(root).children().getFirst().children().getFirst());
  }

  @Test
  void diamondBranchesAndConcurrentInvocationsDoNotLoseSharedLeaves() throws Exception {
    final var leaf = new Node("leaf", List.of());
    final var root = new Node("root", List.of(new Node("left", List.of(leaf)), new Node("right", List.of(leaf))));
    final var mapper = Telescope.mapper(Node.class, NodeDto.class);
    try (var executor = Executors.newFixedThreadPool(4)) {
      final var tasks = new ArrayList<Future<Node>>();
      for (int i = 0; i < 32; i++) tasks.add(executor.submit(() -> mapper.backward(mapper.forward(root))));
      for (final var task : tasks) assertEquals(root, task.get());
    }
  }

  @Test
  void exceptionsAndReentrantCallsRestoreTheOuterActivePath() {
    final var leaf = new Node("leaf", List.of());
    final var root = new Node("root", List.of(new Node("branch", List.of(leaf, leaf))));
    final var mapper = Telescope.mapper(Node.class, NodeDto.class);
    ON_READ.set(() -> {
      throw new IllegalArgumentException("getter failure");
    });
    assertThrows(RuntimeException.class, () -> mapper.forward(root));
    ON_READ.remove();
    assertEquals(root, mapper.backward(mapper.forward(root)));

    final var entered = new AtomicBoolean();
    final var nested = new AtomicReference<NodeDto>();
    ON_READ.set(() -> {
      if (entered.compareAndSet(false, true)) nested.set(mapper.forward(root));
    });
    final var mapped = mapper.forward(root);
    ON_READ.remove();
    assertEquals(mapped, nested.get());
    assertNotNull(mapped.children().getFirst().children().get(1));
  }

  @Test
  void comparatorOnlyKeysAndEmptyMapsKeepTheirComparator() {
    final var values = new TreeMap<Key, Value>(Comparator.comparingInt(Key::n).reversed());
    final var mapper = Telescope.mapper(KeyMap.class, KeyMapDto.class);
    assertSame(values.comparator(), mapper.forward(new KeyMap(values)).values().comparator());
    values.put(new Key(1), new Value(1));
    values.put(new Key(2), new Value(2));
    final var mapped = mapper.forward(new KeyMap(values));
    assertEquals(new Key(2), mapped.values().firstKey());
    assertEquals(new KeyMap(values), mapper.backward(mapped));
    assertNull(mapper.forward(new KeyMap(null)).values());
  }

  @Test
  void changedSortedSetElementsRequireAnExplicitComparator() {
    final var values = new TreeSet<Value>(Comparator.comparingInt(Value::n));
    values.add(new Value(1));
    final var mapper = Telescope.mapper(SortedValues.class, SortedValuesDto.class);
    final var failure = assertThrows(IllegalStateException.class, () -> mapper.forward(new SortedValues(values)));
    assertTrue(failure.getMessage().contains("Mapping.via"));
    final var dto = new TreeSet<ValueDto>(Comparator.comparingInt(ValueDto::n));
    assertThrows(IllegalStateException.class, () -> mapper.backward(new SortedValuesDto(dto)));
  }

  @Test
  void inheritedNestedListArgumentsAreSubstituted() {
    final var values = new NestedList<Value>();
    values.add(List.of(new Value(1)));
    final var mapper = Telescope.mapper(Nested.class, NestedDto.class);
    final var mapped = mapper.forward(new Nested(values));
    assertEquals(List.of(List.of(new ValueDto(1))), mapped.values());
    assertEquals(new Nested(values), mapper.backward(mapped));
  }

  @Test
  void allExecutionStrategiesPassTheContainerAndGraphRegressions() {
    for (final var mode : List.of("default", "javaLoop", "array")) {
      System.setProperty(MhIso.CONTAINER_DISABLE_PROPERTY, Boolean.toString(mode.equals("javaLoop")));
      System.setProperty(MhIso.DISABLE_PROPERTY, Boolean.toString(mode.equals("array")));
      sharedSiblingsAreMappedInBothDirections();
      sortedMapsPreserveComparatorsInBothDirections();
      fixedKeyGenericMapResolvesItsSupertype();
      reorderedGenericMapResolvesItsSupertype();
      comparatorOnlyKeysAndEmptyMapsKeepTheirComparator();
      inheritedNestedListArgumentsAreSubstituted();
      cyclesStopAtTheFirstActiveBackEdgeInBothDirections();
      changedSortedSetElementsRequireAnExplicitComparator();
      for (final int size : new int[] { 0, 1, 16, 256, 4096 }) {
        final var values = new ArrayList<Value>();
        for (int i = 0; i < size; i++) values.add(new Value(i));
        final var mapper = Telescope.mapper(Copies.class, CopiesDto.class);
        final var nonNullInput = new Copies(new CopyOnWriteArrayList<>(values));
        assertEquals(nonNullInput, mapper.backward(mapper.forward(nonNullInput)));
        values.add(null);
        final var input = new Copies(new CopyOnWriteArrayList<>(values));
        final var mapped = mapper.forward(input);
        assertEquals(CopyOnWriteArrayList.class, mapped.values().getClass());
        assertEquals(input, mapper.backward(mapped));
        assertNull(mapper.forward(new Copies(null)).values());
        final var sets = Telescope.mapper(CopySets.class, CopySetsDto.class);
        final var setInput = new CopySets(new CopyOnWriteArraySet<>(values));
        assertEquals(CopyOnWriteArraySet.class, sets.forward(setInput).values().getClass());
        assertEquals(setInput, sets.backward(sets.forward(setInput)));
      }
    }
  }
}
