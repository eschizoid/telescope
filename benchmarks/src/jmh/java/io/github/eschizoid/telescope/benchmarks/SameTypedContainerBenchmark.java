package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * A record whose one component declares the same container type on both sides, converted by a
 * reused runtime mapper and by the bridge {@code @Bridge} generates. The two hand-written rows are
 * the controls: one builds the target around the source's own container, the other around a copy
 * made by the container's copy constructor, and neither runs any telescope code, so a control that
 * moves between two runs measures the runners rather than the change.
 *
 * <p>{@code MUTABLE} holds the source's elements in an {@code ArrayList}, {@code LinkedHashSet} or
 * {@code LinkedHashMap}; {@code UNMODIFIABLE} holds them in a {@code List.copyOf}, {@code
 * Set.copyOf} or {@code Map.copyOf}, which nothing can modify.
 */
@State(Scope.Thread)
public class SameTypedContainerBenchmark {

  @Param({ "0", "16", "256", "4096" })
  public int size;

  @Param({ "LIST", "SET", "MAP" })
  public String kind;

  @Param({ "MUTABLE", "UNMODIFIABLE" })
  public String source;

  private Mapper<Object, Object> mapper;
  private Function<Object, Object> bridge;
  private Function<Object, Object> share;
  private Function<Object, Object> copy;
  private Object input;

  @Setup
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public void setup() {
    final var mutable = source.equals("MUTABLE");
    switch (kind) {
      case "LIST" -> {
        final var values = new ArrayList<Integer>(size);
        for (int i = 0; i < size; i++) values.add(i);
        input = new SameTypedList(mutable ? values : List.copyOf(values));
        mapper = (Mapper) Telescope.mapper(SameTypedList.class, SameTypedListDto.class);
        bridge = in -> SameTypedListBridge.forward((SameTypedList) in);
        share = in -> new SameTypedListDto(((SameTypedList) in).values());
        copy = in -> new SameTypedListDto(new ArrayList<>(((SameTypedList) in).values()));
      }
      case "SET" -> {
        final var values = new LinkedHashSet<Integer>();
        for (int i = 0; i < size; i++) values.add(i);
        input = new SameTypedSet(mutable ? values : Set.copyOf(values));
        mapper = (Mapper) Telescope.mapper(SameTypedSet.class, SameTypedSetDto.class);
        bridge = in -> SameTypedSetBridge.forward((SameTypedSet) in);
        share = in -> new SameTypedSetDto(((SameTypedSet) in).values());
        copy = in -> new SameTypedSetDto(new LinkedHashSet<>(((SameTypedSet) in).values()));
      }
      case "MAP" -> {
        final var values = new LinkedHashMap<Integer, Integer>();
        for (int i = 0; i < size; i++) values.put(i, i);
        input = new SameTypedMap(mutable ? values : Map.copyOf(values));
        mapper = (Mapper) Telescope.mapper(SameTypedMap.class, SameTypedMapDto.class);
        bridge = in -> SameTypedMapBridge.forward((SameTypedMap) in);
        share = in -> new SameTypedMapDto(((SameTypedMap) in).values());
        copy = in -> new SameTypedMapDto(new LinkedHashMap<>(((SameTypedMap) in).values()));
      }
      default -> throw new IllegalArgumentException(kind);
    }
    for (final var conversion : List.of(mapper::forward, bridge, share, copy)) {
      if (sizeOf(conversion.apply(input)) != size) throw new IllegalStateException("lost values");
    }
  }

  private static int sizeOf(final Object record) {
    final Object values = switch (record) {
      case SameTypedListDto l -> l.values();
      case SameTypedSetDto s -> s.values();
      case SameTypedMapDto m -> m.values();
      default -> throw new IllegalArgumentException(String.valueOf(record));
    };
    return values instanceof Map<?, ?> map ? map.size() : ((Collection<?>) values).size();
  }

  @Benchmark
  public Object mapperForward() {
    return mapper.forward(input);
  }

  @Benchmark
  public Object bridgeForward() {
    return bridge.apply(input);
  }

  @Benchmark
  public Object handWrittenShare() {
    return share.apply(input);
  }

  @Benchmark
  public Object handWrittenCopy() {
    return copy.apply(input);
  }
}
