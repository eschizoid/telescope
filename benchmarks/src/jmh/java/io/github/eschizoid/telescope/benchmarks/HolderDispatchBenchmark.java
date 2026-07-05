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
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH micro-benchmarks pinning the holder-routed dispatch path layered on top of the LMF substrate.
 * Two axes:
 *
 * <ul>
 *   <li><b>Phase B — per-field dispatch.</b> {@link Telescope#field(Telescope.Accessor)} calls
 *       {@link io.github.eschizoid.telescope.internal.MetadataHolderProbe#lensFromHolder
 *       MetadataHolderProbe.lensFromHolder(implClass, name)} first; when a sibling {@code
 *       <X>Telescope} holder is present, the cached {@code Telescope<X, FieldType>} constant is
 *       returned without re-running the {@link io.github.eschizoid.telescope.internal.Records}
 *       LMF-backed field-lens build. When absent, the original LMF path runs.
 *   <li><b>Phase C — deep-mapping backward branch.</b> {@link
 *       io.github.eschizoid.telescope.internal.Reflective#structuralIso(Class)
 *       Reflective.structuralIso(cls)}'s backward direction (instance &rarr; {@code Map<String,
 *       Object>}) routes per-component reads through the holder's pre-baked {@link
 *       io.github.eschizoid.telescope.internal.optics.Lens Lens} constants when present; absent
 *       falls back to {@link io.github.eschizoid.telescope.internal.Records#read Records.read} /
 *       {@link io.github.eschizoid.telescope.internal.Beans#readProperty Beans.readProperty}. The
 *       forward direction (map &rarr; instance) is unchanged — Phase D (out of scope here) will
 *       short-circuit it via a constructor holder; numbers for the backward branch are what this
 *       benchmark pins.
 * </ul>
 *
 * <p>Two flavours of fixture for each axis: {@code _holder} rows compile against a {@link
 * io.github.eschizoid.telescope.annotations.Focus &#64;Focus}-annotated record (the {@code @Focus}
 * processor emits the sibling {@code <X>Telescope} holder, so {@link
 * io.github.eschizoid.telescope.internal.MetadataHolderProbe MetadataHolderProbe} finds it). {@code
 * _lmf} rows compile against the same record shape without the annotation, so the probe misses and
 * the LMF substrate runs the dispatch. The two flavours are otherwise structurally identical — same
 * field names, same field types, same 3-level tree — so the ratio is the holder-routed savings, not
 * a workload difference.
 *
 * <p>A third per-field row, {@code field_holder_constant}, dispatches through the generated holder
 * constant <em>directly</em> ({@code BenchHolderRecTelescope.id.update(...)}) without going through
 * {@link Telescope#of(Class) Telescope.of}.{@link Telescope#field(Telescope.Accessor) field}. It
 * skips the probe entirely — pure codegen-direct dispatch — and is the ceiling the {@code
 * field_holder} row can approach but not exceed.
 *
 * <p>Each {@link Telescope} / {@link Mapper} is built once in {@link #setup} and reused.
 *
 * <p>Run with:
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh
 * }</pre>
 *
 * <p>For numbers and how to read them, see {@code benchmarks/README.md}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class HolderDispatchBenchmark {

  // ---- Fixtures live as top-level types ------------------------------------------------------
  // @Focus is only valid on top-level records (the generated *Path class can't reference a nested
  // record's canonical constructor). The annotated flavour ({@code BenchHolderRec} et al.) lives
  // in sibling files alongside this benchmark; the unannotated flavour ({@code BenchPlainRec} et
  // al.) is the structural twin without @Focus, so MetadataHolderProbe misses and the LMF path
  // runs. The two flavours share field names, field types, and the 3-level tree shape so the only
  // difference at dispatch time is the holder's presence.

  // ---- State -----------------------------------------------------------------------------------

  private BenchHolderRec holderRec;
  private BenchPlainRec plainRec;

  private Telescope<BenchHolderRec, String> fieldHolder;
  private Telescope<BenchPlainRec, String> fieldLmf;
  // The direct holder constant (skips the probe). Resolved by name in @Setup so the benchmark
  // module does not need a compile-time reference to the generated holder class.
  private Telescope<BenchHolderRec, String> fieldHolderConstant;

  private BenchHolderSrc holderSrc;
  private BenchPlainSrc plainSrc;

  private Mapper<BenchHolderSrc, BenchHolderTgt> mapHolder;
  private Mapper<BenchPlainSrc, BenchPlainTgt> mapLmf;

  // Pre-built target instances for the backward-direction benchmarks — we do NOT want to time
  // forward() inside the backward() row.
  private BenchHolderTgt holderTgt;
  private BenchPlainTgt plainTgt;

  @Setup
  public void setup() throws Exception {
    holderRec = new BenchHolderRec("u1", "Alice", 30);
    plainRec = new BenchPlainRec("u1", "Alice", 30);

    fieldHolder = Telescope.of(BenchHolderRec.class).field(BenchHolderRec::name);
    fieldLmf = Telescope.of(BenchPlainRec.class).field(BenchPlainRec::name);

    // Direct holder-constant baseline: read the generated `name` constant from
    // `BenchHolderRecTelescope` via reflection. This is the same Telescope value `Telescope.of(X)
    // .field(X::name)` resolves to via the holder probe — but accessed without the probe so the
    // benchmark measures the dispatch primitive, not the probe overhead.
    final var holderClass = Class.forName(BenchHolderRec.class.getName() + "Telescope");
    @SuppressWarnings("unchecked")
    final var directConstant = (Telescope<BenchHolderRec, String>) holderClass.getField("name").get(null);
    fieldHolderConstant = directConstant;

    final var holderAddr = new BenchHolderAddressSrc("nyc", "10001");
    final var holderDept = new BenchHolderDeptSrc("Platform", 12, holderAddr);
    holderSrc = new BenchHolderSrc("Acme", holderDept);

    final var plainAddr = new BenchPlainAddressSrc("nyc", "10001");
    final var plainDept = new BenchPlainDeptSrc("Platform", 12, plainAddr);
    plainSrc = new BenchPlainSrc("Acme", plainDept);

    mapHolder = Telescope.mapper(BenchHolderSrc.class, BenchHolderTgt.class);
    mapLmf = Telescope.mapper(BenchPlainSrc.class, BenchPlainTgt.class);

    holderTgt = mapHolder.forward(holderSrc);
    plainTgt = mapLmf.forward(plainSrc);
  }

  // ---- Per-field dispatch (Phase B) -----------------------------------------------------------

  /**
   * {@code Telescope.of(X).field(X::name).update(x, fn)} against an unannotated record — the probe
   * misses, the LMF-backed {@link io.github.eschizoid.telescope.internal.Records#fieldLens
   * Records.fieldLens(name)} resolves the lens.
   */
  @Benchmark
  public void field_lmf(final Blackhole bh) {
    bh.consume(fieldLmf.update(plainRec, String::toUpperCase));
  }

  /**
   * Same dispatch shape against a {@link io.github.eschizoid.telescope.annotations.Focus
   * &#64;Focus}-annotated record — the probe hits and returns the pre-baked holder constant. This
   * is the {@code field_lmf} row's holder-routed counterpart.
   */
  @Benchmark
  public void field_holder(final Blackhole bh) {
    bh.consume(fieldHolder.update(holderRec, String::toUpperCase));
  }

  /**
   * The generated holder constant invoked directly — {@code BenchHolderRecTelescope.name.update(x,
   * fn)}. Skips the {@link io.github.eschizoid.telescope.internal.MetadataHolderProbe#probeFor
   * probe} entirely, so this row is the pure codegen-direct ceiling the {@code field_holder} row
   * can approach.
   */
  @Benchmark
  public void field_holder_constant(final Blackhole bh) {
    bh.consume(fieldHolderConstant.update(holderRec, String::toUpperCase));
  }

  // ---- Deep-mapping forward (Phase C — backward Iso branch on the source read) ---------------

  /**
   * Forward conversion via {@code Mapper.forward(a)} against an unannotated source/target pair — no
   * holder, so the LMF-backed {@link io.github.eschizoid.telescope.internal.Records#read} runs the
   * per-component reads inside {@link
   * io.github.eschizoid.telescope.internal.Reflective#structuralIso}'s backward branch.
   */
  @Benchmark
  public void mapForward_lmf(final Blackhole bh) {
    bh.consume(mapLmf.forward(plainSrc));
  }

  /**
   * Same forward conversion against a {@link io.github.eschizoid.telescope.annotations.Focus
   * &#64;Focus}-annotated source/target pair — the holder is found, so the source-side backward Iso
   * branch reads each component through the pre-baked holder lens constants.
   */
  @Benchmark
  public void mapForward_holder(final Blackhole bh) {
    bh.consume(mapHolder.forward(holderSrc));
  }

  // ---- Deep-mapping backward -----------------------------------------------------------------

  /**
   * Backward conversion via {@code Mapper.backward(b)} against an unannotated pair — same LMF
   * fallback as {@link #mapForward_lmf}, exercised on the opposite Iso direction.
   */
  @Benchmark
  public void mapBackward_lmf(final Blackhole bh) {
    bh.consume(mapLmf.backward(plainTgt));
  }

  /**
   * Same backward conversion against the annotated pair — holder-routed reads on the source-side
   * backward branch.
   */
  @Benchmark
  public void mapBackward_holder(final Blackhole bh) {
    bh.consume(mapHolder.backward(holderTgt));
  }
}
