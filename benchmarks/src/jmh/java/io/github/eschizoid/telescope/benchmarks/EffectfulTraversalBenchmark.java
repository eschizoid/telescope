package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.effects.Validated;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Scaling benchmark for effectful traversal updates. Each benchmark traverses a list of {@code N}
 * elements and rebuilds it through one effect, where {@code N} is swept across orders of magnitude.
 *
 * <p>The purpose is to expose the complexity class of {@code Traversal#modifyF}'s accumulator, not
 * to produce an absolute ns/op headline. The default {@code modifyF} folds results into a fresh
 * {@code ArrayList} copy per element ({@code O(n^2)} total copying); the pure {@code update} path
 * walks {@code modify} once ({@code O(n)}). Reading the result: divide each row's ns/op by its
 * {@code size} to get per-element cost. If that per-element number is flat across sizes the path is
 * linear; if it climbs roughly proportionally with {@code size} the path is quadratic.
 *
 * <p>The synchronous effects ({@code updateOptional}, {@code updateValidated}) are measured with
 * every element succeeding, so the whole traversal is processed — no short-circuit masks the fold
 * cost. {@code updatePure} is the {@code O(n)} control: same traversal, no effect.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=EffectfulTraversalBenchmark
 * }</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class EffectfulTraversalBenchmark {

  /** Single-field record whose list is the traversal target. */
  public record Bag(List<Integer> items) {}

  @Param({ "16", "64", "256", "1024" })
  public int size;

  private Bag bag;
  private Telescope<Bag, Integer> each;

  @Setup
  public void setup() {
    final var items = new ArrayList<Integer>(size);
    for (var i = 0; i < size; i++) {
      items.add(i);
    }
    bag = new Bag(items);
    each = Telescope.of(Bag.class).each(Bag::items);
  }

  /** O(n) control: pure update walks {@code modify} once, no effect accumulator. */
  @Benchmark
  public void updatePure(final Blackhole bh) {
    bh.consume(each.update(bag, n -> n + 1));
  }

  /** Synchronous effect through {@code modifyF}; every element present (full traversal). */
  @Benchmark
  public void updateOptionalAllPresent(final Blackhole bh) {
    final Optional<Bag> result = each.updateOptional(bag, n -> Optional.of(n + 1));
    bh.consume(result);
  }

  /** Synchronous accumulating effect through {@code modifyF}; every element valid. */
  @Benchmark
  public void updateValidatedAllValid(final Blackhole bh) {
    final Validated<String, Bag> result = each.updateValidated(bag, n -> new Validated.Valid<>(n + 1));
    bh.consume(result);
  }
}
