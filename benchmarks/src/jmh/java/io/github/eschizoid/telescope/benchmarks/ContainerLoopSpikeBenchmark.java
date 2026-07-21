package io.github.eschizoid.telescope.benchmarks;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
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
 * REGRESSION GUARD (originally the decision spike for the container-element MethodHandle loop —
 * that decision shipped: the runtime container path now loops the raw element handle, the shape the
 * {@code mhIteratedLoop} / {@code fnLoop} rows model). The row names keep their original spike
 * labels for continuity; read them for what they are today:
 *
 * <ul>
 *   <li>{@code handWritten} — Java for-loop, direct {@code new Dst(...)}. The MapStruct ceiling.
 *   <li>{@code liftListIso} — the PRE-campaign shape (Iso.to -> Function.apply -> invokeExact, two
 *       virtual hops per element), kept as the reference point the shipped path must stay below.
 *   <li>{@code mhIteratedLoop} — one {@code iteratedLoop} handle invoking the raw element handle
 *       directly; the shipped path's ceiling variant.
 *   <li>{@code fnLoop} — the raw handle behind a single {@code Function} hop; closest model of the
 *       shipped loop body.
 * </ul>
 *
 * <p>Fixture is the deep benchmark's inner hop: {@code List<Team>} of 3 elements, Team = {@code
 * (String name, int headcount)}, a record-to-record leaf. The regression signal: {@code fnLoop} /
 * {@code mhIteratedLoop} drifting up toward {@code liftListIso} means the shipped container loop
 * regained a dispatch layer.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ContainerLoopSpikeBenchmark {

  public record Src(String name, int headcount) {}

  public record Dst(String name, int headcount) {}

  private List<Src> input;

  // Raw element handle: (Object)->Object == (Src)->Dst, erased. Same shape MhIso.compose emits.
  private MethodHandle rawElem;
  // The current path's element Iso, modeled as a (Object)->Object Function behind an Iso.to shim.
  private ElementIso elementIso;
  // Raw element as a single Function (isolates the Function.apply layer).
  private Function<Object, Object> elemFn;
  // The whole List->List conversion as one iteratedLoop handle.
  private MethodHandle listLoop;

  /** Minimal stand-in for Iso: to() dispatches through a stored Function (two virtual hops). */
  interface ElementIso {
    Object to(Object x);
  }

  @Setup
  public void setup() throws Throwable {
    input = new ArrayList<>(3);
    input.add(new Src("alpha", 4));
    input.add(new Src("beta", 7));
    input.add(new Src("gamma", 2));

    final MethodHandles.Lookup lk = MethodHandles.lookup();

    final MethodHandle ctor = lk.findConstructor(Dst.class, MethodType.methodType(void.class, String.class, int.class));
    final MethodHandle nameAcc = lk.findVirtual(Src.class, "name", MethodType.methodType(String.class));
    final MethodHandle hcAcc = lk.findVirtual(Src.class, "headcount", MethodType.methodType(int.class));
    // (Src, Src) -> Dst  then permute to a single Src input: (Src) -> Dst.
    final MethodHandle filtered = MethodHandles.filterArguments(ctor, 0, nameAcc, hcAcc);
    final MethodHandle elem = MethodHandles.permuteArguments(
      filtered,
      MethodType.methodType(Dst.class, Src.class),
      0,
      0
    );
    rawElem = elem.asType(MethodType.methodType(Object.class, Object.class));

    final MethodHandle raw = rawElem;
    elemFn = x -> {
      try {
        return raw.invokeExact(x);
      } catch (final Throwable t) {
        throw new RuntimeException(t);
      }
    };
    final Function<Object, Object> fn = elemFn;
    elementIso = fn::apply;

    listLoop = buildListLoop(rawElem);
  }

  /**
   * Build {@code (List)->List} as one {@code iteratedLoop}: init = new ArrayList, body = {@code
   * acc.add(elem(x)); return acc}, default iteration over the sole external List argument.
   */
  private static MethodHandle buildListLoop(final MethodHandle elem) throws Throwable {
    final MethodHandles.Lookup lk = MethodHandles.lookup();

    // init: (List) -> ArrayList  == new ArrayList()
    final MethodHandle newList = lk
      .findConstructor(ArrayList.class, MethodType.methodType(void.class))
      .asType(MethodType.methodType(ArrayList.class));
    final MethodHandle init = MethodHandles.dropArguments(newList, 0, List.class);

    // add: (ArrayList, Object) -> boolean  (List.add via ArrayList)
    final MethodHandle add = lk
      .findVirtual(ArrayList.class, "add", MethodType.methodType(boolean.class, Object.class))
      .asType(MethodType.methodType(boolean.class, ArrayList.class, Object.class));
    // filter the element through the raw handle: (ArrayList, Object) -> boolean
    final MethodHandle mapped = MethodHandles.filterArguments(add, 1, elem);
    // drop the boolean result -> void: (ArrayList, Object) -> void
    final MethodHandle addVoid = mapped.asType(mapped.type().changeReturnType(void.class));
    // return the accumulator: fold the void add before an identity that yields arg0.
    final MethodHandle retAcc = MethodHandles.dropArguments(MethodHandles.identity(ArrayList.class), 1, Object.class);
    final MethodHandle body2 = MethodHandles.foldArguments(retAcc, addVoid); // (ArrayList, Object) -> ArrayList
    // iteratedLoop body wants (V, T, A...) -> V ; append the external List arg.
    final MethodHandle body = MethodHandles.dropArguments(body2, 2, List.class); // (ArrayList, Object, List) -> ArrayList

    // iterator = null -> default iterates the sole external argument (the List).
    final MethodHandle loop = MethodHandles.iteratedLoop(null, init, body); // (List) -> ArrayList
    return loop.asType(MethodType.methodType(List.class, List.class));
  }

  @Benchmark
  public void handWritten(final Blackhole bh) {
    final List<Dst> out = new ArrayList<>(input.size());
    for (final Src s : input) out.add(new Dst(s.name(), s.headcount()));
    bh.consume(out);
  }

  @Benchmark
  public void liftListIso(final Blackhole bh) {
    final List<Object> out = new ArrayList<>(input.size());
    for (final Src s : input) out.add(elementIso.to(s));
    bh.consume(out);
  }

  @Benchmark
  public void fnLoop(final Blackhole bh) {
    final List<Object> out = new ArrayList<>(input.size());
    for (final Src s : input) out.add(elemFn.apply(s));
    bh.consume(out);
  }

  @Benchmark
  public void mhIteratedLoop(final Blackhole bh) throws Throwable {
    final List<?> out = (List<?>) listLoop.invokeExact((List<Src>) input);
    bh.consume(out);
  }
}
