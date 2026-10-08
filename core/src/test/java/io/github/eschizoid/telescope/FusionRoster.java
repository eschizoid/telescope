package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/** Setter-strategy bean fixture for navigator fusion. */
@BeanFocus
public class FusionRoster {

  private String title;
  private String code;

  public FusionRoster() {}

  public String getTitle() {
    return title;
  }

  public void setTitle(final String title) {
    this.title = title;
  }

  public String getCode() {
    return code;
  }

  public void setCode(final String code) {
    this.code = code;
  }
}
