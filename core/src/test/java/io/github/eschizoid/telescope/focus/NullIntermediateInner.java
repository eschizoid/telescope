package io.github.eschizoid.telescope.focus;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/**
 * Nested {@code @BeanFocus} POJO that may appear as a {@code null} intermediate inside a multi-hop
 * telescope path — the captured {@code NullIntermediateInner::getName} method reference must not be
 * invoked on a {@code null} receiver.
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
