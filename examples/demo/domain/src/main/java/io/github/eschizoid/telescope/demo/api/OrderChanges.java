package io.github.eschizoid.telescope.demo.api;

import io.github.eschizoid.telescope.demo.order.Delivery;
import java.util.List;

/**
 * The body of a PATCH. Every field is optional: a field the client leaves out is null, and the
 * order keeps what it had. A list that is sent replaces the order's list.
 */
public record OrderChanges(
  String discountCode,
  Delivery delivery,
  Boolean giftWrap,
  List<String> tags,
  List<NewLine> lines
) {}
