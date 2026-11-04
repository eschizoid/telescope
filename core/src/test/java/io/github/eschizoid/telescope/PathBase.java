package io.github.eschizoid.telescope;

/** Declares the property {@code customer} that {@link PathSub} inherits. */
public class PathBase {

  private PathShorthandTest.Customer customer;

  public PathShorthandTest.Customer getCustomer() {
    return customer;
  }

  public void setCustomer(final PathShorthandTest.Customer customer) {
    this.customer = customer;
  }
}
