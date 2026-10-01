package io.github.eschizoid.telescope.containerparity;

/** Able to receive the order through a parameter wider than the keys it orders. */
public record ObjCmpTgt(SortedSubtypeObjectCmp<String, SortedParityB> items) {}
