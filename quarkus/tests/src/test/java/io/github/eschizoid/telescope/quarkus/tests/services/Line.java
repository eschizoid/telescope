package io.github.eschizoid.telescope.quarkus.tests.services;

/** A line a client's changes are written into. */
public class Line {

  private String sku;
  private int price;

  public String getSku() {
    return sku;
  }

  public void setSku(final String sku) {
    this.sku = sku;
  }

  public int getPrice() {
    return price;
  }

  public void setPrice(final int price) {
    this.price = price;
  }
}
