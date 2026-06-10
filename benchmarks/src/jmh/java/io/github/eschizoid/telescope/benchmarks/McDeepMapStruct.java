package io.github.eschizoid.telescope.benchmarks;

import org.mapstruct.InheritInverseConfiguration;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * MapStruct mapper for the deep tier. Three levels of nesting + two list hops: {@code Company →
 * List<Department> → List<Team>}. MapStruct auto-generates the list iterators when an element-level
 * mapping method exists, so the interface declares one method per type pair and lets the processor
 * synthesise the {@code List} bridges and the inverse direction.
 */
@Mapper
public interface McDeepMapStruct {
  McDeepMapStruct INSTANCE = Mappers.getMapper(McDeepMapStruct.class);

  McCompanyRec toRec(McCompanyBean src);

  McDeptRec toDeptRec(McDeptBean src);

  McTeamRec toTeamRec(McTeamBean src);

  @InheritInverseConfiguration
  McCompanyBean toBean(McCompanyRec src);

  @InheritInverseConfiguration
  McDeptBean toDeptBean(McDeptRec src);

  @InheritInverseConfiguration
  McTeamBean toTeamBean(McTeamRec src);
}
