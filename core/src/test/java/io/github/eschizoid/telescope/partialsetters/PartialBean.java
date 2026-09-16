package io.github.eschizoid.telescope.partialsetters;

/**
 * A bean whose setter surface is incomplete: {@code name} can be set, {@code code} can only be read
 * and is written through the builder. Both surfaces exist, and only one of them can reproduce the
 * whole value.
 */
public class PartialBean {

  private String name;
  private String code;

  public PartialBean() {}

  public static Builder builder() {
    return new Builder();
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  /** No setter. A rebuild that cannot reach this property leaves it null. */
  public String getCode() {
    return code;
  }

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

    public PartialBean build() {
      final var out = new PartialBean();
      out.name = name;
      out.code = code;
      return out;
    }
  }
}
