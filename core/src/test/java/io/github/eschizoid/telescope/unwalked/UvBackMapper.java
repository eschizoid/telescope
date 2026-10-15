package io.github.eschizoid.telescope.unwalked;

/** Converts a back-reference to an empty one, as a @ViaMapper holder with static methods. */
public final class UvBackMapper {

  private UvBackMapper() {}

  public static UvBackDto forward(final UvBack back) {
    return back == null ? null : new UvBackDto(null);
  }

  public static UvBack backward(final UvBackDto back) {
    return back == null ? null : new UvBack(null);
  }
}
