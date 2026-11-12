package io.github.eschizoid.telescope.demo.order;

import java.math.BigDecimal;

/** What an order costs: the lines added up, the discount taken off and the rest. */
public record Totals(BigDecimal subtotal, BigDecimal discount, BigDecimal total) {}
