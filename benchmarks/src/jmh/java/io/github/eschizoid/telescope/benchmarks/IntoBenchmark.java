package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
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
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=IntoBenchmark -Pjmh.profilers=gc
 * }</pre>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class IntoBenchmark {

  /** Five-property bean; the twenty-property shape is {@link IntoWide}. */
  public static class IntoNarrow {

    private String a;
    private String b;
    private String c;
    private String d;
    private String e;

    public String getA() {
      return a;
    }

    public void setA(final String a) {
      this.a = a;
    }

    public String getB() {
      return b;
    }

    public void setB(final String b) {
      this.b = b;
    }

    public String getC() {
      return c;
    }

    public void setC(final String c) {
      this.c = c;
    }

    public String getD() {
      return d;
    }

    public void setD(final String d) {
      this.d = d;
    }

    public String getE() {
      return e;
    }

    public void setE(final String e) {
      this.e = e;
    }
  }

  public record NarrowRec(String a, String b, String c, String d, String e) {}

  /** Twenty-property bean: the arity at which per-property rebinding becomes visible. */
  public static class IntoWide {

    private String f0;

    public String getF0() {
      return f0;
    }

    public void setF0(final String f0) {
      this.f0 = f0;
    }

    private String f1;

    public String getF1() {
      return f1;
    }

    public void setF1(final String f1) {
      this.f1 = f1;
    }

    private String f2;

    public String getF2() {
      return f2;
    }

    public void setF2(final String f2) {
      this.f2 = f2;
    }

    private String f3;

    public String getF3() {
      return f3;
    }

    public void setF3(final String f3) {
      this.f3 = f3;
    }

    private String f4;

    public String getF4() {
      return f4;
    }

    public void setF4(final String f4) {
      this.f4 = f4;
    }

    private String f5;

    public String getF5() {
      return f5;
    }

    public void setF5(final String f5) {
      this.f5 = f5;
    }

    private String f6;

    public String getF6() {
      return f6;
    }

    public void setF6(final String f6) {
      this.f6 = f6;
    }

    private String f7;

    public String getF7() {
      return f7;
    }

    public void setF7(final String f7) {
      this.f7 = f7;
    }

    private String f8;

    public String getF8() {
      return f8;
    }

    public void setF8(final String f8) {
      this.f8 = f8;
    }

    private String f9;

    public String getF9() {
      return f9;
    }

    public void setF9(final String f9) {
      this.f9 = f9;
    }

    private String f10;

    public String getF10() {
      return f10;
    }

    public void setF10(final String f10) {
      this.f10 = f10;
    }

    private String f11;

    public String getF11() {
      return f11;
    }

    public void setF11(final String f11) {
      this.f11 = f11;
    }

    private String f12;

    public String getF12() {
      return f12;
    }

    public void setF12(final String f12) {
      this.f12 = f12;
    }

    private String f13;

    public String getF13() {
      return f13;
    }

    public void setF13(final String f13) {
      this.f13 = f13;
    }

    private String f14;

    public String getF14() {
      return f14;
    }

    public void setF14(final String f14) {
      this.f14 = f14;
    }

    private String f15;

    public String getF15() {
      return f15;
    }

    public void setF15(final String f15) {
      this.f15 = f15;
    }

    private String f16;

    public String getF16() {
      return f16;
    }

    public void setF16(final String f16) {
      this.f16 = f16;
    }

    private String f17;

    public String getF17() {
      return f17;
    }

    public void setF17(final String f17) {
      this.f17 = f17;
    }

    private String f18;

    public String getF18() {
      return f18;
    }

    public void setF18(final String f18) {
      this.f18 = f18;
    }

    private String f19;

    public String getF19() {
      return f19;
    }

    public void setF19(final String f19) {
      this.f19 = f19;
    }
  }

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
}
