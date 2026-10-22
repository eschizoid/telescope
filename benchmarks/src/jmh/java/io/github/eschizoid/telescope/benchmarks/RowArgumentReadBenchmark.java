package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.mapping.MapStep;
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
 * A transform on a generic record's one property, declared with a type variable, through a runtime
 * row typed by the record's type argument, which reads the property as {@code String}, and through
 * one typed by the variable's erasure, which reads it as {@code Object}. The hand-written copy is
 * the control a run is normalised against.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=RowArgumentReadBenchmark -Pjmh.profilers=gc
 * }</pre>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class RowArgumentReadBenchmark {

  /** The source, its one property declared with its type variable. */
  public record Box<X>(X value) {}

  /** The target, its one property declared with its type variable. */
  public record Out<Y>(Y value) {}

  private Box<String> source;
  private Mapper<Box<String>, Out<String>> argument;
  private Mapper<Box<Object>, Out<Object>> erased;

  /** The runtime mapper for the pair, typed by the arguments its row reads it with. */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static <A, B> Mapper<A, B> mapper(final MapStep row) {
    return Telescope.mapper((Class) Box.class, (Class) Out.class, row);
  }

  @Setup
  public void setup() {
    source = new Box<>("a");
    argument = mapper(
      Mapping.<Box<String>, Out<String>, String, String>to(Box::value, Out::value, s -> s.concat("!"), s -> s)
    );
    erased = mapper(
      Mapping.<Box<Object>, Out<Object>, Object, Object>to(
        Box::value,
        Out::value,
        s -> ((String) s).concat("!"),
        s -> s
      )
    );
  }

  @Benchmark
  public Out<String> runtimeArgument() {
    return argument.forward(source);
  }

  @Benchmark
  @SuppressWarnings("unchecked")
  public Out<Object> runtimeErased() {
    return erased.forward((Box<Object>) (Box<?>) source);
  }

  @Benchmark
  public Out<String> handWritten() {
    return new Out<>(source.value().concat("!"));
  }
}
