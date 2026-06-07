package io.github.eschizoid.telescope.examples.codegen;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/**
 * A {@code @BeanFocus}-annotated POJO. The codegen processor emits a sibling {@code
 * BeanFocusUserPath<R>} navigator using the no-arg + setters rebuild strategy.
 */
@BeanFocus
public final class BeanFocusUser {

  private String id;
  private String email;

  public BeanFocusUser() {}

  public BeanFocusUser(final String id, final String email) {
    this.id = id;
    this.email = email;
  }

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

  @Override
  public String toString() {
    return "BeanFocusUser[id=" + id + ", email=" + email + "]";
  }
}
