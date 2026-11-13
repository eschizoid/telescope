package io.github.eschizoid.telescope.demo.api;

import io.github.eschizoid.telescope.annotations.FromMap;
import java.util.List;

/**
 * An order from a partner's feed, which arrives as JSON with every value written as text. {@link
 * PartnerFeed} reads the text into this record, and a mapper turns the text into the types {@link
 * PlaceOrder} holds. The generated {@code ImportedOrderFromMap} binds the same record from an
 * already parsed map.
 */
@FromMap(required = { "customerEmail", "lines" })
public record ImportedOrder(
  String customerEmail,
  List<ImportedLine> lines,
  String delivery,
  String giftWrap,
  String discountCode
) {}
