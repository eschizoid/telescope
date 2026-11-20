package io.github.eschizoid.telescope.quarkus.tests.openapi.cycle.x;

/**
 * The domain type a resource maps to {@link Node}.
 *
 * @param xv a value
 * @param other its loop
 */
public record Knot(String xv, Loop other) {
  /**
   * The domain type a {@link Knot} refers to, mapped to {@link Link}.
   *
   * @param yv a value
   * @param back the knot
   */
  public record Loop(int yv, Knot back) {}
}
