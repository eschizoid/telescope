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
 * <p>The tags are sent only when the request asks for them. A row whose tags were not asked for
 * holds {@code null} and is written without a {@code tags} field; a row whose tags were asked for
 * holds the order's list, empty when the order has none. {@link Unloaded} also leaves out a list
 * the persistence provider has not loaded, so writing a row never loads one.
 */
public record OrderRow(
  String number,
  String customerName,
  OrderStatus status,
  Delivery delivery,
  boolean giftWrap,
  LocalDate placedOn,
  @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = Unloaded.class) List<String> tags
) {
  /** This row without its tags, for a request that did not ask for them. */
  public OrderRow withoutTags() {
    return new OrderRow(number, customerName, status, delivery, giftWrap, placedOn, null);
  }
}
