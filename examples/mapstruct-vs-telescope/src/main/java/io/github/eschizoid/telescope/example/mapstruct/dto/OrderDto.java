package io.github.eschizoid.telescope.example.mapstruct.dto;

import java.util.List;

/**
 * Target-side order. Mirrors {@code Order}; only {@code Customer.email -> contactEmail} differs.
 */
public record OrderDto(String id, CustomerDto customer, List<LineItemDto> lines) {}
