package io.github.eschizoid.telescope.jackson;

import io.github.eschizoid.telescope.annotations.FromMap;

/** A nested type with a generated binder of its own, held by {@link StrictOrder}. */
@FromMap
public record StrictLine(String sku, int quantity) {}
