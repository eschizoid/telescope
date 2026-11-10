package io.github.telescopeorganization.telescope.containerparity;

/**
 * Ordered by declaration and able to receive the order, from a source that does not promise one.
 */
public record PlainToCmpTgt(SortedSubtypeCmp<String, SortedParityB> items) {}
