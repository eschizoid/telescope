package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.conversion.Match;
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
 * Positioning benchmark for {@code Match} sealed dispatch: the natural baseline is free — a Java
 * pattern-matching {@code switch} over the same sealed hierarchy. {@code Match} buys build-time
 * exhaustiveness verification and a first-class dispatch value; this measures what that costs per
 * call so the docs can say honestly where it belongs (a wiring/ergonomics API, or fine in a loop).
 *
 * <p>The mixed-instance array forces a megamorphic call site — the realistic worst case for both
 * sides. Read the ratio, not the absolutes.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=MatchDispatchBenchmark -Pjmh.fork=3   # fork >= 3 for gating reads
 * }</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class MatchDispatchBenchmark {

  public sealed interface Shape permits Circle, Square, Triangle {}

  public record Circle(double radius) implements Shape {}

  public record Square(double side) implements Shape {}

  public record Triangle(double base, double height) implements Shape {}

  private Shape[] shapes;
  private Function<Shape, Double> match;

  @Setup
  public void setup() {
    shapes = new Shape[96];
    for (var i = 0; i < shapes.length; i += 3) {
      shapes[i] = new Circle(i + 1.0);
      shapes[i + 1] = new Square(i + 2.0);
      shapes[i + 2] = new Triangle(i + 3.0, 2.0);
    }
    match = Match.<Shape, Double>of(Shape.class)
      .when(Circle.class, c -> c.radius() * c.radius() * Math.PI)
      .when(Square.class, s -> s.side() * s.side())
      .when(Triangle.class, t -> (t.base() * t.height()) / 2.0)
      .exhaustive();
  }

  @Benchmark
  public void matchDispatch(final Blackhole bh) {
    for (final var s : shapes) {
      bh.consume(match.apply(s));
    }
  }

  /** The free baseline: a pattern-matching switch over the same permits. */
  @Benchmark
  public void switchDispatch(final Blackhole bh) {
    for (final var s : shapes) {
      final double area = switch (s) {
        case Circle c -> c.radius() * c.radius() * Math.PI;
        case Square sq -> sq.side() * sq.side();
        case Triangle t -> (t.base() * t.height()) / 2.0;
      };
      bh.consume(area);
    }
  }
}
