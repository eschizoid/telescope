package io.github.eschizoid.telescope.benchmarks;

/**
 * Converts {@link BenchWrapSrc#a()}: its {@code backward} returns an {@code Integer} for an {@code
 * int}.
 */
public final class BenchWrapAMap {

  private BenchWrapAMap() {}

  /**
   * @param value the source's value
   * @return it, boxed
   */
  public static Integer forward(final int value) {
    return value;
  }

  /**
   * @param value the target's value
   * @return it, as it is
   */
  public static Integer backward(final Integer value) {
    return value;
  }
}
