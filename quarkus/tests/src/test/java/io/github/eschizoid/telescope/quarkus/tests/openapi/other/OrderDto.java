package io.github.eschizoid.telescope.quarkus.tests.openapi.other;

/**
 * A type an unmapped endpoint returns, sharing its simple name with a mapped body's wire type.
 *
 * @param other a component the wire type does not have
 * @param unrelated another
 */
public record OrderDto(String other, boolean unrelated) {}
