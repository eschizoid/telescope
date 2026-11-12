package io.github.eschizoid.telescope.demo.order;

/** Hides all of a value but its end. */
public final class Masks {

  private Masks() {}

  /** The last four characters, which is what a receipt prints of an account number. */
  public static String lastFour(final String value) {
    return value.length() <= 4 ? value : value.substring(value.length() - 4);
  }
}
