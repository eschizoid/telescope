package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Compute;

/**
 * {@link SuperRowSource}'s shape, bridged to {@link SuperRowGenericSub} with a computed value on
 * the property its generic base declares, so the generated bridge can be compared with a runtime
 * {@code compute} row naming the same property through the base's getter.
 */
@Bridge(value = SuperRowGenericSub.class, computes = @Compute(field = "name", using = SuperRowNameSupplier.class))
public record SuperRowComputedBridged(int n) {}
