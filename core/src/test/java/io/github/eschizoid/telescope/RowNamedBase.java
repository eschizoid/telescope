package io.github.eschizoid.telescope;

/** An abstract base that declares the properties {@code name} and {@code code}. */
public abstract class RowNamedBase {

  private String name;
  private String code;

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
}
