package io.github.eschizoid.telescope.focus;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/**
 * Nested {@code @BeanFocus} POJO used as the leaf of a path whose intermediate may be {@code null}.
 */
@BeanFocus
public class NullIntermediateInner {

  private String name;

  public NullIntermediateInner() {}

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }
}
