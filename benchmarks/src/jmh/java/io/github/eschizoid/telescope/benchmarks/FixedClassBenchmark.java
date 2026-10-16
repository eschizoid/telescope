package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Converting a bean whose values pass a type variable through into one whose class fixes that
 * variable to {@code String}: three values handed across unchanged, each written into a property
 * the receiving class fixes. The generated bridge writes each through a cast to the fixed class,
 * and the runtime mapper through its own write. The hand-written row does what a bridge has to do
 * at the least, a cast per value, and is the control a run is normalised against.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=FixedClassBenchmark -Pjmh.profilers=gc
 * }</pre>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class FixedClassBenchmark {

  private BenchFixedSrc<String> source;
  private Mapper<BenchFixedSrc, BenchFixedTgt> mapper;

  @Setup
  public void setup() {
    source = new BenchFixedSrc<>();
    source.setKey("k");
    source.setFirst("a");
    source.setSecond("b");
    source.setThird("c");
    // Passed as values: the compile-time verifier reads a property a subclass passes through as its
    // erasure, and refuses this pair, which the runtime mapper builds.
    final Class<BenchFixedSrc> sourceClass = BenchFixedSrc.class;
    final Class<BenchFixedTgt> targetClass = BenchFixedTgt.class;
    mapper = Telescope.mapper(sourceClass, targetClass);
  }

  @Benchmark
  public BenchFixedTgt generated() {
    return BenchFixedSrcBridge.forward(source);
  }

  @Benchmark
  public BenchFixedTgt runtime() {
    return mapper.forward(source);
  }

  @Benchmark
  public BenchFixedTgt handWritten() {
    final Object first = source.getFirst();
    final Object second = source.getSecond();
    final Object third = source.getThird();
    final var out = new BenchFixedTgt();
    out.setKey(source.getKey());
    out.setFirst((String) first);
    out.setSecond((String) second);
    out.setThird((String) third);
    return out;
  }
}
