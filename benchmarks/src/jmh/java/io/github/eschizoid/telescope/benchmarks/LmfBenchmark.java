package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.Records;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH micro-benchmarks pinning the LambdaMetafactory (LMF) substrate against its hand-written
 * baseline (and the reflection path it replaces) for the three runtime hot paths that have been
 * swapped to LMF so far:
 *
 * <ul>
 *   <li><b>Phase 1 — record component readers.</b> {@link Records#read(Object, String)} dispatches
 *       through a cached {@code Function<Object, Object>} built once per record class via {@link
 *       java.lang.invoke.LambdaMetafactory}.
 *   <li><b>Phase 2 — bean getter readers.</b> {@link Beans#readProperty(Object, String)} dispatches
 *       through a cached {@code Function<Object, Object>} built once per {@code (beanClass,
 *       property)} via LMF.
 *   <li><b>Phase 3 — bean setter dispatch.</b> {@link Beans#settersWriter(Class)} returns a writer
 *       whose {@code construct(...)} routes each {@code setX(...)} call through a cached {@code
 *       BiConsumer<Object, Object>} built once per {@code (beanClass, property)} via LMF.
 * </ul>
 *
 * <p>Each LMF benchmark has a matching hand-rolled baseline that calls the accessor / setter
 * directly — a Java field read or method call — so the ratio between the two pins the residual
 * dispatch overhead the synthetic {@code Function} / {@code BiConsumer} adds on top of an inlined
 * accessor.
 *
 * <p>This benchmark complements {@link TelescopeBenchmark} (which times the full deep-copy DSL hot
 * paths through composed lenses); this file isolates the single-step dispatch for the LMF tier
 * specifically.
 *
 * <p>Run with:
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh
 * }</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class LmfBenchmark {

  // ---- Fixtures -------------------------------------------------------------------------------

  /** Tiny record for the Phase 1 LMF reader benchmark. */
  public record BenchRecord(String id, String name, int age) {}

  /** Tiny POJO for the Phase 2 / Phase 3 LMF benchmarks. */
  public static final class BenchPojo {

    private String id;
    private String name;
    private int age;

    public String getId() {
      return id;
    }

    public String getName() {
      return name;
    }

    public int getAge() {
      return age;
    }

    public void setId(final String id) {
      this.id = id;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public void setAge(final int age) {
      this.age = age;
    }
  }

  /**
   * Eight-component record for the megamorphic dispatch rows. Each accessor (v0..v7) yields a
   * distinct LMF-built {@code Function} synthetic class, so cycling them through one {@code
   * apply()} call site drives that site megamorphic — the shape a deep-copy traversal over many
   * distinct lens types actually produces, vs the single-class monomorphic best case the rows above
   * measure.
   */
  public record Wide(String v0, String v1, String v2, String v3, String v4, String v5, String v6, String v7) {}

  private static final int FANOUT = 8;

  private BenchRecord record;
  private BenchPojo pojo;

  // Pre-resolved SettersWriter so the per-call benchmark only times construct() — writer lookup
  // and per-setter LMF BiConsumer build happen once in @Setup and then live in the writer's
  // ConcurrentHashMap cache; subsequent calls go straight to the cached BiConsumer.
  private Beans.BeanWriter<BenchPojo> settersWriter;

  // Cached, setAccessible-warmed reflective members used by the *_methodInvoke baselines below.
  // These reproduce the pre-LMF runtime path — `Method.invoke` / `RecordComponent.getAccessor`
  // dispatch on every call, no synthetic SAM — so the delta vs the LMF rows isolates exactly what
  // the LMF substrate swap (Phases 1-3) removed.
  private Method recordNameAccessor;
  private Method beanGetName;
  private Method beanSetName;
  private static final Object[] EMPTY_ARGS = new Object[0];
  private static final Object[] SINGLE_NAME_ARG = new Object[] { "updated" };

  // A single-key array + value lookup used by the LMF setter benchmark — keeps the per-call
  // allocation footprint identical to the hand-rolled baseline (a new BenchPojo + one setter
  // call) so the comparison isolates dispatch cost.
  private static final String[] NAME_ONLY = new String[] { "name" };
  private static final Function<String, Object> NAME_LOOKUP = n -> "updated";

  // Megamorphic dispatch fixtures: FANOUT distinct captured Functions (one per Wide accessor — each
  // a distinct LMF synthetic class) and the matching pre-resolved Methods, all reading the same
  // Wide
  // instance. "Captured" means resolved once in @Setup — no per-call cache lookup — so these
  // isolate
  // pure dispatch (unlike the *_lmf rows above, which go through Records.read / Beans.readProperty
  // and pay a per-call string->Function map lookup the *_methodInvoke rows don't).
  private Function<Object, Object>[] megaFns;
  private Method[] megaMethods;
  private Object megaTarget;
  private int idx;

  @SuppressWarnings("unchecked")
  @Setup
  public void setup() throws Exception {
    record = new BenchRecord("u1", "Alice", 30);
    pojo = new BenchPojo();
    pojo.setId("u1");
    pojo.setName("Alice");
    pojo.setAge(30);

    settersWriter = Beans.settersWriter(BenchPojo.class);
    // Warm the ConcurrentHashMap-backed BiConsumer cache for "name" so the first measured call
    // doesn't pay the one-shot build cost.
    settersWriter.construct(NAME_ONLY, NAME_LOOKUP);

    // Resolve the reflective members for the *_methodInvoke baselines. The same setAccessible
    // warm-up cost is paid once here; per-call cost is just Method.invoke (no per-call
    // access-check, identical to what the pre-LMF Beans / Records paths paid).
    recordNameAccessor = BenchRecord.class.getRecordComponents()[1].getAccessor();
    recordNameAccessor.setAccessible(true);
    beanGetName = BenchPojo.class.getMethod("getName");
    beanGetName.setAccessible(true);
    beanSetName = BenchPojo.class.getMethod("setName", String.class);
    beanSetName.setAccessible(true);

    // Build FANOUT distinct captured Functions (one per Wide accessor) + matching Methods.
    final var lookup = MethodHandles.lookup();
    final var wide = new Wide("0", "1", "2", "3", "4", "5", "6", "7");
    final var comps = Wide.class.getRecordComponents();
    megaTarget = wide;
    megaFns = new Function[FANOUT];
    megaMethods = new Method[FANOUT];
    for (int i = 0; i < FANOUT; i++) {
      final var accessor = comps[i].getAccessor();
      accessor.setAccessible(true);
      megaMethods[i] = accessor;
      megaFns[i] = buildAccessorFunction(lookup, accessor);
    }
  }

  // Build a captured Function<Object, Object> over a record accessor via LambdaMetafactory — the
  // same dispatch primitive Beans/Records cache internally, materialised here so the megamorphic
  // benchmark can hold N distinct synthetic classes and cycle them through one call site.
  @SuppressWarnings("unchecked")
  private static Function<Object, Object> buildAccessorFunction(
    final MethodHandles.Lookup lookup,
    final Method accessor
  ) {
    try {
      final var handle = lookup.unreflect(accessor);
      final var callSite = LambdaMetafactory.metafactory(
        lookup,
        "apply",
        MethodType.methodType(Function.class),
        MethodType.methodType(Object.class, Object.class),
        handle,
        MethodType.methodType(accessor.getReturnType(), accessor.getDeclaringClass())
      );
      return (Function<Object, Object>) callSite.getTarget().invoke();
    } catch (final Throwable t) {
      throw new RuntimeException("Failed to build accessor Function for " + accessor, t);
    }
  }

  // ---- Phase 1 — record component LMF reader vs hand-rolled accessor --------------------------

  /**
   * {@link Records#read(Object, String)} — Phase 1 LMF reader hot path. The cached {@code
   * Function<Object, Object>} for {@code BenchRecord::name} is invoked directly; no {@link
   * java.lang.reflect.Method#invoke}, no per-call argument array.
   */
  @Benchmark
  public void recordComponentRead_lmf(final Blackhole bh) {
    bh.consume(Records.read(record, "name"));
  }

  /** Hand-rolled baseline: direct record accessor call. */
  @Benchmark
  public void recordComponentRead_handRolled(final Blackhole bh) {
    bh.consume(record.name());
  }

  /**
   * Pre-LMF baseline: cached {@link java.lang.reflect.Method#invoke Method.invoke} on the record
   * component accessor (already {@code setAccessible(true)}'d in {@code @Setup}). Reproduces the
   * exact dispatch shape the Phase 1 LMF reader replaced — per-call argument-array allocation, the
   * full reflective machinery. The delta vs {@code recordComponentRead_lmf} is what the LMF
   * substrate swap bought.
   */
  @Benchmark
  public void recordComponentRead_methodInvoke(final Blackhole bh) throws Exception {
    bh.consume(recordNameAccessor.invoke(record, EMPTY_ARGS));
  }

  // ---- Phase 2 — bean getter LMF reader vs hand-rolled accessor -------------------------------

  /**
   * {@link Beans#readProperty(Object, String)} — Phase 2 LMF getter hot path. The cached {@code
   * Function<Object, Object>} for {@code BenchPojo::getName} is invoked directly through the
   * GETTER_INVOKERS ClassValue cache.
   */
  @Benchmark
  public void beanGetterRead_lmf(final Blackhole bh) {
    bh.consume(Beans.readProperty(pojo, "name"));
  }

  /** Hand-rolled baseline: direct getter call. */
  @Benchmark
  public void beanGetterRead_handRolled(final Blackhole bh) {
    bh.consume(pojo.getName());
  }

  /**
   * Pre-LMF baseline: cached {@link java.lang.reflect.Method#invoke Method.invoke} on the bean
   * getter. Reproduces the dispatch shape the Phase 2 LMF reader replaced; the delta vs {@code
   * beanGetterRead_lmf} is what {@link Beans#readProperty(Object, String)} no longer pays.
   */
  @Benchmark
  public void beanGetterRead_methodInvoke(final Blackhole bh) throws Exception {
    bh.consume(beanGetName.invoke(pojo, EMPTY_ARGS));
  }

  // ---- Phase 3 — bean setter LMF dispatch vs hand-rolled setter -------------------------------

  /**
   * {@link Beans#settersWriter(Class)} {@code .construct(...)} with a single-property input — Phase
   * 3 LMF setter dispatch hot path. The writer instantiates a fresh {@link BenchPojo} via its
   * cached no-arg ctor and routes the one {@code setName(...)} call through the cached {@code
   * BiConsumer<Object, Object>} built once via LMF in {@code @Setup}'s warmup call.
   *
   * <p>Includes the no-arg ctor invocation per call (so the result is constructable in isolation) —
   * the hand-rolled baseline below pays the same allocation, so the delta is the LMF dispatch
   * overhead alone.
   */
  @Benchmark
  public void beanSetterDispatch_lmf(final Blackhole bh) {
    bh.consume(settersWriter.construct(NAME_ONLY, NAME_LOOKUP));
  }

  /** Hand-rolled baseline: {@code new BenchPojo()} + direct setter call. */
  @Benchmark
  public void beanSetterDispatch_handRolled(final Blackhole bh) {
    final var p = new BenchPojo();
    p.setName("updated");
    bh.consume(p);
  }

  /**
   * Pre-LMF baseline: {@code new BenchPojo()} + cached {@link java.lang.reflect.Method#invoke
   * Method.invoke} on the setter. Reproduces the dispatch shape the Phase 3 LMF setter swap
   * replaced — per-call argument-array allocation, full reflective machinery. The delta vs {@code
   * beanSetterDispatch_lmf} is what the Phase 3 LMF substrate bought.
   */
  @Benchmark
  public void beanSetterDispatch_methodInvoke(final Blackhole bh) throws Exception {
    final var p = new BenchPojo();
    beanSetName.invoke(p, SINGLE_NAME_ARG);
    bh.consume(p);
  }

  // ---- Captured dispatch: monomorphic vs megamorphic, LMF vs Method.invoke ----------------------
  // The rows above conflate the per-call cache lookup with dispatch, and only ever hit one
  // synthetic
  // class (monomorphic — the JIT's best case). These resolve the accessor once (as the optics do)
  // and isolate the call-site shape: index 0 fixed (monomorphic) vs cycling FANOUT distinct classes
  // through one site (megamorphic). The megamorphic LMF row is the closest proxy for a deep-copy
  // traversal dispatching many distinct lens Functions through a shared apply() site.

  /**
   * Captured LMF Function, monomorphic call site (always class 0) — the fair dispatch-only LMF
   * read.
   */
  @Benchmark
  public void capturedRead_lmf_monomorphic(final Blackhole bh) {
    bh.consume(megaFns[0].apply(megaTarget));
  }

  /** Captured LMF Functions, megamorphic call site (FANOUT distinct synthetic classes cycled). */
  @Benchmark
  public void capturedRead_lmf_megamorphic(final Blackhole bh) {
    idx = (idx + 1) & (FANOUT - 1);
    bh.consume(megaFns[idx].apply(megaTarget));
  }

  /** Pre-resolved Method.invoke, monomorphic — fair dispatch-only reflection baseline. */
  @Benchmark
  public void capturedRead_methodInvoke_monomorphic(final Blackhole bh) throws Exception {
    bh.consume(megaMethods[0].invoke(megaTarget, EMPTY_ARGS));
  }

  /**
   * Pre-resolved Method.invoke, megamorphic — FANOUT distinct Methods cycled through one invoke
   * site.
   */
  @Benchmark
  public void capturedRead_methodInvoke_megamorphic(final Blackhole bh) throws Exception {
    idx = (idx + 1) & (FANOUT - 1);
    bh.consume(megaMethods[idx].invoke(megaTarget, EMPTY_ARGS));
  }
}
