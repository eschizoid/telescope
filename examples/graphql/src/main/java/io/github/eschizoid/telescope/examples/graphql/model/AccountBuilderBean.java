package io.github.eschizoid.telescope.examples.graphql.model;

/**
 * An immutable, builder-only mirror of {@link Account} — no no-arg constructor and no public
 * setters, so a runtime {@code Telescope.mapper(Account.class, AccountBuilderBean.class)} must
 * construct it through the {@code BuilderWriter} strategy (static {@code builder()} → fluent
 * setters → {@code build()}). Used by {@link
 * io.github.eschizoid.telescope.examples.graphql.server.NativeVerify} to exercise that write path
 * under native-image, which the no-arg-ctor {@link AccountEntity} does not reach.
 */
public final class AccountBuilderBean {

  private final String username;
  private final String email;

  private AccountBuilderBean(final String username, final String email) {
    this.username = username;
    this.email = email;
  }

  public static Builder builder() {
    return new Builder();
  }

  public String getUsername() {
    return username;
  }

  public String getEmail() {
    return email;
  }

  /** Fluent builder — each setter returns {@code this}, exercising the {@code BiFunction} bind. */
  public static final class Builder {

    private String username;
    private String email;

    public Builder username(final String username) {
      this.username = username;
      return this;
    }

    public Builder email(final String email) {
      this.email = email;
      return this;
    }

    public AccountBuilderBean build() {
      return new AccountBuilderBean(username, email);
    }
  }
}
