package io.github.eschizoid.telescope.example.mapstruct.domain;

import java.math.BigDecimal;

/** Source-side order line. {@code price} is the field Act 2 deep-updates in place. */
public record LineItem(String sku, int quantity, BigDecimal price) {}
