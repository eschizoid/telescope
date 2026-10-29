package io.github.eschizoid.telescope.declaredbridge;

/**
 * A bean built through its builder or through its setters, which records which one built it in a
 * member no property reads.
 */
public class HintedBean {

  private String label;
  private String how = "setters";

  public HintedBean() {}

  public String getLabel() {
    return label;
  }

  public void setLabel(final String label) {
    this.label = label;
  }

  /** How the bean was built: {@code "builder"} or {@code "setters"}. */
  public String how() {
    return how;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Builds the bean, recording that the builder built it. */
  public static final class Builder {

    private String label;

    public Builder label(final String label) {
      this.label = label;
      return this;
    }

    public HintedBean build() {
      final var bean = new HintedBean();
      bean.label = label;
      bean.how = "builder";
      return bean;
    }
  }
}
