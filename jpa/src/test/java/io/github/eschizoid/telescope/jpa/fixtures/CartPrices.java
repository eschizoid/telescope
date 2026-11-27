package io.github.eschizoid.telescope.jpa.fixtures;

/** The price of each SKU: a hundred per character of it. */
public final class CartPrices {

  private CartPrices() {}

  public static int of(final LineChanges line) {
    return line.sku().length() * 100;
  }
}
