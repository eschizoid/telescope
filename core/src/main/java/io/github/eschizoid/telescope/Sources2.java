package io.github.eschizoid.telescope;

/**
 * Two-source tuple consumed by {@link Telescope#merge(Class, Class, Class,
 * io.github.eschizoid.telescope.mapping.MergeStep2[])}. The forward-only multi-source mapper reads
 * each row's source value from either {@link #first} or {@link #second} based on which slot the row
 * was bound to via {@link io.github.eschizoid.telescope.mapping.MergeStep2#first} / {@link
 * io.github.eschizoid.telescope.mapping.MergeStep2#second}.
 *
 * <p>Exists as a concrete record (rather than a {@code Tuple2} generic alias) so the mapper's
 * source type is a real, named class — both for IDE inference at call sites and for {@link
 * io.github.eschizoid.telescope.conversion.Mapper#sourceClass()} introspection.
 *
 * <p>Permit of the sealed {@link Sources} family — see that interface for the arity-extension
 * policy.
 *
 * @param <A> the first source type
 * @param <B> the second source type
 */
public record Sources2<A, B>(A first, B second) implements Sources {
  @Override
  public int arity() {
    return 2;
  }

  @Override
  public Object slot(final int index) {
    return switch (index) {
      case 0 -> first;
      case 1 -> second;
      default -> throw new IndexOutOfBoundsException("Sources2 slot " + index + " out of range [0, 1]");
    };
  }
}
