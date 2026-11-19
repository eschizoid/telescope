package io.github.eschizoid.telescope.benchmarks;

/** A line as a client sends it, carrying the identifier of a line the order holds. */
public record MbLineForm(Long id, String sku, int quantity) {}
