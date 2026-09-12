package io.github.eschizoid.telescope.bridgexpkg.sortedcmp;

/** Deliberately not {@link Comparable}: a sorted container can only hold it under a comparator. */
public record SortKey(String id) {}
