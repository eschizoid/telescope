package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.constant;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/**
 * {@link SuperRowSource}'s shape, bridged to {@link SuperRowAbstractSub} with a constant on the
 * property the subclass inherits, so the generated bridge can be compared with a runtime {@code
 * constant} row naming the same property through the abstract base's getter.
 */
public record SuperRowBridged(int n) {
  @Bridge
  public static final Mapper<SuperRowBridged, SuperRowAbstractSub> SUB = mapper(
    SuperRowBridged.class,
    SuperRowAbstractSub.class,
    constant(SuperRowAbstractSub::getName, "fixed")
  );
}
