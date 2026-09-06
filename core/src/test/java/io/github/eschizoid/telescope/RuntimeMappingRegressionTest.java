package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.*;

import io.github.eschizoid.telescope.internal.MhIso;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Behavior regressions found in the runtime mapper review. */
class RuntimeMappingRegressionTest {

  private static final ThreadLocal<Runnable> ON_READ = new ThreadLocal<>();

  record Node(String name, List<Node> children) {
    @Override
    public String name() {
      var callback = ON_READ.get();
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
    var leaf = new Node("leaf", List.of());
    var root = new Node("root", List.of(new Node("branch", List.of(leaf, leaf))));
    var mapper = Telescope.mapper(Node.class, NodeDto.class);
    var mapped = mapper.forward(root);
    var siblings = mapped.children().getFirst().children();
    assertNotNull(siblings.get(1));
    assertEquals(siblings.getFirst(), siblings.get(1));
    assertNotSame(siblings.getFirst(), siblings.get(1));
    assertEquals(root, mapper.backward(mapped));
    var dtoLeaf = new NodeDto("leaf", List.of());
    var backward = mapper.backward(new NodeDto("root", List.of(new NodeDto("branch", List.of(dtoLeaf, dtoLeaf)))));
    assertNotNull(backward.children().getFirst().children().get(1));
  }

  @Test
  void fixedKeyGenericMapResolvesItsSupertype() {
    var values = new StringMap<Value>();
    values.put("one", new Value(1));
    var mapper = Telescope.mapper(Fixed.class, Plain.class);
    var mapped = mapper.forward(new Fixed(values));
    assertEquals(Map.of("one", new ValueDto(1)), mapped.values());
    assertEquals(new Fixed(values), mapper.backward(mapped));
  }

  @Test
  void reorderedGenericMapResolvesItsSupertype() {
    var values = new ReorderedMap<Value, String>();
    values.put("one", new Value(1));
    var mapper = Telescope.mapper(Reordered.class, Plain.class);
    var mapped = mapper.forward(new Reordered(values));
    assertEquals(Map.of("one", new ValueDto(1)), mapped.values());
    assertEquals(new Reordered(values), mapper.backward(mapped));
  }

  @Test
  void unresolvedContainerElementsFailWithAFieldDiagnostic() {
    var failure = assertThrows(IllegalStateException.class, () -> Telescope.mapper(Unknown.class, Plain.class));
    assertTrue(failure.getMessage().contains("values"));
  }

  @Test
  void sortedMapsPreserveComparatorsInBothDirections() {
    var values = new TreeMap<String, Value>(Comparator.reverseOrder());
    values.put("a", new Value(1));
    values.put("z", new Value(2));
    var mapper = Telescope.mapper(Sorted.class, SortedDto.class);
    var mapped = mapper.forward(new Sorted(values));
    assertSame(values.comparator(), mapped.values().comparator());
    assertEquals(List.of("z", "a"), new ArrayList<>(mapped.values().keySet()));
    assertSame(values.comparator(), mapper.backward(mapped).values().comparator());
  }

  @Test
  void cyclesStopAtTheFirstActiveBackEdgeInBothDirections() {
    var children = new ArrayList<Node>();
    var root = new Node("root", children);
    children.add(root);
    var mapper = Telescope.mapper(Node.class, NodeDto.class);
    assertNull(mapper.forward(root).children().getFirst());
    var dtoChildren = new ArrayList<NodeDto>();
    var dto = new NodeDto("root", dtoChildren);
    dtoChildren.add(dto);
    assertNull(mapper.backward(dto).children().getFirst());
    children.clear();
    children.add(new Node("child", List.of(root)));
    assertNull(mapper.forward(root).children().getFirst().children().getFirst());
  }

  @Test
  void diamondBranchesAndConcurrentInvocationsDoNotLoseSharedLeaves() throws Exception {
    var leaf = new Node("leaf", List.of());
    var root = new Node("root", List.of(new Node("left", List.of(leaf)), new Node("right", List.of(leaf))));
    var mapper = Telescope.mapper(Node.class, NodeDto.class);
    try (var executor = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<java.util.concurrent.Future<Node>>();
      for (int i = 0; i < 32; i++) tasks.add(executor.submit(() -> mapper.backward(mapper.forward(root))));
      for (var task : tasks) assertEquals(root, task.get());
    }
  }

