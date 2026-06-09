package io.github.eschizoid.telescope.demo.spring.api;

/**
 * Request body for {@code POST /orders/{id}/bulk-update}. Each field is independently nullable —
 * null means "leave that path alone". The controller folds the non-null subset into a {@code
 * Telescope.all(over(...))} bundle, one {@code over(...)} per requested edit.
 */
public record BulkUpdateRequest(
  String orderNumber,
  String customerEmail,
  String shippingCity,
  String billingCity,
  Integer lineItemQuantityDelta
) {}
