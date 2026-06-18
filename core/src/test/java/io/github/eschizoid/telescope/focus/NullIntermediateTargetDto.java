package io.github.eschizoid.telescope.focus;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/** Target DTO with a single {@code innerName} sourced from a nullable nested bean path. */
@BeanFocus
public class NullIntermediateTargetDto {

  private String innerName;

  public NullIntermediateTargetDto() {}

  public String getInnerName() {
    return innerName;
  }

  public void setInnerName(final String innerName) {
    this.innerName = innerName;
  }
}
