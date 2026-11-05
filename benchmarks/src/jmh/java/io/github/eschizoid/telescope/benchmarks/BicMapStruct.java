package io.github.eschizoid.telescope.benchmarks;

import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * MapStruct's mappers for {@link BuiltInConversionBenchmark}'s two pairs, by its own conversions.
 */
@Mapper
public interface BicMapStruct {
  BicMapStruct INSTANCE = Mappers.getMapper(BicMapStruct.class);

  BicOrderDto toDto(BicOrder source);

  BicPlainDto toPlain(BicPlain source);
}
