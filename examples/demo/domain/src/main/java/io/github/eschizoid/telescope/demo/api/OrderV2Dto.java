package io.github.eschizoid.telescope.demo.api;

import java.util.List;
import java.util.Set;

/**
 * An order as version 2 of the API shows it. Version 2 is what the shop's mobile app was built
 * against, and phones that never update keep calling it, so its shape is fixed. The app calls the
 * customer the {@code buyer} and a discount code a {@code coupon}, and expects the coupon never to
 * be null. It reads JSON numbers as floating point, which would round a price, so money is text. It
 * shows the date as it arrives, so the date is text in the app's display format, and it shows tags
 * as labels, so they are a set.
 */
public record OrderV2Dto(
  String number,
  String buyer,
  String status,
  String placedOn,
  List<OrderLineV2Dto> lines,
  String total,
  String coupon,
  Set<String> tags
) {}
