package io.github.eschizoid.telescope.benchmarks;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Generated conversion of raw container subtypes, across cardinality.
 *
 * <p>Cardinality is the dimension that makes an unsized rebuild visible: a container filled element
 * by element from its default capacity grows a logarithmic number of times, and every abandoned
 * table counts toward the allocation the {@code gc} profiler reports. At one element nothing grows,
 * so the low rows are the control for the high ones — a change that shifted every row equally would
 * be measuring something other than sizing.
 *
 * <p>Run with {@code -Pjmh.profilers=gc}: allocation is deterministic and survives a runner
 * difference, where the timings do not.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class RawContainerBenchmark {

  @Param({ "1", "16", "256", "4096" })
  public int size;

  private RawHolderA source;
  private RawHolderB target;

  @Setup
  public void setup() {
    final var urls = new RawListA();
    final var index = new RawMapA();
    final var plain = new ArrayList<RawUrlA>(size);
    for (int i = 0; i < size; i++) {
      urls.add(new RawUrlA("u" + i));
      index.put("k" + i, new RawUrlA("u" + i));
      plain.add(new RawUrlA("u" + i));
    }
    source = new RawHolderA(urls, index, plain);
    target = RawHolderABridge.forward(source);
  }

  /** Both raw-subtype sides plus the interface-to-subtype direction of the mixed field. */
  @Benchmark
  public RawHolderB forward() {
    return RawHolderABridge.forward(source);
  }

  /** The reverse, where the mixed field's output is a JDK default impl that can be sized. */
  @Benchmark
  public RawHolderA backward() {
    return RawHolderABridge.backward(target);
  }
}