  @Test
  void exceptionsAndReentrantCallsRestoreTheOuterActivePath() {
    var leaf = new Node("leaf", List.of());
    var root = new Node("root", List.of(new Node("branch", List.of(leaf, leaf))));
    var mapper = Telescope.mapper(Node.class, NodeDto.class);
    ON_READ.set(() -> {
      throw new IllegalArgumentException("getter failure");
    });
    assertThrows(RuntimeException.class, () -> mapper.forward(root));
    ON_READ.remove();
    assertEquals(root, mapper.backward(mapper.forward(root)));

    var entered = new AtomicBoolean();
    var nested = new AtomicReference<NodeDto>();
    ON_READ.set(() -> {
      if (entered.compareAndSet(false, true)) nested.set(mapper.forward(root));
    });
    var mapped = mapper.forward(root);
    ON_READ.remove();
    assertEquals(mapped, nested.get());
    assertNotNull(mapped.children().getFirst().children().get(1));
  }

  @Test
  void comparatorOnlyKeysAndEmptyMapsKeepTheirComparator() {
    var values = new TreeMap<Key, Value>(Comparator.comparingInt(Key::n).reversed());
    var mapper = Telescope.mapper(KeyMap.class, KeyMapDto.class);
    assertSame(values.comparator(), mapper.forward(new KeyMap(values)).values().comparator());
    values.put(new Key(1), new Value(1));
    values.put(new Key(2), new Value(2));
    var mapped = mapper.forward(new KeyMap(values));
    assertEquals(new Key(2), mapped.values().firstKey());
    assertEquals(new KeyMap(values), mapper.backward(mapped));
    assertNull(mapper.forward(new KeyMap(null)).values());
  }

  @Test
  void changedSortedSetElementsRequireAnExplicitComparator() {
    var values = new TreeSet<Value>(Comparator.comparingInt(Value::n));
    values.add(new Value(1));
    var mapper = Telescope.mapper(SortedValues.class, SortedValuesDto.class);
    var failure = assertThrows(IllegalStateException.class, () -> mapper.forward(new SortedValues(values)));
    assertTrue(failure.getMessage().contains("Mapping.via"));
    var dto = new TreeSet<ValueDto>(Comparator.comparingInt(ValueDto::n));
    assertThrows(IllegalStateException.class, () -> mapper.backward(new SortedValuesDto(dto)));
  }

  @Test
  void inheritedNestedListArgumentsAreSubstituted() {
    var values = new NestedList<Value>();
    values.add(List.of(new Value(1)));
    var mapper = Telescope.mapper(Nested.class, NestedDto.class);
    var mapped = mapper.forward(new Nested(values));
    assertEquals(List.of(List.of(new ValueDto(1))), mapped.values());
    assertEquals(new Nested(values), mapper.backward(mapped));
  }

  @Test
  void allExecutionStrategiesPassTheContainerAndGraphRegressions() {
    for (var mode : List.of("default", "javaLoop", "array")) {
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
      for (int size : new int[] { 0, 1, 16, 256, 4096 }) {
        var values = new ArrayList<Value>();
        for (int i = 0; i < size; i++) values.add(new Value(i));
        var mapper = Telescope.mapper(Copies.class, CopiesDto.class);
        var nonNullInput = new Copies(new CopyOnWriteArrayList<>(values));
        assertEquals(nonNullInput, mapper.backward(mapper.forward(nonNullInput)));
        values.add(null);
        var input = new Copies(new CopyOnWriteArrayList<>(values));
        var mapped = mapper.forward(input);
        assertEquals(CopyOnWriteArrayList.class, mapped.values().getClass());
        assertEquals(input, mapper.backward(mapped));
        assertNull(mapper.forward(new Copies(null)).values());
        var sets = Telescope.mapper(CopySets.class, CopySetsDto.class);
        var setInput = new CopySets(new CopyOnWriteArraySet<>(values));
        assertEquals(CopyOnWriteArraySet.class, sets.forward(setInput).values().getClass());
        assertEquals(setInput, sets.backward(sets.forward(setInput)));
      }
    }
  }
}
