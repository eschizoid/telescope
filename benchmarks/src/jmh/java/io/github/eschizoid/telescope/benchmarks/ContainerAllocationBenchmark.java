package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.openjdk.jmh.annotations.*;

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

  @Param({ "0", "1", "16", "256", "4096" })
  public int size;

  @Param({ "LIST", "COPY_ON_WRITE", "MAP", "SET" })
  public String kind;

  private Mapper<Object, Object> mapper;
  private Object source;
  private Object target;

  @Setup
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public void setup() {
    var values = new ArrayList<Value>(size);
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
        var map = new LinkedHashMap<Integer, Value>();
        for (var value : values) map.put(value.n(), value);
        mapper = (Mapper) Telescope.mapper(Maps.class, MapsDto.class);
        source = new Maps(map);
      }
      case "SET" -> {
        mapper = (Mapper) Telescope.mapper(Sets.class, SetsDto.class);
        source = new Sets(new LinkedHashSet<>(values));
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
