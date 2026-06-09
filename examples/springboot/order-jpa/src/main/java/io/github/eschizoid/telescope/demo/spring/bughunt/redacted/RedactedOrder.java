package io.github.eschizoid.telescope.demo.spring.bughunt.redacted;

/**
 * Narrower projection of {@code Order} used by the redacted GET endpoint. Strips line items,
 * billing address, and gift wrap; reduces the customer to an obfuscated email and the shipping
 * address to a first-letter + "***" hint of the city.
 *
 * <p>Conversion runs through {@code Telescope.from(Order.class).to(RedactedOrder.class).using(...)}
 * — see {@link RedactedOrderTelescopes#REDACT}. The pair is deliberately <b>not bijective</b>: the
 * {@code backward} direction cannot recover the lost fields, so it explicitly throws.
 */
public record RedactedOrder(Long id, String orderNumber, String redactedCustomerEmail, String redactedShippingCity) {}
