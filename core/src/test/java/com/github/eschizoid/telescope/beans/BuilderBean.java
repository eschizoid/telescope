package com.github.eschizoid.telescope.beans;

/** Codegen test fixture: POJO reconstructed through a static {@code builder()}. */
public final class BuilderBean {

  private final String id;
  private final String email;

  private BuilderBean(final String id, final String email) {
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

    public BuilderBean build() {
      return new BuilderBean(id, email);
    }
  }
}
