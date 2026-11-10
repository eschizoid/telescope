package io.github.telescopeorganization.telescope.focus;

import io.github.telescopeorganization.telescope.annotations.BeanFocus;

/**
 * {@code @BeanFocus} target with a primitive {@code int} field. Pairs with a nullable boxed- {@code
 * Integer} source to exercise null-to-primitive coercion.
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
