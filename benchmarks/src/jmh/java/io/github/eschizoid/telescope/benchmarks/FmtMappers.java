package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.toFormatted;
import static io.github.eschizoid.telescope.mapping.Mapping.toFormattedNumber;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/**
 * The generated mappers {@link FormattedRowBenchmark} declares on fields: one formatting a date and
 * an amount through a pattern each, and the control copying the same shape held as text.
 */
public final class FmtMappers {

  private FmtMappers() {}

  /** The date pattern every variant of the benchmark formats in. */
  public static final String DATE = "dd.MM.yyyy";

  /** The number pattern every variant of the benchmark formats in. */
  public static final String AMOUNT = "$#,##0.00";

  @Bridge
  public static final Mapper<FmtOrder, FmtOrderDto> DTO = mapper(
    FmtOrder.class,
    FmtOrderDto.class,
    toFormatted(FmtOrder::shipDate, FmtOrderDto::shipDate, DATE),
    toFormattedNumber(FmtOrder::total, FmtOrderDto::total, AMOUNT)
  );

  @Bridge
  public static final Mapper<FmtPlain, FmtOrderDto> PLAIN = mapper(FmtPlain.class, FmtOrderDto.class);
}
