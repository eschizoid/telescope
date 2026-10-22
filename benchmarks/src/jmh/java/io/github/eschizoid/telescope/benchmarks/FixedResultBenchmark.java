package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
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
 * Converting a bean whose values pass a type variable through into one whose class fixes that
 * variable to {@code String}, with a transform row typed {@code Object} writing one of the values:
 * its result is written into a property the receiving class fixes, through a setter declared with
 * the variable. The same pair converted with no rows, and the hand-written copy, are the controls a
 * run is normalised against.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=FixedResultBenchmark -Pjmh.profilers=gc
 * }</pre>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class FixedResultBenchmark {

  private BenchFixedSrc<String> source;
  private Mapper<BenchFixedSrc, BenchFixedTgt> transformed;
  private Mapper<BenchFixedSrc, BenchFixedTgt> plain;

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
    transformed = Telescope.mapper(
      sourceClass,
      targetClass,
      Mapping.<BenchFixedSrc, BenchFixedTgt, Object, Object>to(
        BenchFixedSrc::getFirst,
        BenchFixedTgt::getFirst,
        v -> v,
        v -> v
      )
    );
    plain = Telescope.mapper(sourceClass, targetClass);
  }

  @Benchmark
  public BenchFixedTgt runtimeTransformed() {
    return transformed.forward(source);
  }

  @Benchmark
  public BenchFixedTgt runtimePlain() {
    return plain.forward(source);
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
