package io.github.eschizoid.telescope.demo.starter.domain;

/**
 * The API-side product record — what Jackson hydrates from the request JSON. The runtime {@code
 * Telescope.mapper(...)} machinery handles every conversion in this submodule; no {@code @Focus}
 * annotation is needed (codegen lives in the sibling {@code invoicing/} demo).
 *
 * <p>{@code id} is nullable on the way in (Hibernate assigns it) and populated on the way back.
 */
public record Product(Long id, String sku, String name, long priceCents) {}
