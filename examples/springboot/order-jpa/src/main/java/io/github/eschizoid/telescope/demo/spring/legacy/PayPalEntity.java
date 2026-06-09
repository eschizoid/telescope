package io.github.eschizoid.telescope.demo.spring.legacy;

public final class PayPalEntity implements PaymentEntity {

  private String email;
  private String token;

  public PayPalEntity() {}

  public String getEmail() {
    return email;
  }

  public void setEmail(final String email) {
    this.email = email;
  }

  public String getToken() {
    return token;
  }

  public void setToken(final String token) {
    this.token = token;
  }
}
