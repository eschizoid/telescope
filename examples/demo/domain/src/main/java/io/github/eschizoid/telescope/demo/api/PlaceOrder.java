package io.github.eschizoid.telescope.demo.api;

import io.github.eschizoid.telescope.demo.order.Delivery;
import io.github.eschizoid.telescope.demo.order.Payment;
import java.util.List;

/**
 * The request that places an order, in the shape the service works with. Version 1 of the API takes
 * it as it is; version 2 and the partner import arrive in other shapes and are mapped to it.
 */
public record PlaceOrder(
  String customerEmail,
  List<NewLine> lines,
  Delivery delivery,
  boolean giftWrap,
  Payment payment,
  String discountCode,
  List<String> tags
) {}
