package io.github.eschizoid.telescope;

/** An abstract base that declares the property {@code name}. */
public abstract class SuperRowAbstractBase {

  private String name;

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }
}
