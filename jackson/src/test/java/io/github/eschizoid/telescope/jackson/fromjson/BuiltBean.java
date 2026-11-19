package io.github.eschizoid.telescope.jackson.fromjson;

import io.github.eschizoid.telescope.jackson.FromJson;
import java.util.Objects;

/** A bean the runtime writer builds through its static {@code builder()}. */
@FromJson
public final class BuiltBean {

  private final String name;
  private final int count;

  private BuiltBean(final String name, final int count) {
    this.name = name;
    this.count = count;
  }

  public static Builder builder() {
    return new Builder();
  }

  public String getName() {
    return name;
  }

  public int getCount() {
    return count;
  }

  /** The builder {@link BuiltBean#builder()} returns. */
  public static final class Builder {

    private String name;
    private int count;

    public Builder name(final String name) {
      this.name = name;
      return this;
    }

    public Builder count(final int count) {
      this.count = count;
      return this;
    }

    public BuiltBean build() {
      return new BuiltBean(name, count);
    }
  }

  @Override
  public boolean equals(final Object other) {
    return other instanceof BuiltBean that && count == that.count && Objects.equals(name, that.name);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, count);
  }

  @Override
  public String toString() {
    return "BuiltBean[" + name + ", " + count + "]";
  }
}
