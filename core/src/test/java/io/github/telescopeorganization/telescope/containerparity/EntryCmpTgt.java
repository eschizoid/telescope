package io.github.telescopeorganization.telescope.containerparity;

/** Ordered by declaration, with no constructor that can be told its own order. */
public record EntryCmpTgt(SortedSubtypeEntryCmp<String, SortedParityB> items) {}
