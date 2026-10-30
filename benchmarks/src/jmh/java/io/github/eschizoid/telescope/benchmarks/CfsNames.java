package io.github.eschizoid.telescope.benchmarks;

/** The method a {@code compute} row of {@link CfsMappers} names. */
public final class CfsNames {

  private CfsNames() {}

  public static String full(final CfsPerson person) {
    return person.first() + " " + person.last();
  }
}
