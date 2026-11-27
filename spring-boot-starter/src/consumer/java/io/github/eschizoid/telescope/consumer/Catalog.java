package io.github.eschizoid.telescope.consumer;

import java.math.BigDecimal;

/** Prices a line by its SKU. */
public final class Catalog {

  private Catalog() {}

  public static BigDecimal price(final LineDto line) {
    return BigDecimal.TEN;
  }

  public static BigDecimal cost(final LineDto line) {
    return BigDecimal.ONE;
  }
}
