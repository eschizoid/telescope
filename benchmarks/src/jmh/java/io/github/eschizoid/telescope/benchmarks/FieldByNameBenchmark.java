package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
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
 * RED baseline for the {@code fieldByName} dispatch-table pre-resolution work.
 *
 * <p>Contrasts the runtime-checked string-keyed {@code fieldByName(...)} lens against the typed
 * {@code .field(Accessor)} lens, with the lens built once in {@code @Setup} and traversed in the
 * hot loop (the real usage). The string-keyed lens is class-erased at build, so {@code
 * Beans.fieldLens} re-resolves on every traversal — {@code get} routes through {@code readProperty}
 * (a {@code ClassValue.get} + map lookup per call), and {@code set} re-walks {@code propertyNames}
 * + {@code autoWriter} and re-reads every sibling via {@code readProperty} per call. The typed lens
 * knows the concrete class at build ({@code implClassOf(getter)}) and captures the accessor/writer
 * once.
 *
 * <p>The gap here is the headroom a per-lens last-class memo on the {@code fieldByName} path could
 * close; this benchmark exists to size it before any change to {@code Beans.fieldLens}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class FieldByNameBenchmark {

  /** Six-field POJO so the {@code set} path's per-call sibling re-reads are visible. */
  public static final class Wide6 {

    private String a;
    private String b;
    private String c;
    private String d;
    private String e;
    private String f;

    public String getA() {
      return a;
    }

    public String getB() {
      return b;
    }

    public String getC() {
      return c;
    }

    public String getD() {
      return d;
    }

    public String getE() {
      return e;
    }

    public String getF() {
      return f;
    }

    public void setA(final String a) {
      this.a = a;
    }

    public void setB(final String b) {
      this.b = b;
    }

    public void setC(final String c) {
      this.c = c;
    }

    public void setD(final String d) {
      this.d = d;
    }

    public void setE(final String e) {
      this.e = e;
    }

    public void setF(final String f) {
      this.f = f;
    }
  }

  private Wide6 pojo;
  private Telescope<Wide6, String> byName;
  private Telescope<Wide6, String> typed;

  @Setup
  public void setup() {
    pojo = new Wide6();
    pojo.setA("A");
    pojo.setB("B");
    pojo.setC("C");
    pojo.setD("D");
    pojo.setE("E");
    pojo.setF("F");

    // Both lenses are built ONCE here; the benchmark times only traversal.
    byName = Telescope.ofBean(Wide6.class).fieldByName("c");
    typed = Telescope.ofBean(Wide6.class).field(Wide6::getC);
  }

  // ---- get: string-keyed (re-resolves) vs typed (captured) ------------------------------------

  @Benchmark
  public void fieldByName_get(final Blackhole bh) {
    bh.consume(byName.read(pojo));
  }

  @Benchmark
  public void typedField_get(final Blackhole bh) {
    bh.consume(typed.read(pojo));
  }

  // ---- set: string-keyed (re-resolves writer + reads all siblings) vs typed (captured) --------

  @Benchmark
  public void fieldByName_set(final Blackhole bh) {
    bh.consume(byName.set(pojo, "x"));
  }

  @Benchmark
  public void typedField_set(final Blackhole bh) {
    bh.consume(typed.set(pojo, "x"));
  }
}
