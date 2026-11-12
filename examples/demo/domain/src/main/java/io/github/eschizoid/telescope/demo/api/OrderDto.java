package io.github.eschizoid.telescope.demo.api;

import io.github.eschizoid.telescope.annotations.Focus;
import io.github.eschizoid.telescope.demo.order.Delivery;
import io.github.eschizoid.telescope.demo.order.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * An order as the public API shows it. It has no cost prices, no card token and no internal notes,
 * and its total is computed from the lines. {@code shipping} stays null until the order ships.
 */
@Focus
public record OrderDto(
  String number,
  String customerName,
  OrderStatus status,
  Delivery delivery,
  boolean giftWrap,
  LocalDate placedOn,
  List<OrderLineDto> lines,
  BigDecimal total,
  String discountCode,
  PaymentDto payment,
  ShippingDto shipping,
  List<String> tags
) {}
