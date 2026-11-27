package io.github.eschizoid.telescope.benchmarks;

/**
 * The service the service row of {@link SrvMappers} calls, computing what {@link SrvNames} does.
 */
public class SrvNamer {

  public String full(final SrvPerson person) {
    return person.first() + " " + person.last();
  }
}
