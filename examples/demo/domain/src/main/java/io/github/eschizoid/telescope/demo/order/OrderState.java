package io.github.eschizoid.telescope.demo.order;

import io.github.eschizoid.telescope.demo.api.NewLine;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything an order holds, as an immutable value. {@code order.toOrderState()} reads it out of
 * the entity, and {@code OrderMappers.STATE.into(order, state)} writes it back into the same
 * entity. Each line is held as the SKU and quantity a client orders; the prices come back from the
 * catalog.
 */
public record OrderState(
  Long id,
  String number,
  Customer customer,
  OrderStatus status,
  Delivery delivery,
  boolean giftWrap,
  LocalDate placedOn,
  String discountCode,
  String trackingNumber,
  String internalNotes,
  Payment payment,
  List<String> tags,
  List<NewLine> lines
) {}
