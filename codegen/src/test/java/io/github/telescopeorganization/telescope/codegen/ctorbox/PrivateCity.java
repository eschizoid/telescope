package io.github.telescopeorganization.telescope.codegen.ctorbox;

/**
 * A bean with a setter for its one property and a no-argument constructor only the class itself
 * calls, which a path built through its setters reaches on the runtime path and not from generated
 * code.
 */
public class PrivateCity {

  private String city;

  private PrivateCity() {}

  public String getCity() {
    return city;
  }

  public void setCity(final String city) {
    this.city = city;
  }

  /** A {@code PrivateCity} holding {@code city}. */
  public static PrivateCity of(final String city) {
    final var built = new PrivateCity();
    built.city = city;
    return built;
  }
}
