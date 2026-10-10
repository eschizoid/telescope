package io.github.eschizoid.telescope;

/** {@link RowFlat} without {@code code}, so mapping {@link RowNamedSub} to it needs a drop. */
public record RowUncoded(String name, String tag) {}
