package io.github.eschizoid.telescope.demo.api;

import java.math.BigDecimal;

/** One line of an order, as the public API shows it: what the customer pays, never the cost. */
public record OrderLineDto(String sku, String name, int quantity, BigDecimal unitPrice) {}
