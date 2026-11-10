package io.github.telescopeorganization.telescope.benchmarks;

import static io.github.telescopeorganization.telescope.Telescope.mapper;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;

/**
 * The generated mappers {@link BuiltInConversionBenchmark} declares on fields: one converting every
 * property through a built-in conversion, and the control over the same shape converting none.
 */
public final class BicMappers {

  private BicMappers() {}

  @Bridge
  public static final Mapper<BicOrder, BicOrderDto> DTO = mapper(BicOrder.class, BicOrderDto.class);

  @Bridge
  public static final Mapper<BicPlain, BicPlainDto> PLAIN = mapper(BicPlain.class, BicPlainDto.class);
}
