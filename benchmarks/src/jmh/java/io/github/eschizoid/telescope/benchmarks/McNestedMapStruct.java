package io.github.eschizoid.telescope.benchmarks;

import org.mapstruct.InheritInverseConfiguration;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * MapStruct mapper for the nested tier. The outer {@link McNestedBean} ↔ {@link McNestedRec}
 * carries a nested address pair; the inner {@code McAddressBean ↔ McAddressRec} method is declared
 * so MapStruct generates the recursion. Without it, MapStruct cannot auto-compose nested types
 * across the bean/record paradigm boundary.
 */
@Mapper
public interface McNestedMapStruct {
  McNestedMapStruct INSTANCE = Mappers.getMapper(McNestedMapStruct.class);

  McNestedRec toRec(McNestedBean src);

  McAddressRec toAddressRec(McAddressBean src);

  @InheritInverseConfiguration
  McNestedBean toBean(McNestedRec src);

  @InheritInverseConfiguration
  McAddressBean toAddressBean(McAddressRec src);
}
