package io.github.eschizoid.telescope.demo.order;

import java.math.BigDecimal;

/** Something the shop sells: the price a customer pays and the cost the shop paid. */
public record Product(String sku, String name, BigDecimal price, BigDecimal cost) {}
