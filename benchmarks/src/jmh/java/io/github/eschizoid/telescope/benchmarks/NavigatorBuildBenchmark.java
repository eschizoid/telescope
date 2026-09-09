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
 * Building a generated navigator path at the call site, which is the idiom the codegen docs show.
 * The existing navigator rows measure a path built once in setup, so nothing covered what a hop
 * costs to construct — the dimension where a per-call lens rebuild hides.
 *
 * <p>{@code prebuiltRead} is the floor: the same path, constructed once. The gap between it and the
 * inline rows is construction, and it should be the composition itself rather than the lens, whose
 * inputs are constant for a field.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=NavigatorBuildBenchmark -Pjmh.profilers=gc
 * }</pre>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class NavigatorBuildBenchmark {

  private BenchHolderSrc source;
  private Telescope<BenchHolderSrc, String> prebuiltCity;

  @Setup
  public void setup() {
    source = new BenchHolderSrc(
      "acme",
      new BenchHolderDeptSrc("eng", 12, new BenchHolderAddressSrc("austin", "78701"))
    );
    prebuiltCity = BenchHolderSrcTelescope.of().department().address().city();
  }

  /** One hop, built inline. */
  @Benchmark
  public Object buildOneHop() {
    return BenchHolderSrcTelescope.of().name();
  }

  /** Three hops, built inline — the shape the docs show at a call site. */
  @Benchmark
  public Object buildThreeHops() {
    return BenchHolderSrcTelescope.of().department().address().city();
  }

  /** Build and read, the whole call-site idiom. */
  @Benchmark
  public String buildThreeHopsAndRead() {
    return BenchHolderSrcTelescope.of().department().address().city().read(source);
  }

  /** The floor: same read, path constructed once. */
  @Benchmark
  public String prebuiltRead() {
    return prebuiltCity.read(source);
  }
}
