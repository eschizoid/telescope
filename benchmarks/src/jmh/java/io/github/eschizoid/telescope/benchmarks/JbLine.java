package io.github.eschizoid.telescope.benchmarks;

import java.math.BigDecimal;

/** One line of a {@link JbOrder}. */
public record JbLine(String sku, int quantity, BigDecimal price) {}
