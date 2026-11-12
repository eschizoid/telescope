package io.github.eschizoid.telescope.benchmarks;

import org.mapstruct.InheritInverseConfiguration;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/** MapStruct's mapper for {@link FormattedRowBenchmark}'s pair, by its own format attributes. */
@Mapper
public interface FmtMapStruct {
  FmtMapStruct INSTANCE = Mappers.getMapper(FmtMapStruct.class);

  @Mapping(target = "shipDate", dateFormat = FmtMappers.DATE)
  @Mapping(target = "total", numberFormat = FmtMappers.AMOUNT)
  FmtOrderDto toDto(FmtOrder source);

  @InheritInverseConfiguration
  FmtOrder fromDto(FmtOrderDto source);
}
