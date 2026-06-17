package io.github.eschizoid.telescope.focus;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/**
 * {@code @BeanFocus} target whose {@code attemptCount} is a primitive {@code int}; the codegen-
 * emitted {@code construct(Function)} unboxes the boxed value back to {@code int}, so it must
 * substitute the JLS default when the source value is {@code null}.
 */
@BeanFocus
public class PrimitiveIntTarget {

  private int attemptCount;

  public PrimitiveIntTarget() {}

  public int getAttemptCount() {
    return attemptCount;
  }

  public void setAttemptCount(final int attemptCount) {
    this.attemptCount = attemptCount;
  }
}
