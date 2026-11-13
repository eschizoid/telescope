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
 * {@code Mapper.patchInto} writing a partial that sends two of seven properties, a scalar and a
 * list, into an entity-like bean in place, across the list's size, through the runtime mapper and
 * through the field's generated {@code patchInto}.
 *
 * <p>{@code handWritten} is the control: the setter for the scalar and a clear-and-add of the held
 * list, each behind the null check a hand-written PATCH handler makes, which is the floor of the
 * operation. No change to {@code patchInto} moves it, so a control that moved between two runs
 * means the runners differ.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=PatchIntoBenchmark -Pjmh.profilers=gc
 * }</pre>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class PatchIntoBenchmark {

  @Param({ "1", "16", "256" })
  public int size;

  private Mapper<PatchIntoEntity, PatchIntoChanges> runtime;
  private PatchIntoEntity target;
  private PatchIntoChanges changes;

  @Setup
  public void setup() {
    final var tags = new ArrayList<String>(size);
    for (var i = 0; i < size; i++) {
      tags.add("t" + i);
    }
    changes = new PatchIntoChanges(null, null, "EXPRESS", null, null, null, tags);
    target = new PatchIntoEntity();
    target.setNumber("SO-1");
    target.setStatus("PLACED");
    target.setDelivery("STANDARD");
    target.setQuantity(2);
    runtime = Telescope.mapper(PatchIntoEntity.class, PatchIntoChanges.class);
    // A generated row measures the generated bridge only while the field's mapper runs it, and a
    // runtime row the runtime mapper only while it runs no bridge.
    final var declared = PatchIntoMappers.CHANGES.explain().declared();
    if (declared.map(OpticNode.Declared::runs).orElse(null) != OpticNode.Runs.GENERATED) {
      throw new IllegalStateException("the @Bridge field's mapper runs on the runtime path");
    }
    if (runtime.explain().declared().map(OpticNode.Declared::runs).orElse(null) == OpticNode.Runs.GENERATED) {
      throw new IllegalStateException("the runtime mapper runs the generated bridge");
    }
    final var held = target.getTags();
    for (final Mapper<PatchIntoEntity, PatchIntoChanges> mapper : List.of(runtime, PatchIntoMappers.CHANGES)) {
      mapper.patchInto(target, changes);
      if (target.getTags() != held || !held.equals(tags) || !"EXPRESS".equals(target.getDelivery())) {
        throw new IllegalStateException("patchInto wrote " + target.getDelivery() + " " + target.getTags());
      }
      if (!"SO-1".equals(target.getNumber()) || target.getQuantity() != 2) {
        throw new IllegalStateException("patchInto wrote a property the partial left out");
      }
    }
  }

  @Benchmark
  public PatchIntoEntity patchIntoRuntime() {
    return runtime.patchInto(target, changes);
  }

  @Benchmark
  public PatchIntoEntity patchIntoGenerated() {
    return PatchIntoMappers.CHANGES.patchInto(target, changes);
  }

  @Benchmark
  public PatchIntoEntity handWritten() {
    final var into = target;
    final var sent = changes;
    if (sent.number() != null) {
      into.setNumber(sent.number());
    }
    if (sent.status() != null) {
      into.setStatus(sent.status());
    }
    if (sent.delivery() != null) {
      into.setDelivery(sent.delivery());
    }
    if (sent.discountCode() != null) {
      into.setDiscountCode(sent.discountCode());
    }
    if (sent.trackingNumber() != null) {
      into.setTrackingNumber(sent.trackingNumber());
    }
    if (sent.quantity() != null) {
      into.setQuantity(sent.quantity());
    }
    if (sent.tags() != null) {
      final List<String> held = into.getTags();
      held.clear();
      held.addAll(sent.tags());
    }
    return into;
  }
}
