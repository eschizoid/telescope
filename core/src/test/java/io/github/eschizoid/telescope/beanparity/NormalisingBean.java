package io.github.eschizoid.telescope.beanparity;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/**
 * A bean that offers both write surfaces and makes them disagree. The builder upper-cases on
 * build(); the setter stores what it was given. Which surface a rebuild chooses is therefore
 * visible in the value, not only in the generated text.
 */
@BeanFocus
public class NormalisingBean {

  private String name;
  private String code;

  public NormalisingBean() {}

  public static Builder builder() {
    return new Builder();
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public String getCode() {
    return code;
  }

  public void setCode(final String code) {
    this.code = code;
  }

  /**
   * Upper-cases the code, so a rebuild routed through here is distinguishable from a setter write.
   */
  public static final class Builder {

    private String name;
    private String code;

    public Builder name(final String name) {
      this.name = name;
      return this;
    }

    public Builder code(final String code) {
      this.code = code;
      return this;
    }

    public NormalisingBean build() {
      final var out = new NormalisingBean();
      out.setName(name);
      out.setCode(code == null ? null : code.toUpperCase());
      return out;
    }
  }
}
