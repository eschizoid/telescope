package io.github.eschizoid.telescope.jpa.fixtures;

/**
 * A line as a client sends it: the identifier of a line the basket holds, or null for a new one.
 */
public record LineForm(Long id, String sku, int quantity) {}
