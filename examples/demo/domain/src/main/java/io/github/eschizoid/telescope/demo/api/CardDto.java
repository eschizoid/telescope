package io.github.eschizoid.telescope.demo.api;

/** A card payment as the customer sees it on a receipt. */
public record CardDto(String brand, String last4) implements PaymentDto {}
