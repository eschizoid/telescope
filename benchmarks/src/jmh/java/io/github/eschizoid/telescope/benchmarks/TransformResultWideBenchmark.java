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
 * A generated bridge writing a transform result typed wider than the property it fills, which it
 * checks and casts to the property's setter. The hand-written row does the least a bridge has to, a
 * cast per value, and is the control a run is normalised against.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=TransformResultWideBenchmark -Pjmh.profilers=gc
 * }</pre>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class TransformResultWideBenchmark {

  private BenchTxWideSrc source;
  private BenchTxWideFn fn;

  @Setup
  public void setup() {
    source = new BenchTxWideSrc();
    source.setValue("a");
    fn = new BenchTxWideFn();
  }

  @Benchmark
  public BenchTxTgt generated() {
    return BenchTxWideSrcToBenchTxTgtBridge.forward(source);
  }

  @Benchmark
  public BenchTxTgt handWritten() {
    final var out = new BenchTxTgt();
    out.setValue((String) fn.forward(source.getValue()));
    return out;
  }
}
