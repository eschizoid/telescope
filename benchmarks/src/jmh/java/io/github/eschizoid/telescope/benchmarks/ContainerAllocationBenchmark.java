package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Reused runtime mappers: cardinality scaling and allocation, with setup excluded. */
@State(Scope.Thread)
public class ContainerAllocationBenchmark {

  public record Value(int n) {}

  public record ValueDto(int n) {}

  public record Lists(List<Value> values) {}

  public record ListsDto(List<ValueDto> values) {}

  public record Copies(CopyOnWriteArrayList<Value> values) {}

  public record CopiesDto(CopyOnWriteArrayList<ValueDto> values) {}

  public record Maps(Map<Integer, Value> values) {}

  public record MapsDto(Map<Integer, ValueDto> values) {}

  public record Sets(Set<Value> values) {}

  public record SetsDto(Set<ValueDto> values) {}

  // Ordered by nature rather than by a comparator, which is what makes the sorted rows below ask
  // the ordering question at all -- a container built carrying a comparator never asks.
  public record Ranked(int n) implements Comparable<Ranked> {
    @Override
    public int compareTo(final Ranked other) {
      return Integer.compare(n, other.n);
    }
  }

  public record RankedDto(int n) implements Comparable<RankedDto> {
    @Override
    public int compareTo(final RankedDto other) {
      return Integer.compare(n, other.n);
    }
  }

  // A sorted source into an unsorted target. The direction measured builds a Set, which orders
  // nothing, so it keeps its fused loop -- the row exists because that direction used to give it up
  // for an ordering its output does not have.
  public record SortedToPlain(SortedSet<Ranked> values) {}

  public record SortedToPlainDto(Set<RankedDto> values) {}

  public record Sorteds(SortedSet<Ranked> values) {}

  public record SortedsDto(SortedSet<RankedDto> values) {}

  @Param({ "0", "1", "16", "256", "4096" })
  public int size;

  @Param({ "LIST", "COPY_ON_WRITE", "MAP", "SET", "SORTED_SET", "SORTED_TO_PLAIN" })
  public String kind;

  private Mapper<Object, Object> mapper;
  private Object source;
  private Object target;

  @Setup
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public void setup() {
    final var values = new ArrayList<Value>(size);
    for (int i = 0; i < size; i++) values.add(new Value(i));
    switch (kind) {
      case "LIST" -> {
        mapper = (Mapper) Telescope.mapper(Lists.class, ListsDto.class);
        source = new Lists(values);
      }
      case "COPY_ON_WRITE" -> {
        mapper = (Mapper) Telescope.mapper(Copies.class, CopiesDto.class);
        source = new Copies(new CopyOnWriteArrayList<>(values));
      }
      case "MAP" -> {
        final var map = new LinkedHashMap<Integer, Value>();
        for (final var value : values) map.put(value.n(), value);
        mapper = (Mapper) Telescope.mapper(Maps.class, MapsDto.class);
        source = new Maps(map);
      }
      case "SET" -> {
        mapper = (Mapper) Telescope.mapper(Sets.class, SetsDto.class);
        source = new Sets(new LinkedHashSet<>(values));
      }
      // The one kind whose elements change type into a naturally ordered container, so each
      // converted element is tested before it is inserted and the fused loop is not taken. SET is
      // the control beside it: same conversion, same cardinality, no ordering to establish.
      // Forward builds an unsorted Set and keeps the fused loop; backward builds the SortedSet and
      // takes the checking loop. SORTED_SET beside it is the same conversion with both sides
      // sorted, so the pair separates what the ordering costs from what the conversion costs.
      case "SORTED_TO_PLAIN" -> {
        final var ranked = new TreeSet<Ranked>();
        for (int i = 0; i < size; i++) ranked.add(new Ranked(i));
        mapper = (Mapper) Telescope.mapper(SortedToPlain.class, SortedToPlainDto.class);
        source = new SortedToPlain(ranked);
      }
      case "SORTED_SET" -> {
        final var ranked = new TreeSet<Ranked>();
        for (int i = 0; i < size; i++) ranked.add(new Ranked(i));
        mapper = (Mapper) Telescope.mapper(Sorteds.class, SortedsDto.class);
        source = new Sorteds(ranked);
      }
      default -> throw new IllegalArgumentException(kind);
    }
    target = mapper.forward(source);
    if (!source.equals(mapper.backward(target))) throw new IllegalStateException("round trip failed");
  }

  @Benchmark
  public Object forward() {
    return mapper.forward(source);
  }

  @Benchmark
  public Object backward() {
    return mapper.backward(target);
  }
}
