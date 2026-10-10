package io.github.eschizoid.telescope;

/**
 * A setter bean whose {@code name} comes from an abstract, non-generic base, with no generated
 * holder, so a runtime path through it is written by the reflective bean lens.
 */
public class InheritPlainLeaf extends InheritNamedBase {

  private int size;

  public InheritPlainLeaf() {}

  public int getSize() {
    return size;
  }

  public void setSize(final int size) {
    this.size = size;
  }
}
