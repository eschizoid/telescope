package io.github.eschizoid.telescope.jpa.fixtures;

/** A line of a cart as an edit sends it: the identifier of a held line, or null for a new one. */
public record LineChanges(Long id, String sku, int quantity) {}
