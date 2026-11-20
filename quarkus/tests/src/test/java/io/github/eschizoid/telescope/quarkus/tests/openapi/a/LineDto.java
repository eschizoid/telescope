package io.github.eschizoid.telescope.quarkus.tests.openapi.a;

/**
 * A line as a mapped body writes it, sharing its simple name with another class.
 *
 * @param sku the line's stock-keeping unit
 */
public record LineDto(String sku) {}
