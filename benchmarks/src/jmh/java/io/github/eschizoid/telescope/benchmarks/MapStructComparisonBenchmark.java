package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.List;
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
 * Apples-to-apples comparison between MapStruct, telescope's runtime path, and telescope's codegen
 * ({@code @Bridge}) path on identical fixture shapes across three depth tiers and both directions
 * (bean → record forward, record → bean backward).
 *
 * <h2>What the rows measure</h2>
 *
 * <p>Three depth tiers × two directions × three engines, plus one static-forward row per tier and a
 * {@code BRIDGE_FN.forward} row on the flat tier (the directly-callable one-interface-hop constant):
 *
 * <ul>
 *   <li>{@code flat_*} — five scalar fields, no nesting.
 *   <li>{@code nested_*} — outer with two scalars + one nested type ({@code Address}).
 *   <li>{@code deep_*} — three levels of nesting plus two {@code List<>} hops ({@code Company →
 *       List<Department> → List<Team>}).
 * </ul>
 *
 * <p>For each tier the suite measures both {@code _forward} (bean → record) and {@code _backward}
 * (record → bean), and across all six rows the same input fixture instance is reused so the three
 * engines time the SAME work — only the conversion path varies.
 *
 * <h2>Engines</h2>
 *
 * <ul>
 *   <li>{@code _mapstruct_*} — MapStruct's compile-time-generated {@code *Impl} class. Looked up
 *       once via {@code Mappers.getMapper(...)} and held as a static field; the hot loop is a
 *       direct interface dispatch into bytecode the MapStruct processor emitted.
 *   <li>{@code _runtime_*} — telescope's reflective {@link Telescope#mapper(Class, Class,
 *       io.github.eschizoid.telescope.mapping.MapStep...)} factory. Per-component reads / writes go
 *       through cached {@code LambdaMetafactory}-built {@code Function}/{@code BiConsumer}
 *       dispatchers (see ADR-0005). No codegen on the consumer's build.
 *   <li>{@code _codegen_*} — telescope's {@code @Bridge}-emitted {@code *Bridge.BRIDGE} constant.
 *       Direct method-reference + canonical-constructor calls at every hop — the same dispatch
 *       shape MapStruct's generated impl uses, surfaced through the public {@link Telescope} value.
 * </ul>
 *
 * <h2>Reading the numbers</h2>
 *
 * <p>The {@code _mapstruct_*} and {@code _codegen_*} rows are the closest comparison: both bind at
 * compile time and emit direct bytecode. Any delta between them on a given tier is the cost of
 * telescope's lattice {@code .then(...)} composition vs MapStruct's hand-templated method body —
 * usually small and dominated by JIT inlining. The {@code _runtime_*} rows establish the upper
 * bound on telescope when the consumer opts out of codegen entirely.
 *
 * <p>Run with: {@code ./gradlew :benchmarks:jmh -Pjmh.includes=MapStructComparisonBenchmark}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class MapStructComparisonBenchmark {

  // ---------- Flat tier: 5 scalar fields, no nesting ----------

  private McFlatBean flatBean;
  private McFlatRec flatRec;
  private Mapper<McFlatBean, McFlatRec> flatRuntimeMapper;

  // ---------- Nested tier: outer + one nested record ----------

  private McNestedBean nestedBean;
  private McNestedRec nestedRec;
  private Mapper<McNestedBean, McNestedRec> nestedRuntimeMapper;

  // ---------- Deep tier: 3-level nesting + 2 list hops ----------

  private McCompanyBean deepBean;
  private McCompanyRec deepRec;
  private Mapper<McCompanyBean, McCompanyRec> deepRuntimeMapper;

  @Setup
  public void setup() {
    // Flat tier — single object per direction.
    flatBean = new McFlatBean(42L, "alice@example.com", "Alice", 30, true);
    flatRec = new McFlatRec(42L, "alice@example.com", "Alice", 30, true);
    flatRuntimeMapper = Telescope.mapper(McFlatBean.class, McFlatRec.class);

    // Nested tier — outer + nested address.
    nestedBean = new McNestedBean(7L, "bob@example.com", new McAddressBean("123 Main St", "Brooklyn", "11201"));
    nestedRec = new McNestedRec(7L, "bob@example.com", new McAddressRec("123 Main St", "Brooklyn", "11201"));
    nestedRuntimeMapper = Telescope.mapper(McNestedBean.class, McNestedRec.class);

    // Deep tier — 2 departments × 3 teams = 6 leaf records / beans.
    deepBean = buildCompanyBean();
    deepRec = buildCompanyRec();
    deepRuntimeMapper = Telescope.mapper(McCompanyBean.class, McCompanyRec.class);
  }

  // ---------- Flat — forward (bean → record) ----------

  @Benchmark
  public void flat_mapstruct_forward(final Blackhole bh) {
    bh.consume(McFlatMapStruct.INSTANCE.toRec(flatBean));
  }

  @Benchmark
  public void flat_telescope_runtime_forward(final Blackhole bh) {
    bh.consume(flatRuntimeMapper.forward(flatBean));
  }

  @Benchmark
  public void flat_telescope_codegen_forward(final Blackhole bh) {
    bh.consume(McFlatBeanBridge.BRIDGE.read(flatBean));
  }

  @Benchmark
  public void flat_telescope_codegen_static_forward(final Blackhole bh) {
    // Direct static call — same shape MapStruct's INSTANCE.toRec(...) compiles to. Isolates the
    // codegen output quality from the Telescope lattice dispatch the BRIDGE.read(...) path pays.
    bh.consume(McFlatBeanBridge.forward(flatBean));
  }

  @Benchmark
  public void flat_telescope_codegen_bridgefn_forward(final Blackhole bh) {
    // The emitted BRIDGE_FN constant — one interface hop, the same dispatch shape as MapStruct's
    // INSTANCE.toRec(...). The passable, composition-free value an adopter calls in a hot loop;
    // should land between BRIDGE.read (lattice) and the static forward (zero-hop) floor.
    bh.consume(McFlatBeanBridge.BRIDGE_FN.forward(flatBean));
  }

  // ---------- Flat — backward (record → bean) ----------

  @Benchmark
  public void flat_mapstruct_backward(final Blackhole bh) {
    bh.consume(McFlatMapStruct.INSTANCE.toBean(flatRec));
  }

  @Benchmark
  public void flat_telescope_runtime_backward(final Blackhole bh) {
    bh.consume(flatRuntimeMapper.backward(flatRec));
  }

  @Benchmark
  public void flat_telescope_codegen_backward(final Blackhole bh) {
    bh.consume(McFlatBeanBridge.BRIDGE.set(flatBean, flatRec));
  }

  // ---------- Nested — forward ----------

  @Benchmark
  public void nested_mapstruct_forward(final Blackhole bh) {
    bh.consume(McNestedMapStruct.INSTANCE.toRec(nestedBean));
  }

  @Benchmark
  public void nested_telescope_runtime_forward(final Blackhole bh) {
    bh.consume(nestedRuntimeMapper.forward(nestedBean));
  }

  @Benchmark
  public void nested_telescope_codegen_forward(final Blackhole bh) {
    bh.consume(McNestedBeanBridge.BRIDGE.read(nestedBean));
  }

  @Benchmark
  public void nested_telescope_codegen_static_forward(final Blackhole bh) {
    bh.consume(McNestedBeanBridge.forward(nestedBean));
  }

  // ---------- Nested — backward ----------

  @Benchmark
  public void nested_mapstruct_backward(final Blackhole bh) {
    bh.consume(McNestedMapStruct.INSTANCE.toBean(nestedRec));
  }

  @Benchmark
  public void nested_telescope_runtime_backward(final Blackhole bh) {
    bh.consume(nestedRuntimeMapper.backward(nestedRec));
  }

  @Benchmark
  public void nested_telescope_codegen_backward(final Blackhole bh) {
    bh.consume(McNestedBeanBridge.BRIDGE.set(nestedBean, nestedRec));
  }

  // ---------- Deep — forward ----------

  @Benchmark
  public void deep_mapstruct_forward(final Blackhole bh) {
    bh.consume(McDeepMapStruct.INSTANCE.toRec(deepBean));
  }

  @Benchmark
  public void deep_telescope_runtime_forward(final Blackhole bh) {
    bh.consume(deepRuntimeMapper.forward(deepBean));
  }

  @Benchmark
  public void deep_telescope_codegen_forward(final Blackhole bh) {
    bh.consume(McCompanyBeanBridge.BRIDGE.read(deepBean));
  }

  @Benchmark
  public void deep_telescope_codegen_static_forward(final Blackhole bh) {
    bh.consume(McCompanyBeanBridge.forward(deepBean));
  }

  // ---------- Deep — backward ----------

  @Benchmark
  public void deep_mapstruct_backward(final Blackhole bh) {
    bh.consume(McDeepMapStruct.INSTANCE.toBean(deepRec));
  }

  @Benchmark
  public void deep_telescope_runtime_backward(final Blackhole bh) {
    bh.consume(deepRuntimeMapper.backward(deepRec));
  }

  @Benchmark
  public void deep_telescope_codegen_backward(final Blackhole bh) {
    bh.consume(McCompanyBeanBridge.BRIDGE.set(deepBean, deepRec));
  }

  // ---------- Fixture builders (called once at @Setup) ----------

  private static McCompanyBean buildCompanyBean() {
    final var teamsA = List.of(new McTeamBean("Platform", 8), new McTeamBean("Tools", 5), new McTeamBean("Infra", 7));
    final var teamsB = List.of(new McTeamBean("Frontend", 6), new McTeamBean("Mobile", 4), new McTeamBean("Design", 3));
    final var departments = List.of(new McDeptBean("Engineering", teamsA), new McDeptBean("Product", teamsB));
    return new McCompanyBean("Acme", departments);
  }

  private static McCompanyRec buildCompanyRec() {
    final var teamsA = List.of(new McTeamRec("Platform", 8), new McTeamRec("Tools", 5), new McTeamRec("Infra", 7));
    final var teamsB = List.of(new McTeamRec("Frontend", 6), new McTeamRec("Mobile", 4), new McTeamRec("Design", 3));
    final var departments = List.of(new McDeptRec("Engineering", teamsA), new McDeptRec("Product", teamsB));
    return new McCompanyRec("Acme", departments);
  }
}
