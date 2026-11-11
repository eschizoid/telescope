package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.mapping.Mapping.toFormatted;
import static io.github.eschizoid.telescope.mapping.Mapping.toFormattedNumber;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.introspection.OpticNode;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.ParsePosition;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
 * A date and an amount written as text through a pattern each, and read back, by the formatted rows
 * against the same formatting written by hand and MapStruct's {@code dateFormat} and {@code
 * numberFormat} on the same pair.
 *
 * <p>{@code format_codegen} and {@code parse_codegen} run the bridge generated for the
 * {@code @Bridge} field through the field's mapper, {@code format_bridge} through the bridge's own
 * {@code forward}, and the {@code _runtime} rows the runtime mapper built from the same rows. The
 * {@code _handwritten} rows make the JDK calls the generated bridge makes, written out: a {@code
 * static final DateTimeFormatter}, and a copy of one {@code DecimalFormat} per call, which is what
 * the bridge should cost.
 *
 * <p>The {@code number_*} rows format one amount through each way of keeping a {@code
 * DecimalFormat}, which is not safe to share between threads, off one thread: a new instance per
 * call, a copy of a prototype per call, which the rows use, and an instance held per thread. Their
 * allocation is the measure that decides between them.
 *
 * <p>The {@code plain_*} rows copy the same three properties already held as text, which no
 * formatted row touches. They are the controls: neither moves between runs on runners of the same
 * speed, so a run whose controls moved is normalised against them.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class FormattedRowBenchmark {

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern(FmtMappers.DATE);

  private static final DecimalFormat AMOUNT = amount();

  private static final ThreadLocal<DecimalFormat> AMOUNT_PER_THREAD = ThreadLocal.withInitial(
    FormattedRowBenchmark::amount
  );

  private static DecimalFormat amount() {
    final var format = new DecimalFormat(FmtMappers.AMOUNT);
    format.setParseBigDecimal(true);
    return format;
  }

  private FmtOrder order;
  private FmtOrderDto text;
  private FmtPlain plain;
  private Mapper<FmtOrder, FmtOrderDto> runtime;

  @Setup
  public void setup() {
    order = new FmtOrder("4711", LocalDate.of(2026, 12, 31), new BigDecimal("1234.5"));
    text = new FmtOrderDto("4711", "31.12.2026", "$1,234.50");
    plain = new FmtPlain("4711", "31.12.2026", "$1,234.50");
    runtime = Telescope.mapper(
      FmtOrder.class,
      FmtOrderDto.class,
      toFormatted(FmtOrder::shipDate, FmtOrderDto::shipDate, FmtMappers.DATE),
      toFormattedNumber(FmtOrder::total, FmtOrderDto::total, FmtMappers.AMOUNT)
    );
    for (final var mapper : List.of(FmtMappers.DTO, FmtMappers.PLAIN)) {
      final var runs = mapper.explain().declared().map(OpticNode.Declared::runs).orElse(OpticNode.Runs.RUNTIME);
      if (runs != OpticNode.Runs.GENERATED) throw new IllegalStateException(mapper.explain().toString());
    }
    final var formatted = format_handwritten();
    for (final var produced : List.of(format_codegen(), format_bridge(), format_runtime(), format_mapstruct())) {
      if (!formatted.equals(produced)) throw new IllegalStateException(produced + " differs from " + formatted);
    }
    final var parsed = parse_handwritten();
    for (final var produced : List.of(parse_codegen(), parse_runtime(), parse_mapstruct())) {
      if (!parsed.equals(produced)) throw new IllegalStateException(produced + " differs from " + parsed);
    }
    for (final var produced : List.of(number_new(), number_threadlocal())) {
      if (!number_clone().equals(produced)) throw new IllegalStateException(produced + " differs");
    }
  }

  @Benchmark
  public FmtOrderDto plain_handwritten() {
    final var p = plain;
    return new FmtOrderDto(p.id(), p.shipDate(), p.total());
  }

  @Benchmark
  public FmtOrderDto plain_codegen() {
    return FmtMappers.PLAIN.forward(plain);
  }

  @Benchmark
  public FmtOrderDto format_handwritten() {
    final var o = order;
    final var shipDate = o.shipDate();
    final var total = o.total();
    return new FmtOrderDto(
      o.id(),
      shipDate == null ? null : DATE.format(shipDate),
      total == null ? null : ((DecimalFormat) AMOUNT.clone()).format(total)
    );
  }

  @Benchmark
  public FmtOrderDto format_codegen() {
    return FmtMappers.DTO.forward(order);
  }

  @Benchmark
  public FmtOrderDto format_bridge() {
    return FmtOrderToFmtOrderDtoBridge.forward(order);
  }

  @Benchmark
  public FmtOrderDto format_runtime() {
    return runtime.forward(order);
  }

  @Benchmark
  public FmtOrderDto format_mapstruct() {
    return FmtMapStruct.INSTANCE.toDto(order);
  }

  @Benchmark
  public FmtOrder parse_handwritten() {
    final var t = text;
    final var shipDate = t.shipDate();
    final var total = t.total();
    BigDecimal amount = null;
    if (total != null) {
      final var position = new ParsePosition(0);
      final var number = ((DecimalFormat) AMOUNT.clone()).parse(total, position);
      if (!(number instanceof BigDecimal exact) || position.getIndex() != total.length()) {
        throw new IllegalArgumentException(total);
      }
      amount = exact;
    }
    return new FmtOrder(t.id(), shipDate == null ? null : DATE.parse(shipDate, LocalDate::from), amount);
  }

  @Benchmark
  public FmtOrder parse_codegen() {
    return FmtMappers.DTO.backward(text);
  }

  @Benchmark
  public FmtOrder parse_runtime() {
    return runtime.backward(text);
  }

  @Benchmark
  public FmtOrder parse_mapstruct() {
    return FmtMapStruct.INSTANCE.fromDto(text);
  }

  @Benchmark
  public String number_new() {
    return new DecimalFormat(FmtMappers.AMOUNT).format(order.total());
  }

  @Benchmark
  public String number_clone() {
    return ((DecimalFormat) AMOUNT.clone()).format(order.total());
  }

  @Benchmark
  public String number_threadlocal() {
    return AMOUNT_PER_THREAD.get().format(order.total());
  }
}
