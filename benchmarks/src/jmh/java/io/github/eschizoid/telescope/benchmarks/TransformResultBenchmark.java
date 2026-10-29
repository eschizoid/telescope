package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.mapping.Mapping;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * A transform filling a bean's one {@code String} value, through a generated bridge and through a
 * runtime row typed as the value or wider than it. The hand-written row, a getter and a setter, is
 * the control a run is normalised against.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=TransformResultBenchmark -Pjmh.profilers=gc
 * }</pre>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class TransformResultBenchmark {

  private BenchTxSrc source;
  private ForwardMapper<BenchTxSrc, BenchTxTgt> typed;
  private ForwardMapper<BenchTxSrc, BenchTxTgt> wide;

  @Setup
  public void setup() {
    source = new BenchTxSrc();
    source.setValue("a");
    final var fn = new BenchTxFn();
    typed = Telescope.mapperForward(
      BenchTxSrc.class,
      BenchTxTgt.class,
      Mapping.<BenchTxSrc, BenchTxTgt, String, String>toOneWay(BenchTxSrc::getValue, BenchTxTgt::getValue, fn::forward)
    );
    wide = Telescope.mapperForward(
      BenchTxSrc.class,
      BenchTxTgt.class,
      Mapping.<BenchTxSrc, BenchTxTgt, String, Object>toOneWay(BenchTxSrc::getValue, BenchTxTgt::getValue, value ->
        (Object) fn.forward(value)
      )
    );
  }

  @Benchmark
  public BenchTxTgt generated() {
    return BenchTxSrcToBenchTxTgtBridge.forward(source);
  }

  @Benchmark
  public BenchTxTgt runtimeTyped() {
    return typed.forward(source);
  }

  @Benchmark
  public BenchTxTgt runtimeWide() {
    return wide.forward(source);
  }

  @Benchmark
  public BenchTxTgt handWritten() {
    final var out = new BenchTxTgt();
    out.setValue(source.getValue());
    return out;
  }
}
