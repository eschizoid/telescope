package io.github.eschizoid.telescope.demo.starter.domain;

import io.github.eschizoid.telescope.annotations.Focus;

/**
 * The API-side product record — what Jackson hydrates from the request JSON. {@code @Focus} emits
 * {@code ProductPath<R>} + {@code ProductTelescope} navigators so any deep update through the
 * record is reflection-free at compile time.
 *
 * <p>{@code id} is nullable on the way in (Hibernate assigns it) and populated on the way back.
 */
@Focus
public record Product(Long id, String sku, String name, long priceCents) {}
