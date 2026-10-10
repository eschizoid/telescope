package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Constant;

/**
 * {@link SuperRowSource}'s shape, bridged to {@link SuperRowNamedRecord} with a constant on the
 * component {@link SuperRowNamed} abstracts, so the generated bridge can be compared with a runtime
 * {@code constant} row naming the component through the interface.
 */
@Bridge(value = SuperRowNamedRecord.class, constants = @Constant(field = "name", value = "fixed"))
public record SuperRowNamedBridged(int n) {}
