package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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
 * SPIKE (not a permanent benchmark): does a MethodHandle-combinator-composed {@code (Src) -> Dst}
 * forward — no {@code Object[]} intermediate, no boxing for primitive fields — beat the current
 * array-based reflective {@code Telescope.mapper(...).forward(...)} path, and how close does it get
 * to a hand-written constructor call (the MapStruct-equivalent unboxed ceiling)?
 *
 * <p>The fixture is a flat 5-field record pair with mixed primitives + a reference type, so boxing
 * is actually exercised. Three paths convert the SAME {@code Src} to a {@code Dst}:
 *
 * <ul>
 *   <li>{@code handWritten} — direct {@code new Dst(src.a(), ...)}; the unboxed ceiling.
 *   <li>{@code mhChain} — {@code filterArguments}(raw primitive accessors into raw ctor) {@code ->
 *       permuteArguments -> } one {@code (Src) -> Dst} handle, {@code invokeExact} through a {@code
 *       final} field. No array, no boxing.
 *   <li>{@code currentMapper} — {@code Telescope.mapper(...).forward(...)}; the {@code Object[]} +
 *       boxing baseline.
 * </ul>
 *
 * <p>If {@code mhChain} lands near {@code handWritten} and well below {@code currentMapper}, the
 * lattice-legal structural win (a MH-combinator leaf {@code Iso}) is real. If {@code mhChain} tracks
 * {@code currentMapper}, the runtime floor is confirmed and hot loops belong on {@code @Bridge}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class MethodHandleChainSpikeBenchmark {

  public record Src(int a, long b, String c, double d, boolean e) {}

  public record Dst(int a, long b, String c, double d, boolean e) {}

  private Src src;
  private Mapper<Src, Dst> currentMapper;
  private MethodHandle mhChain; // (Src) -> Dst, fully composed, invokeExact through final field
  private Function<Src, Dst> mhFn; // the same handle behind one SAM hop — production-representative

  @Setup
  public void setup() throws Throwable {
    src = new Src(7, 42L, "telescope", 3.14, true);
    currentMapper = Telescope.mapper(Src.class, Dst.class);

    final MethodHandles.Lookup lk = MethodHandles.lookup();

    // Raw, primitive-typed constructor handle: (int, long, String, double, boolean) -> Dst.
    final MethodHandle ctor =
      lk.unreflectConstructor(Dst.class.getDeclaredConstructor(int.class, long.class, String.class, double.class, boolean.class));

    // Raw, primitive-typed component accessors: each (Src) -> Ti.
    final MethodHandle a = lk.unreflect(Src.class.getMethod("a")); // (Src) -> int
    final MethodHandle b = lk.unreflect(Src.class.getMethod("b")); // (Src) -> long
    final MethodHandle c = lk.unreflect(Src.class.getMethod("c")); // (Src) -> String
    final MethodHandle d = lk.unreflect(Src.class.getMethod("d")); // (Src) -> double
    final MethodHandle e = lk.unreflect(Src.class.getMethod("e")); // (Src) -> boolean

    // Feed each constructor argument through its accessor: (Src, Src, Src, Src, Src) -> Dst.
    final MethodHandle filtered = MethodHandles.filterArguments(ctor, 0, a, b, c, d, e);

    // Collapse the five Src slots to a single input: (Src) -> Dst. All primitive-typed end to end —
    // no Object[], no boxing.
    mhChain = MethodHandles.permuteArguments(filtered, MethodType.methodType(Dst.class, Src.class), 0, 0, 0, 0, 0);

    // Same handle, but reached through one Function SAM hop — approximates what a real leaf Iso.of
    // forward would pay (Iso.to -> captured forward.apply). A productionized MH-combinator leaf Iso
    // lands between mhChain (bare) and currentMapper.
    final MethodHandle h = mhChain;
    mhFn =
      s -> {
        try {
          return (Dst) h.invokeExact(s);
        } catch (final Throwable t) {
          throw new RuntimeException(t);
        }
      };
  }

  @Benchmark
  public void handWritten(final Blackhole bh) {
    bh.consume(new Dst(src.a(), src.b(), src.c(), src.d(), src.e()));
  }

  @Benchmark
  public void mhChain(final Blackhole bh) throws Throwable {
    bh.consume((Dst) mhChain.invokeExact(src));
  }

  @Benchmark
  public void mhChainViaFunction(final Blackhole bh) {
    bh.consume(mhFn.apply(src));
  }

  @Benchmark
  public void currentMapper(final Blackhole bh) {
    bh.consume(currentMapper.forward(src));
  }
}
