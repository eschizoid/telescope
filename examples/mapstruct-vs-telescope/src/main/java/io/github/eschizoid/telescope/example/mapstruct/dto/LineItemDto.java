package io.github.eschizoid.telescope.example.mapstruct.dto;

import java.math.BigDecimal;

/**
 * Target-side order line. All components are same-named — they map by recursion with no override.
 */
public record LineItemDto(String sku, int quantity, BigDecimal price) {}
