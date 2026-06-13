package io.github.eschizoid.telescope;

/**
 * Three-source tuple consumed by {@link Telescope#merge(Class, Class, Class, Class,
 * io.github.eschizoid.telescope.mapping.MergeStep3[])}. Each row in the merge picks one of {@link
 * #first}, {@link #second}, or {@link #third} via the corresponding {@link
 * io.github.eschizoid.telescope.mapping.MergeStep3} factory.
 *
 * <p>Permit of the sealed {@link Sources} family. See {@link Sources} for the arity-extension
 * policy ({@code Sources4} / {@code Sources5} are mechanical copies of this shape; arity &gt; 5 is
 * an explicit smell threshold).
 *
 * @param <A> the first source type
 * @param <B> the second source type
 * @param <C> the third source type
 */
public record Sources3<A, B, C>(A first, B second, C third) implements Sources {
  @Override
  public int arity() {
    return 3;
  }

  @Override
  public Object slot(final int index) {
    return switch (index) {
      case 0 -> first;
      case 1 -> second;
      case 2 -> third;
      default -> throw new IndexOutOfBoundsException("Sources3 slot " + index + " out of range [0, 2]");
    };
  }
}
