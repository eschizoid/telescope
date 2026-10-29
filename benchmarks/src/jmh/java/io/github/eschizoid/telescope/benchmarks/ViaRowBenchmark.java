package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.mapping.Mapping.via;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.introspection.OpticNode;
import io.github.eschizoid.telescope.mapping.Mapping;
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
 * A list converted element by element through a hand-written converter: by a via row naming the
 * converter's class on a {@code @Bridge} field of the source itself and of a holder class, by a via
 * row of the converter's two methods on a {@code @Bridge} field, and by the same rows on the
 * runtime path. A loop written by hand is the control.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ViaRowBenchmark {

  @Param({ "1", "16" })
  public int size;

  private VbSrc src;
  private VbTgt target;
  private Mapper<VbSrc, VbTgt> runtimeMapper;
  private VbFieldSrc fieldSrc;
  private VbClassSrc classSrc;
  private Mapper<VbSrc, VbTgt> runtimeMethods;
  private Mapper<VbSrc, VbTgt> runtimeNamed;

  @Setup
  public void setup() {
    final var items = new ArrayList<VbItem>(size);
    for (var i = 0; i < size; i++) items.add(new VbItem("n" + i, i));
    src = new VbSrc(items);
    target = VbSrcToVbTgtBridge.BRIDGE_FN.forward(src);
    runtimeMapper = Telescope.mapper(
      VbSrc.class,
      VbTgt.class,
      via(
        VbSrc::items,
        VbTgt::items,
        Telescope.mapper(VbItem.class, VbItemDto.class, Mapping.to(VbItem::name, VbItemDto::label))
      )
    );
    fieldSrc = new VbFieldSrc(items);
    classSrc = new VbClassSrc(items);
    runtimeNamed = Telescope.mapper(VbSrc.class, VbTgt.class, via(VbSrc::items, VbTgt::items, VbItemMap.class));
    if (
      VbMappers.NAMED.explain().declared().map(OpticNode.Declared::runs).orElse(OpticNode.Runs.RUNTIME) !=
      OpticNode.Runs.GENERATED
    ) {
      throw new IllegalStateException(VbMappers.NAMED.explain().toString());
    }
    runtimeMethods = Telescope.mapper(
      VbSrc.class,
      VbTgt.class,
      via(VbSrc::items, VbTgt::items, VbItemMap::forward, VbItemMap::backward)
    );
    if (
      VbMappers.ITEMS.explain().declared().map(OpticNode.Declared::runs).orElse(OpticNode.Runs.RUNTIME) !=
      OpticNode.Runs.GENERATED
    ) {
      throw new IllegalStateException(VbMappers.ITEMS.explain().toString());
    }
  }

  @Benchmark
  public VbTgt handwritten_forward() {
    final var items = src.items();
    final var out = new ArrayList<VbItemDto>(items.size());
    for (final var item : items) out.add(item == null ? null : VbItemMap.forward(item));
    return new VbTgt(List.copyOf(out));
  }

  @Benchmark
  public VbTgt classform_via_mapper_forward() {
    return VbSrcToVbTgtBridge.BRIDGE_FN.forward(src);
  }

  @Benchmark
  public VbSrc classform_via_mapper_backward() {
    return VbSrcToVbTgtBridge.BRIDGE_FN.backward(target);
  }

  @Benchmark
  public VbTgt runtime_via_mapper_forward() {
    return runtimeMapper.forward(src);
  }

  @Benchmark
  public VbTgt field_via_methods_forward() {
    return VbMappers.ITEMS.forward(fieldSrc);
  }

  @Benchmark
  public VbFieldSrc field_via_methods_backward() {
    return VbMappers.ITEMS.backward(target);
  }

  @Benchmark
  public VbTgt runtime_via_methods_forward() {
    return runtimeMethods.forward(src);
  }

  @Benchmark
  public VbTgt field_via_class_forward() {
    return VbMappers.NAMED.forward(classSrc);
  }

  @Benchmark
  public VbClassSrc field_via_class_backward() {
    return VbMappers.NAMED.backward(target);
  }

  @Benchmark
  public VbTgt runtime_via_class_forward() {
    return runtimeNamed.forward(src);
  }
}
