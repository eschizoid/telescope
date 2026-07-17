package io.github.eschizoid.telescope.examples.graphql.model;

/**
 * The mutable persistence-side mirror of {@link Account} — a classic JavaBean (no-arg constructor +
 * getters/setters). It is both the {@code @Bridge} target for {@link Account} (codegen conversion)
 * and the target of a runtime {@code Telescope.mapper(Account.class, AccountEntity.class)} in
 * {@link io.github.eschizoid.telescope.examples.graphql.server.NativeVerify}, which exercises the
 * Beans LMF no-arg-constructor + setter write path.
 */
public class AccountEntity {

  private String username;
  private String email;

  public AccountEntity() {}

  public String getUsername() {
    return username;
  }

  public void setUsername(final String username) {
    this.username = username;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(final String email) {
    this.email = email;
  }
}
