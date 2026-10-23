package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.mapping.Mapping.via;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
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
 * A list converted element by element through a hand-written converter: by a {@code @ViaMapper} on
 * the source's class, by a via row of the converter's two methods on a {@code @Bridge} field, and
 * by the same rows on the runtime path. A loop written by hand is the control.
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

  @Setup
  public void setup() {
    final var items = new ArrayList<VbItem>(size);
    for (var i = 0; i < size; i++) items.add(new VbItem("n" + i, i));
    src = new VbSrc(items);
    target = VbSrcBridge.BRIDGE_FN.forward(src);
    runtimeMapper = Telescope.mapper(
      VbSrc.class,
      VbTgt.class,
      via(VbSrc::items, VbTgt::items, Telescope.mapper(VbItem.class, VbItemDto.class, Mapping.to(VbItem::name, VbItemDto::label)))
    );
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
    return VbSrcBridge.BRIDGE_FN.forward(src);
  }

  @Benchmark
  public VbSrc classform_via_mapper_backward() {
    return VbSrcBridge.BRIDGE_FN.backward(target);
  }

  @Benchmark
  public VbTgt runtime_via_mapper_forward() {
    return runtimeMapper.forward(src);
  }
}
