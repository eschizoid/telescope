package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.introspection.OpticNode;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * {@code Mapper.into(target, source)} against {@code forward(source)} on the same pair. Both walk
 * the same fields; {@code into} additionally reads each produced value back and writes it onto a
 * caller-supplied target, so it should cost a constant multiple of {@code forward} and scale the
 * same way with property count. A ratio that grows with arity means per-property work that belongs
 * at bind time is being redone per call.
 *
 * <p>{@code intoGenerated} and {@code intoGeneratedWide} call {@code into} on the mapper a
 * {@code @Bridge} field declares ({@link IntoMappers}), over a source of the same properties and
 * the same target class. {@code intoHandWritten} and {@code intoHandWrittenWide} write the same
 * values onto the same target with one setter call per property, which is what the generated path
 * should cost; they and {@code forwardOnly} are the controls, rows no change to {@code into} moves.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=IntoBenchmark -Pjmh.profilers=gc
 * }</pre>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class IntoBenchmark {

  public record NarrowRec(String a, String b, String c, String d, String e) {}

  public record WideRec(
    String f0,
    String f1,
    String f2,
    String f3,
    String f4,
    String f5,
    String f6,
    String f7,
    String f8,
    String f9,
    String f10,
    String f11,
    String f12,
    String f13,
    String f14,
    String f15,
    String f16,
    String f17,
    String f18,
    String f19
  ) {}

  private Mapper<NarrowRec, IntoNarrow> narrow;
  private NarrowRec narrowSource;
  private IntoNarrow narrowTarget;
  private Mapper<WideRec, IntoWide> wide;
  private WideRec wideSource;
  private IntoWide wideTarget;
  private IntoFieldSrc narrowFieldSource;
  private IntoFieldWideSrc wideFieldSource;

  @Setup
  public void setup() {
    narrow = Telescope.mapper(NarrowRec.class, IntoNarrow.class);
    narrowSource = new NarrowRec("a", "b", "c", "d", "e");
    narrowTarget = new IntoNarrow();
    narrow.into(narrowTarget, narrowSource);

    wide = Telescope.mapper(WideRec.class, IntoWide.class);
    wideSource = new WideRec(
      "f0",
      "f1",
      "f2",
      "f3",
      "f4",
      "f5",
      "f6",
      "f7",
      "f8",
      "f9",
      "f10",
      "f11",
      "f12",
      "f13",
      "f14",
      "f15",
      "f16",
      "f17",
      "f18",
      "f19"
    );
    wideTarget = new IntoWide();
    wide.into(wideTarget, wideSource);

    narrowFieldSource = new IntoFieldSrc("a", "b", "c", "d", "e");
    wideFieldSource = new IntoFieldWideSrc(
      "f0",
      "f1",
      "f2",
      "f3",
      "f4",
      "f5",
      "f6",
      "f7",
      "f8",
      "f9",
      "f10",
      "f11",
      "f12",
      "f13",
      "f14",
      "f15",
      "f16",
      "f17",
      "f18",
      "f19"
    );
    // A generated row measures the generated bridge only while the field's mapper runs it; a
    // mapper left on the runtime path would measure something else under the same name.
    for (final var field : List.of(IntoMappers.NARROW.explain(), IntoMappers.WIDE.explain())) {
      if (field.declared().map(OpticNode.Declared::runs).orElse(null) != OpticNode.Runs.GENERATED) {
        throw new IllegalStateException("a @Bridge field's mapper runs on the runtime path: " + field);
      }
    }
    IntoMappers.NARROW.into(narrowTarget, narrowFieldSource);
    IntoMappers.WIDE.into(wideTarget, wideFieldSource);
  }

  @Benchmark
  public IntoNarrow forwardOnly() {
    return narrow.forward(narrowSource);
  }

  @Benchmark
  public IntoNarrow intoExisting() {
    return narrow.into(narrowTarget, narrowSource);
  }

  @Benchmark
  public IntoWide forwardOnlyWide() {
    return wide.forward(wideSource);
  }

  @Benchmark
  public IntoWide intoExistingWide() {
    return wide.into(wideTarget, wideSource);
  }

  @Benchmark
  public IntoNarrow intoGenerated() {
    return IntoMappers.NARROW.into(narrowTarget, narrowFieldSource);
  }

  @Benchmark
  public IntoNarrow intoHandWritten() {
    final var source = narrowSource;
    final var target = narrowTarget;
    target.setA(source.a());
    target.setB(source.b());
    target.setC(source.c());
    target.setD(source.d());
    target.setE(source.e());
    return target;
  }

  @Benchmark
  public IntoWide intoGeneratedWide() {
    return IntoMappers.WIDE.into(wideTarget, wideFieldSource);
  }

  @Benchmark
  public IntoWide intoHandWrittenWide() {
    final var source = wideSource;
    final var target = wideTarget;
    target.setF0(source.f0());
    target.setF1(source.f1());
    target.setF2(source.f2());
    target.setF3(source.f3());
    target.setF4(source.f4());
    target.setF5(source.f5());
    target.setF6(source.f6());
    target.setF7(source.f7());
    target.setF8(source.f8());
    target.setF9(source.f9());
    target.setF10(source.f10());
    target.setF11(source.f11());
    target.setF12(source.f12());
    target.setF13(source.f13());
    target.setF14(source.f14());
    target.setF15(source.f15());
    target.setF16(source.f16());
    target.setF17(source.f17());
    target.setF18(source.f18());
    target.setF19(source.f19());
    return target;
  }
}
