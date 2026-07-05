package io.github.eschizoid.telescope.introspection;

/**
 * Bounds for {@code trace(input)} — a trace materializes one node per focus, so a walk over a large
 * collection would build a huge tree. By default a trace caps how many elements it shows per
 * many-focus step ({@link #maxBreadth}) and how deep it descends ({@link #maxDepth}), marking the
 * cut with a {@code … (+K more)} / truncation node. {@link #none()} lifts both caps for the full
 * tree when you genuinely want everything.
 *
 * @param maxBreadth elements shown per {@code each} / {@code eachValue} step before truncating
 * @param maxDepth traversal steps descended before truncating
 */
public record TraceLimits(int maxBreadth, int maxDepth) {
  public TraceLimits {
    if (maxBreadth < 1) throw new IllegalArgumentException("maxBreadth must be >= 1, was " + maxBreadth);
    if (maxDepth < 1) throw new IllegalArgumentException("maxDepth must be >= 1, was " + maxDepth);
  }

  /**
   * The safe defaults applied by the no-arg {@code trace(input)}: 10 elements per fan-out, 20 deep.
   */
  public static TraceLimits defaults() {
    return new TraceLimits(10, 20);
  }

  /** No caps — the complete tree, however large. Use when you know the input is bounded. */
  public static TraceLimits none() {
    return new TraceLimits(Integer.MAX_VALUE, Integer.MAX_VALUE);
  }
}
