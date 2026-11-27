package io.github.eschizoid.telescope.benchmarks;

/** The static method the static row of {@link SrvMappers} calls. */
public final class SrvNames {

  private SrvNames() {}

  public static String full(final SrvPerson person) {
    return person.first() + " " + person.last();
  }
}
