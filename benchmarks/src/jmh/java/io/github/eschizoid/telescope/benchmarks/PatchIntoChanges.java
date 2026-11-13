package io.github.eschizoid.telescope.benchmarks;

import java.util.List;

/**
 * The partial {@link PatchIntoBenchmark} sends: every property of a {@link PatchIntoEntity}, the
 * quantity boxed so that it too can be left out. The benchmark sends the delivery and the tags.
 */
public record PatchIntoChanges(
  String number,
  String status,
  String delivery,
  String discountCode,
  String trackingNumber,
  Integer quantity,
  List<String> tags
) {}
