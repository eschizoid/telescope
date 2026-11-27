package io.github.eschizoid.telescope.jpa.fixtures;

/** A line as a PATCH sends it: the identifier of a held line, or null for a new one. */
public record LinePatch(Long id, String sku, int quantity) {}
