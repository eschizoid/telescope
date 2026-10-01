package io.github.eschizoid.telescope.containerparity;

/** Able to receive the order, through a parameter that names the key type exactly. */
public record InvCmpTgt(SortedSubtypeInvCmp<String, SortedParityB> items) {}
