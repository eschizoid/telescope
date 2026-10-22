package io.github.eschizoid.telescope.benchmarks;

/**
 * Converts {@link BenchWrapSrc#b()}: its {@code forward} returns an {@code Integer} for an {@code
 * int}.
 */
public final class BenchWrapBMap {

  private BenchWrapBMap() {}

  /**
   * @param value the source's value
   * @return it, as it is
   */
  public static Integer forward(final Integer value) {
    return value;
  }

  /**
   * @param value the target's value
   * @return it, boxed
   */
  public static Integer backward(final int value) {
    return value;
  }
}
