package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/** Constructor-strategy bean fixture for navigator fusion. */
@BeanFocus
public class FusionCtorBean {

  private final String title;
  private final String code;

  public FusionCtorBean(final String title, final String code) {
    this.title = title;
    this.code = code;
  }

  public String getTitle() {
    return title;
  }

  public String getCode() {
    return code;
  }
}
