package io.github.eschizoid.telescope.benchmarks;

import org.mapstruct.InheritInverseConfiguration;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * MapStruct mapper for the hash-container tier: a {@code Set}-valued and a {@code Map}-valued
 * holder over the same leaf pair the deep tier uses. The list tiers cannot see how a rebuild sizes
 * a hash table, because a list has none — this pair is where an allocation difference in the
 * emitted container helper becomes visible at all.
 *
 * <p>MapStruct synthesises the container bridges from the element-level method, the same way it
 * does for lists.
 */
@Mapper
public interface McContainerMapStruct {
  McContainerMapStruct INSTANCE = Mappers.getMapper(McContainerMapStruct.class);

  McSetRec toSetRec(McSetBean src);

  McMapRec toMapRec(McMapBean src);

  McTeamRec toTeamRec(McTeamBean src);

  @InheritInverseConfiguration
  McSetBean toSetBean(McSetRec src);

  @InheritInverseConfiguration
  McMapBean toMapBean(McMapRec src);

  @InheritInverseConfiguration
  McTeamBean toTeamBean(McTeamRec src);
}
