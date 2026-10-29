package io.github.eschizoid.telescope.benchmarks;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * A generated bridge filling a record's properties from a literal, from a supplier and from a
 * default where the source holds null, against the same record built by hand. The bridge of a pair
 * that fills nothing, {@link FillPlainSrc}, is the control a run is normalised against.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=FillRowBenchmark -Pjmh.profilers=gc
 * }</pre>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class FillRowBenchmark {

  private FillSrc held;
  private FillSrc missing;
  private FillPlainSrc plain;

  @Setup
  public void setup() {
    held = new FillSrc("a", "APAC");
    missing = new FillSrc("b", null);
    plain = new FillPlainSrc("a", "APAC");
  }

  @Benchmark
  public FillTgt generatedHeld() {
    return FillSrcToFillTgtBridge.forward(held);
  }

  @Benchmark
  public FillTgt generatedDefaulted() {
    return FillSrcToFillTgtBridge.forward(missing);
  }

  @Benchmark
  public FillTgt handWritten() {
    final var region = held.region();
    return new FillTgt(held.id(), region == null ? "EMEA" : region, "API", FillStamp.INSTANCE.get());
  }

  @Benchmark
  public FillPlainTgt plainBridge() {
    return FillPlainSrcBridge.forward(plain);
  }
}
