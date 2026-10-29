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
 * A generated bridge writing the {@code Integer} result of a via row's class into an {@code int}:
 * forward, backward, and patch, from a partial holding a value and from one whose slot is null, so
 * the base's own {@code int} is written. The values sit outside the {@code Integer} cache, so a
 * boxing on the write path shows as allocation. The hand-written rows, which construct the same
 * records with the same reads, are the control a run is normalised against.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=WrapperResultBenchmark -Pjmh.profilers=gc
 * }</pre>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class WrapperResultBenchmark {

  private BenchWrapSrc source;
  private BenchWrapTgt target;
  private BenchWrapTgt nullSlot;

  @Setup
  public void setup() {
    source = new BenchWrapSrc(1000, 2000);
    target = new BenchWrapTgt(3000, 4000);
    nullSlot = new BenchWrapTgt(null, 4000);
  }

  @Benchmark
  public BenchWrapTgt generatedForward() {
    return BenchWrapSrcToBenchWrapTgtBridge.forward(source);
  }

  @Benchmark
  public BenchWrapSrc generatedBackward() {
    return BenchWrapSrcToBenchWrapTgtBridge.backward(target);
  }

  @Benchmark
  public BenchWrapSrc generatedPatch() {
    return BenchWrapSrcToBenchWrapTgtBridge.patch(source, target);
  }

  @Benchmark
  public BenchWrapSrc generatedPatchFromBase() {
    return BenchWrapSrcToBenchWrapTgtBridge.patch(source, nullSlot);
  }

  @Benchmark
  public BenchWrapTgt handWrittenForward() {
    final var b = BenchWrapBMap.forward(source.b());
    return new BenchWrapTgt(BenchWrapAMap.forward(source.a()), b == null ? 0 : b);
  }

  @Benchmark
  public BenchWrapSrc handWrittenPatchFromBase() {
    final var a = nullSlot.a();
    return new BenchWrapSrc(a == null ? source.a() : BenchWrapAMap.backward(a), BenchWrapBMap.backward(nullSlot.b()));
  }
}
