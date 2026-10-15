package io.github.eschizoid.telescope;

/** A concrete source bean that declares the property {@code label}. */
public class SourceRootBase {

  private String label;

  public SourceRootBase() {}

  public String getLabel() {
    return label;
  }

  public void setLabel(final String label) {
    this.label = label;
  }
}
