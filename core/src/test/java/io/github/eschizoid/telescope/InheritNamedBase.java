package io.github.eschizoid.telescope;

/** An abstract, non-generic base that declares the property {@code name}. */
public abstract class InheritNamedBase {

  private String name;

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }
}
