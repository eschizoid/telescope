package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.conversion.BridgeFn;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.introspection.OpticNode;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Four conversions per operation through one call site that sees four receiver classes, against the
 * same four through four sites that each see one. A field's mapper is a class of its own, so a
 * method taking any {@code Mapper} is handed several; four class-form {@code BRIDGE_FN} constants
 * through one {@code BridgeFn} site are the same situation on the class form. MapStruct's four
 * direct calls are the control, whose time does not depend on either shape.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class SharedCallSiteBenchmark {

  private ShSrc1 src1;
  private ShSrc2 src2;
  private ShSrc3 src3;
  private ShSrc4 src4;
  private ShField1 field1;
  private ShField2 field2;
  private ShField3 field3;
  private ShField4 field4;
  private Object[] sources;
  private Object[] fieldSources;
  private BridgeFn<Object, Object>[] bridgeFns;
  private Mapper<Object, Object>[] fieldMappers;

  @Setup
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public void setup() {
    src1 = new ShSrc1(1L, "one", 1);
    src2 = new ShSrc2(2L, "two", 2);
    src3 = new ShSrc3(3L, "three", 3);
    src4 = new ShSrc4(4L, "four", 4);
    field1 = new ShField1(1L, "one", 1);
    field2 = new ShField2(2L, "two", 2);
    field3 = new ShField3(3L, "three", 3);
    field4 = new ShField4(4L, "four", 4);
    sources = new Object[] { src1, src2, src3, src4 };
    fieldSources = new Object[] { field1, field2, field3, field4 };
    bridgeFns = new BridgeFn[] {
      ShSrc1Bridge.BRIDGE_FN,
      ShSrc2Bridge.BRIDGE_FN,
      ShSrc3Bridge.BRIDGE_FN,
      ShSrc4Bridge.BRIDGE_FN,
    };
    fieldMappers = new Mapper[] { ShMappers.ONE, ShMappers.TWO, ShMappers.THREE, ShMappers.FOUR };
    final var classes = new HashSet<Class<?>>();
    for (final var mapper : fieldMappers) {
      // A mapper on the runtime path would measure the runtime mapper, not the generated one.
      final var runs = mapper.explain().declared().map(OpticNode.Declared::runs).orElse(OpticNode.Runs.RUNTIME);
      if (runs != OpticNode.Runs.GENERATED) throw new IllegalStateException(mapper.explain().toString());
      classes.add(mapper.getClass());
    }
    if (classes.size() != fieldMappers.length) throw new IllegalStateException("each field's mapper has its own class");
  }

  @Benchmark
  public void shared_field_forward(final Blackhole bh) {
    for (var i = 0; i < fieldMappers.length; i++) bh.consume(fieldMappers[i].forward(fieldSources[i]));
  }

  @Benchmark
  public void shared_bridgefn_forward(final Blackhole bh) {
    for (var i = 0; i < bridgeFns.length; i++) bh.consume(bridgeFns[i].forward(sources[i]));
  }

  @Benchmark
  public void direct_field_forward(final Blackhole bh) {
    bh.consume(ShMappers.ONE.forward(field1));
    bh.consume(ShMappers.TWO.forward(field2));
    bh.consume(ShMappers.THREE.forward(field3));
    bh.consume(ShMappers.FOUR.forward(field4));
  }

  @Benchmark
  public void direct_bridgefn_forward(final Blackhole bh) {
    bh.consume(ShSrc1Bridge.BRIDGE_FN.forward(src1));
    bh.consume(ShSrc2Bridge.BRIDGE_FN.forward(src2));
    bh.consume(ShSrc3Bridge.BRIDGE_FN.forward(src3));
    bh.consume(ShSrc4Bridge.BRIDGE_FN.forward(src4));
  }

  @Benchmark
  public void direct_mapstruct_forward(final Blackhole bh) {
    bh.consume(ShMapStruct.INSTANCE.one(src1));
    bh.consume(ShMapStruct.INSTANCE.two(src2));
    bh.consume(ShMapStruct.INSTANCE.three(src3));
    bh.consume(ShMapStruct.INSTANCE.four(src4));
  }
}
