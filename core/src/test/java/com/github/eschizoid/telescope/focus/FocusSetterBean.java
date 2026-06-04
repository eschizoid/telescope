package com.github.eschizoid.telescope.focus;

import com.github.eschizoid.telescope.annotations.BeanFocus;

/**
 * @BeanFocus fixture: rebuilt via no-arg constructor + setters.
 */
@BeanFocus
public class FocusSetterBean {

  private String id;
  private String email;

  public FocusSetterBean() {}

  public String getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public void setId(final String id) {
    this.id = id;
  }

  public void setEmail(final String email) {
    this.email = email;
  }
}
