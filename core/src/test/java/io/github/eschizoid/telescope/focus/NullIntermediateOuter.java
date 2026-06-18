package io.github.eschizoid.telescope.focus;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/**
 * Outer {@code @BeanFocus} POJO with a nullable nested {@code @BeanFocus} field; exercises the
 * codegen holder-reader path when {@link #inner} is {@code null}.
 */
@BeanFocus
public class NullIntermediateOuter {

  private NullIntermediateInner inner;

  public NullIntermediateOuter() {}

  public NullIntermediateInner getInner() {
    return inner;
  }

  public void setInner(final NullIntermediateInner inner) {
    this.inner = inner;
  }
}
