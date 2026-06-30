package io.github.eschizoid.telescope.example.mapstruct.dto;

/**
 * Target-side customer. {@code contactEmail} is deliberately named differently from the source
 * {@code Customer.email} — this is the field whose correspondence must be spelled explicitly, and
 * the one that exposes the string-vs-method-reference gap when the source field is renamed.
 */
public record CustomerDto(String name, String contactEmail) {}
