package io.github.eschizoid.telescope.codegen.lombok.fixtures;

/**
 * Fixture: declares {@link OrderedLombokTarget}'s getters and setters the other way round. HotSpot
 * lists a class's methods in the order their names were first seen in the JVM, so once this class
 * is loaded, the target's methods come back second first.
 */
public final class OrderedLombokNames {

  public String getSecondLo() {
    return null;
  }

  public void setSecondLo(final String v) {}

  public String getFirstLo() {
    return null;
  }

  public void setFirstLo(final String v) {}
}
