package io.github.eschizoid.telescope.benchmarks;

/**
 * Nested-tier outer record. Mirror of {@link McNestedBean}: {@code id} / {@code email} scalars plus
 * a nested {@link McAddressRec}.
 */
public record McNestedRec(Long id, String email, McAddressRec address) {}
