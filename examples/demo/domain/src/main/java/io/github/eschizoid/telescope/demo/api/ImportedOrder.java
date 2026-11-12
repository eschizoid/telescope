package io.github.eschizoid.telescope.demo.api;

import io.github.eschizoid.telescope.annotations.FromMap;
import java.util.List;

/**
 * An order from a partner's feed, which arrives as untyped JSON with every value written as text.
 * The generated {@code ImportedOrderFromMap} binds the parsed map to this record, and a mapper
 * turns the text into the types {@link PlaceOrder} holds.
 */
@FromMap(required = { "customerEmail", "lines" })
public record ImportedOrder(
  String customerEmail,
  List<ImportedLine> lines,
  String delivery,
  String giftWrap,
  String discountCode
) {}
