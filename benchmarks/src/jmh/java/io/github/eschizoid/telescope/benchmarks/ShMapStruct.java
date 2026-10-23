package io.github.eschizoid.telescope.benchmarks;

import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/** MapStruct's mapper for {@link SharedCallSiteBenchmark}'s four class-form pairs. */
@Mapper
public interface ShMapStruct {
  ShMapStruct INSTANCE = Mappers.getMapper(ShMapStruct.class);

  ShDst1 one(ShSrc1 source);

  ShDst2 two(ShSrc2 source);

  ShDst3 three(ShSrc3 source);

  ShDst4 four(ShSrc4 source);
}
