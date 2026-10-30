package io.github.eschizoid.telescope.benchmarks;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * A mapper reading two objects down a path into a flat target, and writing the flat target back
 * through the path, against the same chain written by hand. The {@code hand*} rows never change,
 * so a run is normalised against them; the {@code mapper*} rows call the mapper {@link NpOrder}'s
 * {@code @Bridge} field holds, which is the generated bridge where the processor compiles the
 * path, and the runtime mapper where it does not.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=NestedPathBenchmark -Pjmh.profilers=gc
 * }</pre>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class NestedPathBenchmark {

  private NpOrder full;
  private NpOrder missing;
  private NpOrderDto flat;

  @Setup
  public void setup() {
    full = new NpOrder(new NpCustomer(new NpAddress("Paris", 75001), "ada"), 3);
    missing = new NpOrder(null, 3);
    flat = new NpOrderDto("Rome", 118, 2);
  }

  @Benchmark
  public NpOrderDto mapperRead() {
    return NpOrder.DTO.forward(full);
  }

  @Benchmark
  public NpOrderDto mapperReadNull() {
    return NpOrder.DTO.forward(missing);
  }

  @Benchmark
  public NpOrder mapperWrite() {
    return NpOrder.DTO.backward(flat);
  }

  @Benchmark
  public NpOrderDto handRead() {
    return read(full);
  }

  @Benchmark
  public NpOrderDto handReadNull() {
    return read(missing);
  }

  @Benchmark
  public NpOrder handWrite() {
    final var dto = flat;
    return new NpOrder(new NpCustomer(new NpAddress(dto.city(), dto.zip()), null), dto.qty());
  }

  private static NpOrderDto read(final NpOrder order) {
    final var customer = order.customer();
    final var address = customer == null ? null : customer.address();
    return new NpOrderDto(
      address == null ? null : address.city(),
      address == null ? 0 : address.zip(),
      order.qty()
    );
  }
}
