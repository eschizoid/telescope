package io.github.eschizoid.telescope.containerparity;

/**
 * Ordered by declaration and able to receive the order, from a source that does not promise one.
 */
public record PlainToCmpTgt(SortedSubtypeCmp<String, SortedParityB> items) {}
