package io.github.telescopeorganization.telescope.benchmarks;

import io.github.telescopeorganization.telescope.Telescope;
import io.github.telescopeorganization.telescope.conversion.Mapper;
import io.github.telescopeorganization.telescope.introspection.OpticNode;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Properties converted from text without a row: a {@code String} into a {@code long}, a {@code
 * BigDecimal}, an enum, a {@code boolean} and an {@code int}, and a list of {@code String}s into a
 * set of {@code Long}s, against the same conversion written by hand and MapStruct's own on the same
 * shape.
 *
 * <p>{@code converted_codegen} runs the bridge generated for the {@code @Bridge} field through the
 * field's mapper, and {@code converted_bridge} through the bridge's own {@code forward}; {@code
 * converted_runtime} runs the runtime mapper. {@code converted_handwritten} makes the JDK calls the
 * generated bridge makes, written out, which is what the bridge should cost, and {@code
 * converted_mapstruct} is MapStruct's mapper for the pair.
 *
 * <p>The {@code plain_*} rows copy a pair of the same six properties, each the same type on both
 * sides, which no built-in conversion touches. They are the controls: none of them moves between
 * runs on runners of the same speed, so a run whose controls moved is normalised against them.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class BuiltInConversionBenchmark {

  private BicOrder order;
  private BicPlain plain;
  private Mapper<BicOrder, BicOrderDto> runtime;

  @Setup
  public void setup() {
    order = new BicOrder("4711", "129.95", "SHIPPED", "true", "3", List.of("17", "4", "23", "4"));
    plain = new BicPlain("4711", "129.95", "SHIPPED", "true", "3", List.of("17", "4", "23", "4"));
    runtime = Telescope.mapper(BicOrder.class, BicOrderDto.class);
    if (
      BicMappers.DTO.explain().declared().map(OpticNode.Declared::runs).orElse(OpticNode.Runs.RUNTIME) !=
      OpticNode.Runs.GENERATED
    ) {
      throw new IllegalStateException(BicMappers.DTO.explain().toString());
    }
    final var expected = converted_handwritten();
    for (final var produced : List.of(converted_codegen(), converted_runtime(), converted_mapstruct())) {
      if (!expected.equals(produced)) throw new IllegalStateException(produced + " differs from " + expected);
    }
  }

  @Benchmark
  public BicPlainDto plain_handwritten() {
    final var p = plain;
    return new BicPlainDto(p.id(), p.total(), p.status(), p.paid(), p.quantity(), List.copyOf(p.tags()));
  }

  @Benchmark
  public BicPlainDto plain_codegen() {
    return BicMappers.PLAIN.forward(plain);
  }

  @Benchmark
  public BicPlainDto plain_mapstruct() {
    return BicMapStruct.INSTANCE.toPlain(plain);
  }

  @Benchmark
  public BicOrderDto converted_handwritten() {
    final var o = order;
    final var id = o.id();
    final var total = o.total();
    final var status = o.status();
    final var paid = o.paid();
    final var quantity = o.quantity();
    final var tags = o.tags();
    LinkedHashSet<Long> tagSet = null;
    if (tags != null) {
      tagSet = LinkedHashSet.newLinkedHashSet(tags.size());
      for (final var tag : tags) tagSet.add(tag == null ? null : Long.parseLong(tag));
    }
    return new BicOrderDto(
      id == null ? 0L : Long.parseLong(id),
      total == null ? null : new BigDecimal(total),
      status == null ? null : BicStatus.valueOf(status),
      paid != null && paid.equalsIgnoreCase("true"),
      quantity == null ? 0 : Integer.parseInt(quantity),
      tagSet
    );
  }

  @Benchmark
  public BicOrderDto converted_codegen() {
    return BicMappers.DTO.forward(order);
  }

  @Benchmark
  public BicOrderDto converted_bridge() {
    return BicOrderToBicOrderDtoBridge.forward(order);
  }

  @Benchmark
  public BicOrderDto converted_mapstruct() {
    return BicMapStruct.INSTANCE.toDto(order);
  }

  @Benchmark
  public BicOrderDto converted_runtime() {
    return runtime.forward(order);
  }
}
