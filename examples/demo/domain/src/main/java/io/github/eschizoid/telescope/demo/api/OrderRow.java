package io.github.eschizoid.telescope.demo.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.eschizoid.telescope.demo.order.Delivery;
import io.github.eschizoid.telescope.demo.order.OrderStatus;
import java.time.LocalDate;
import java.util.List;

/**
 * One row of the back office's order dashboard: what the warehouse needs to pick an order from a
 * list, and no lines or totals, which would load each order's lines.
 *
 * <p>The tags are the order's own list. They are written only once something has loaded them, so a
 * page the client did not ask to expand with its tags is written without them, and without a query
 * per order to load them.
 */
public record OrderRow(
  String number,
  String customerName,
  OrderStatus status,
  Delivery delivery,
  boolean giftWrap,
  LocalDate placedOn,
  @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = Unloaded.class) List<String> tags
) {}
