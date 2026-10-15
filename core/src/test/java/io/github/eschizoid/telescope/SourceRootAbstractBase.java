package io.github.eschizoid.telescope;

/** An abstract source base that declares the property {@code label}. */
public abstract class SourceRootAbstractBase {

  private String label;

  public String getLabel() {
    return label;
  }

  public void setLabel(final String label) {
    this.label = label;
  }
}
