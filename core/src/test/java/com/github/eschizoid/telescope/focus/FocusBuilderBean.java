package com.github.eschizoid.telescope.focus;

import com.github.eschizoid.telescope.annotations.BeanFocus;

/**
 * @BeanFocus fixture: rebuilt via a static builder().
 */
@BeanFocus
public class FocusBuilderBean {

  private final String id;
  private final String email;

  private FocusBuilderBean(final String id, final String email) {
    this.id = id;
    this.email = email;
  }

  public String getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private String id;
    private String email;

    public Builder id(final String id) {
      this.id = id;
      return this;
    }

    public Builder email(final String email) {
      this.email = email;
      return this;
    }

    public FocusBuilderBean build() {
      return new FocusBuilderBean(id, email);
    }
  }
}
