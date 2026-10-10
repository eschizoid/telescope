package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Constant;

/**
 * {@link SuperRowSource}'s shape, bridged to {@link SuperRowAbstractSub} with a constant on the
 * property the subclass inherits, so the generated bridge can be compared with a runtime {@code
 * constant} row naming the same property through the abstract base's getter.
 */
@Bridge(value = SuperRowAbstractSub.class, constants = @Constant(field = "name", value = "fixed"))
public record SuperRowBridged(int n) {}
