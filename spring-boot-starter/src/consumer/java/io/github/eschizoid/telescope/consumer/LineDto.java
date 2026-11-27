package io.github.eschizoid.telescope.consumer;

import java.math.BigDecimal;

/** A line on the wire. */
public record LineDto(String sku, int quantity, BigDecimal unitPrice) {}
