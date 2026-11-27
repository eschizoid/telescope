package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.service;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
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
 * A target property a {@code compute} row fills by calling a service the mapper is bound to,
 * against the same row calling a static method, on both paths. A bound service row should cost what
 * the static row costs, in time and in allocation: binding builds the mapper once, and a call
 * through it hands the bound service to the function it calls.
 *
 * <p>{@code static_generated} and {@code served_generated} run the bridges generated for the two
 * {@code @Bridge} fields, the second bound with {@code with(namer)}; {@code static_runtime} and
 * {@code served_runtime} run the same rows on the runtime path. {@code served_handwritten} builds
 * the target with the getters and the service call written out, which is what the generated path
 * should cost.
 *
 * <p>{@code plain_generated} and {@code plain_handwritten} convert the same source into a target
 * with nothing computed, a path no service row touches. They are the controls: neither moves
 * between runs on runners of the same speed.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=ServiceRowBenchmark -Pjmh.profilers=gc
 * }</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ServiceRowBenchmark {

  private SrvPerson person;
  private SrvNamer namer;
  private ForwardMapper<SrvPerson, SrvServedCard> servedGenerated;
  private ForwardMapper<SrvPerson, SrvCard> staticRuntime;
  private ForwardMapper<SrvPerson, SrvServedCard> servedRuntime;

  @Setup
  public void setup() {
    person = new SrvPerson("ada", "lovelace", 36);
    namer = new SrvNamer();
    servedGenerated = SrvMappers.SERVED.with(namer);
    staticRuntime = Telescope.mapperForward(SrvPerson.class, SrvCard.class, compute(SrvCard::full, SrvNames::full));
    servedRuntime = Telescope.mapperForward(
      SrvPerson.class,
      SrvServedCard.class,
      compute(SrvServedCard::full, service(SrvNamer::full))
    ).with(namer);
    for (final var mapper : new ForwardMapper<?, ?>[] { SrvMappers.STATIC, servedGenerated }) {
      if (
        mapper.explain().declared().map(OpticNode.Declared::runs).orElse(OpticNode.Runs.RUNTIME) !=
        OpticNode.Runs.GENERATED
      ) {
        throw new IllegalStateException(mapper.explain().toString());
      }
    }
  }

  @Benchmark
  public SrvPlain plain_handwritten() {
    return new SrvPlain(person.first(), person.last(), person.age());
  }

  @Benchmark
  public SrvPlain plain_generated() {
    return SrvMappers.PLAIN.forward(person);
  }

  @Benchmark
  public SrvServedCard served_handwritten() {
    return new SrvServedCard(person.first(), person.last(), person.age(), namer.full(person));
  }

  @Benchmark
  public SrvCard static_generated() {
    return SrvMappers.STATIC.forward(person);
  }

  @Benchmark
  public SrvServedCard served_generated() {
    return servedGenerated.forward(person);
  }

  @Benchmark
  public SrvCard static_runtime() {
    return staticRuntime.forward(person);
  }

  @Benchmark
  public SrvServedCard served_runtime() {
    return servedRuntime.forward(person);
  }
}
