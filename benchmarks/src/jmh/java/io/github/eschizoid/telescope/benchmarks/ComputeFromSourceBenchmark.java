package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.mapping.Mapping.compute;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.introspection.OpticNode;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * A target property filled from the whole source by a {@code compute} row, against the same value
 * written by hand with a call of the method on the source.
 *
 * <p>{@code computed_codegen} and {@code computed_bridge} run the bridge generated for the
 * {@code @Bridge} field, through the field's mapper and through the bridge's own {@code forward};
 * {@code computed_runtime} runs the same row on the runtime path. {@code computed_handwritten}
 * builds the target with the getter calls and the method call written out, which is what the
 * generated bridge should cost.
 *
 * <p>{@code plain_codegen} and {@code plain_handwritten} convert the same source into a target with
 * nothing computed, a path the row does not touch. They are the controls: neither moves between
 * runs on runners of the same speed.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ComputeFromSourceBenchmark {

  private CfsPerson person;
  private Mapper<CfsPerson, CfsCard> runtime;

  @Setup
  public void setup() {
    person = new CfsPerson("ada", "lovelace", 36);
    runtime = Telescope.mapper(CfsPerson.class, CfsCard.class, compute(CfsCard::full, CfsNames::full));
    if (
      CfsMappers.CARD.explain().declared().map(OpticNode.Declared::runs).orElse(OpticNode.Runs.RUNTIME) !=
      OpticNode.Runs.GENERATED
    ) {
      throw new IllegalStateException(CfsMappers.CARD.explain().toString());
    }
  }

  @Benchmark
  public CfsPlain plain_handwritten() {
    return new CfsPlain(person.first(), person.last(), person.age());
  }

  @Benchmark
  public CfsPlain plain_codegen() {
    return CfsMappers.PLAIN.forward(person);
  }

  @Benchmark
  public CfsCard computed_handwritten() {
    return new CfsCard(person.first(), person.last(), person.age(), CfsNames.full(person));
  }

  @Benchmark
  public CfsCard computed_codegen() {
    return CfsMappers.CARD.forward(person);
  }

  @Benchmark
  public CfsCard computed_bridge() {
    return CfsPersonToCfsCardBridge.forward(person);
  }

  @Benchmark
  public CfsCard computed_runtime() {
    return runtime.forward(person);
  }
}
