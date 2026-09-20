package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.openjdk.jmh.annotations.*;

/**
 * Submission cost uses a pre-sized queue drained outside timing; completed work includes a worker
 * barrier.
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
  private Telescope<Root, Integer> deferred;
  private Telescope<Root, Integer> workerPath;
  private ArrayDeque<Runnable> queue;
  private ExecutorService worker;
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
    deferred = plain.observeAsync(this::consume, executor, (value, failure) -> {
      throw new AssertionError(failure);
    });
    worker = Executors.newSingleThreadExecutor();
    workerPath = plain.observeAsync(this::consume, worker, (value, failure) -> {
      throw new AssertionError(failure);
    });
  }

  private void consume(Integer value) {
    consumed += value;
  }

  @TearDown(Level.Invocation)
  public void drain() {
    while (!queue.isEmpty()) queue.remove().run();
  }

  @TearDown(Level.Trial)
  public void close() {
    worker.close();
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
  public long deferredRead() {
    return deferred.count(input);
  }

  @Benchmark
  public Root plainUpdate() {
    return plain.update(input, value -> value + 1);
  }

  @Benchmark
  public Root manualUpdate() {
    return manual.update(input, value -> value + 1);
  }

  @Benchmark
  public Root deferredUpdate() {
    return deferred.update(input, value -> value + 1);
  }

  /** Includes completion of every callback; one barrier future per batch, never per value. */
  @Benchmark
  public Root completedWorkerUpdate() throws Exception {
    final var result = workerPath.update(input, value -> value + 1);
    worker.submit(() -> {}).get();
    return result;
  }
}
