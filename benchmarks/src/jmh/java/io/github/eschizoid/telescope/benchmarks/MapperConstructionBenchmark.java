package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/**
 * Measures what every other conversion benchmark hides in {@code @Setup}: the cost of BUILDING a
 * mapper. {@code Telescope.mapper(A, B)} walks the pair tree, runs pairing decisions, and composes
 * the MethodHandle conversion leaf per type pair — and the per-call {@code TypePair → Iso} cache is
 * local to one factory invocation, so a caller constructing in a request path pays all of it every
 * time.
 *
 * <p>Two shapes: the flat 5-field pair and the deep 3-level container tree (the same domains the
 * MapStruct comparison uses). Read the number against the forward call it amortizes — a
 * construction cost of N µs versus a ~tens-of-ns forward means the break-even is N×30-ish calls,
 * which is the "build once, hold it in a static" guidance quantified. This benchmark is a
 * measurement, not a target: whether to memoize sub-Isos globally is a separate decision that
 * starts from these numbers.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=MapperConstructionBenchmark
 * }</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class MapperConstructionBenchmark {

  @Benchmark
  public Mapper<McFlatRec, McFlatBean> constructFlat() {
    return Telescope.mapper(McFlatRec.class, McFlatBean.class);
  }

  @Benchmark
  public Mapper<McCompanyRec, McCompanyBean> constructDeep() {
    return Telescope.mapper(McCompanyRec.class, McCompanyBean.class);
  }
}
