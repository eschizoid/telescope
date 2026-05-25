package org.telescope.beans;

/** Codegen test fixture: POJO with a public all-args constructor in record-component order. */
public final class CtorPojo {

  private final String id;
  private final String email;

  public CtorPojo(final String id, final String email) {
    this.id = id;
    this.email = email;
  }

  public String getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }
}
