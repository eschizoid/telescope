package io.github.eschizoid.telescope.demo.order;

/**
 * A card payment. {@code token} is the payment provider's handle for charging the card again, which
 * only the service may see.
 */
public record Card(String brand, String last4, String token) implements Payment {}
