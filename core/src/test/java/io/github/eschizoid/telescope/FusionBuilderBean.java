package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/** Builder-strategy bean fixture for navigator fusion. */
@BeanFocus
public class FusionBuilderBean {

  private final String title;
  private final String code;

  private FusionBuilderBean(final String title, final String code) {
    this.title = title;
    this.code = code;
  }

  public String getTitle() {
    return title;
  }

  public String getCode() {
    return code;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private String title;
    private String code;

    public Builder title(final String title) {
      this.title = title;
      return this;
    }

    public Builder code(final String code) {
      this.code = code;
      return this;
    }

    public FusionBuilderBean build() {
      return new FusionBuilderBean(title, code);
    }
  }
}
