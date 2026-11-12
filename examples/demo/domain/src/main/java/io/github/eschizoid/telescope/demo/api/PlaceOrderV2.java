package io.github.eschizoid.telescope.demo.api;

import io.github.eschizoid.telescope.demo.order.Delivery;
import io.github.eschizoid.telescope.demo.order.Payment;
import java.util.List;
import java.util.Set;

/**
 * The order the mobile app's cart sends: the signed-in buyer's email, each item as a product and a
 * quantity, a coupon, and the labels the buyer picked, as a set.
 */
public record PlaceOrderV2(
  String buyerEmail,
  List<NewLineV2> items,
  Delivery delivery,
  boolean giftWrap,
  Payment payment,
  String coupon,
  Set<String> tags
) {}
