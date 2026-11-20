package io.github.eschizoid.telescope.quarkus.tests.fixtures;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * The wire type a client sends for a new order, with the constraints written on it. It names the
 * customer {@code buyer}, so only the mapping in {@link RequestMappers} fills a {@link PlaceOrder}
 * from it.
 */
public record PlaceOrderRequest(@NotBlank String buyer, @Positive int quantity, String clientRef) {}
