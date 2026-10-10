package io.github.eschizoid.telescope;

/** A record whose properties line up with {@link RowNamedSub} only through explicit rows. */
public record RowRenamed(String label, String key, String tag) {}
