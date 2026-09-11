package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Navigating a generated navigator inside the measured loop, which no other benchmark does: every
 * existing row builds its path once in {@code @Setup}, so the cost of the fluent call itself is
 * invisible to the whole suite.
 *
 * <p>Each navigator row has a prebuilt-path row beside it reading the same field of the same value.
 * The pair is what separates the two costs: the read is common to both, so the difference is what
 * the fluent call adds, and a change that moved both equally would be measuring the read.
 *
 * <p>Depth is the dimension. A one-hop path and a three-hop path do the same amount of reading per
 * element but build a different number of intermediate optics, so a per-call construction cost
 * shows up as a gap that widens with depth while the prebuilt rows stay flat relative to each
 * other. Run with {@code -Pjmh.profilers=gc}: allocation is deterministic and survives a runner
 * difference.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class NavigatorFluencyBenchmark {

  private BenchHolderSrc source;
  private Telescope<BenchHolderSrc, String> shallowPath;
  private Telescope<BenchHolderSrc, String> deepPath;

  @Setup
  public void setup() {
    source = new BenchHolderSrc(
      "acme",
      new BenchHolderDeptSrc("platform", 8, new BenchHolderAddressSrc("berlin", "10115"))
    );
    shallowPath = BenchHolderSrcTelescope.of().name();
    deepPath = BenchHolderSrcTelescope.of().department().address().city();
  }

  /** One hop, navigated per call. */
  @Benchmark
  public String navigateShallow() {
    return BenchHolderSrcTelescope.of().name().read(source);
  }

  /** The same one-hop read against a path built once — the control for the row above. */
  @Benchmark
  public String prebuiltShallow() {
    return shallowPath.read(source);
  }

  /** Three hops, navigated per call. */
  @Benchmark
  public String navigateDeep() {
    return BenchHolderSrcTelescope.of().department().address().city().read(source);
  }

  /** The same three-hop read against a path built once. */
  @Benchmark
  public String prebuiltDeep() {
    return deepPath.read(source);
  }
}
