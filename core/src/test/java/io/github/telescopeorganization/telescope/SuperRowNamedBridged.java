package io.github.telescopeorganization.telescope;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.constant;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;

/**
 * {@link SuperRowSource}'s shape, bridged to {@link SuperRowNamedRecord} with a constant on the
 * component {@link SuperRowNamed} abstracts, so the generated bridge can be compared with a runtime
 * {@code constant} row naming the component through the interface.
 */
public record SuperRowNamedBridged(int n) {
  @Bridge
  public static final Mapper<SuperRowNamedBridged, SuperRowNamedRecord> RECORD = mapper(
    SuperRowNamedBridged.class,
    SuperRowNamedRecord.class,
    constant(SuperRowNamedRecord::name, "fixed")
  );
}
