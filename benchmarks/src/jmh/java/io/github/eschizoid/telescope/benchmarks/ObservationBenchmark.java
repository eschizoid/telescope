package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.openjdk.jmh.annotations.*;

/**
 * Manual executor submission uses a pre-sized queue drained outside timing. The plain path is the
 * control for traversal cost.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ObservationBenchmark {

  public record Bag(List<Integer> values) {}

  public record Group(List<Bag> bags) {}

  public record Root(List<Group> groups, List<Integer> values) {}

  @Param({ "16", "256", "4096" })
  public int size;

  @Param({ "false", "true" })
  public boolean nested;

  private Root input;
  private Telescope<Root, Integer> plain;
  private Telescope<Root, Integer> synchronous;
  private Telescope<Root, Integer> manual;
  private ArrayDeque<Runnable> queue;
  private volatile long consumed;

  @Setup(Level.Trial)
  public void setup() {
    final var values = IntStream.range(0, size).boxed().toList();
    input = new Root(List.of(new Group(List.of(new Bag(values)))), values);
    plain = nested
      ? Telescope.of(Root.class).each(Root::groups).each(Group::bags).each(Bag::values)
      : Telescope.of(Root.class).each(Root::values);
    queue = new ArrayDeque<>(size);
    final Executor executor = queue::add;
    synchronous = plain.observe(this::consume);
    manual = plain.observe(value -> executor.execute(() -> consume(value)));
  }

  private void consume(Integer value) {
    consumed += value;
  }

  @TearDown(Level.Invocation)
  public void drain() {
    while (!queue.isEmpty()) queue.remove().run();
  }

  @Benchmark
  public long plainRead() {
    return plain.count(input);
  }

  @Benchmark
  public long synchronousRead() {
    return synchronous.count(input);
  }

  @Benchmark
  public long manualRead() {
    return manual.count(input);
  }

  @Benchmark
  public Root plainUpdate() {
    return plain.update(input, value -> value + 1);
  }

  @Benchmark
  public Root synchronousUpdate() {
    return synchronous.update(input, value -> value + 1);
  }

  @Benchmark
  public Root manualUpdate() {
    return manual.update(input, value -> value + 1);
  }
}
