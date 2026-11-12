package io.github.eschizoid.telescope.demo.api;

import io.github.eschizoid.telescope.demo.order.OrderStatus;
import java.math.BigDecimal;

/** One row of the order list. */
public record OrderSummary(String number, String customerName, OrderStatus status, BigDecimal total) {}
