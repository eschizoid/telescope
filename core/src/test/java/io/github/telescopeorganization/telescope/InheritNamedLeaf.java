package io.github.telescopeorganization.telescope;

import io.github.telescopeorganization.telescope.annotations.BeanFocus;

/** A setter bean whose {@code name} comes from an abstract, non-generic base. */
@BeanFocus
public class InheritNamedLeaf extends InheritNamedBase {

  private int size;

  public InheritNamedLeaf() {}

  public int getSize() {
    return size;
  }

  public void setSize(final int size) {
    this.size = size;
  }
}
