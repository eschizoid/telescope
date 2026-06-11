package io.github.eschizoid.telescope.benchmarks;

import org.mapstruct.InheritInverseConfiguration;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * MapStruct mapper for the flat tier — five same-named scalar fields, no overrides. MapStruct's
 * auto-resolution does all the work; the interface is empty of {@code @Mapping} annotations.
 *
 * <p>{@code Mappers.getMapper(McFlatMapStruct.class)} returns the generated {@code
 * McFlatMapStructImpl} via {@link Mappers}' built-in {@code Class.forName} + reflective
 * instantiation, cached behind a static {@code INSTANCE} field on this interface. Benchmark code
 * holds {@link #INSTANCE} once per JMH state and calls {@link #toRec(McFlatBean)} / {@link
 * #toBean(McFlatRec)} in the hot loop — same lookup cost as a hand-coded static field.
 */
@Mapper
public interface McFlatMapStruct {
  McFlatMapStruct INSTANCE = Mappers.getMapper(McFlatMapStruct.class);

  McFlatRec toRec(McFlatBean src);

  @InheritInverseConfiguration
  McFlatBean toBean(McFlatRec src);
}
