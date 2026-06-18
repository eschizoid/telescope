package io.github.eschizoid.telescope.focus;

/** Source POJO with a nullable boxed {@code Integer} field, paired with a primitive-int target. */
public class NullableIntegerSource {

  private Integer attemptCount;

  public NullableIntegerSource() {}

  public Integer getAttemptCount() {
    return attemptCount;
  }

  public void setAttemptCount(final Integer attemptCount) {
    this.attemptCount = attemptCount;
  }
}
