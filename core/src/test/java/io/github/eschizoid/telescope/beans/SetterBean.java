package io.github.eschizoid.telescope.beans;

/** Codegen test fixture: POJO reconstructed via a no-arg constructor + setters. */
public final class SetterBean {

  private String id;
  private String email;

  public SetterBean() {}

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
