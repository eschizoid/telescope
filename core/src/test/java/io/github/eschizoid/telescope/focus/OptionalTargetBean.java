package io.github.eschizoid.telescope.focus;

/**
 * Target POJO whose {@code resolvedName} is sourced from an {@link java.util.Optional}-typed path.
 */
public class OptionalTargetBean {

  private String resolvedName;

  public OptionalTargetBean() {}

  public String getResolvedName() {
    return resolvedName;
  }

  public void setResolvedName(final String resolvedName) {
    this.resolvedName = resolvedName;
  }
}
