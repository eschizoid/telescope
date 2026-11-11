package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.introspection.OpticNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * {@code Mapper.into} writing a list property into a target that already holds a modifiable list,
 * across the list's size. The same class runs on both sides of an A/B: where {@code into} refills
 * the list the target holds, {@code intoRuntime} and {@code intoGenerated} copy the source's list
 * and then clear and refill the held one; where it replaces the list through the setter, they copy
 * and set.
 *
 * <p>{@code handWrittenReplace} sets a copy of the source's list, which is what a replacing {@code
 * into} costs at the floor, and {@code handWrittenRefill} clears the held list and adds the
 * source's elements, which is what a refilling one costs at the floor. Both are controls: no change
 * to {@code into} moves them, so a control that moved between two runs means the runners differ.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=IntoCollectionBenchmark -Pjmh.profilers=gc
 * }</pre>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class IntoCollectionBenchmark {

  @Param({ "1", "16", "256" })
  public int size;

  private Mapper<IntoListSrc, IntoListTgt> runtime;
  private IntoListSrc source;
  private IntoListTgt target;

  @Setup
  public void setup() {
    final var tags = new ArrayList<String>(size);
    for (var i = 0; i < size; i++) tags.add("t" + i);
    source = new IntoListSrc("n", tags);
    target = new IntoListTgt();
    runtime = Telescope.mapper(IntoListSrc.class, IntoListTgt.class);
    // A generated row measures the generated bridge only while the field's mapper runs it, and a
    // runtime row the runtime mapper only while it runs no bridge.
    final var declared = IntoCollectionMappers.LIST.explain().declared();
    if (declared.map(OpticNode.Declared::runs).orElse(null) != OpticNode.Runs.GENERATED) {
      throw new IllegalStateException("the @Bridge field's mapper runs on the runtime path");
    }
    if (runtime.explain().declared().map(OpticNode.Declared::runs).orElse(null) == OpticNode.Runs.GENERATED) {
      throw new IllegalStateException("the runtime mapper runs the generated bridge");
    }
    runtime.into(target, source);
    IntoCollectionMappers.LIST.into(target, source);
    if (!target.getTags().equals(tags)) throw new IllegalStateException("into wrote " + target.getTags());
  }

  @Benchmark
  public IntoListTgt intoRuntime() {
    return runtime.into(target, source);
  }

  @Benchmark
  public IntoListTgt intoGenerated() {
    return IntoCollectionMappers.LIST.into(target, source);
  }

  @Benchmark
  public IntoListTgt handWrittenReplace() {
    final var into = target;
    into.setName(source.name());
    into.setTags(new ArrayList<>(source.tags()));
    return into;
  }

  @Benchmark
  public IntoListTgt handWrittenRefill() {
    final var into = target;
    into.setName(source.name());
    final List<String> held = into.getTags();
    held.clear();
    held.addAll(source.tags());
    return into;
  }
}
