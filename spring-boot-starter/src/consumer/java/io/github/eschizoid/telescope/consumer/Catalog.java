package io.github.eschizoid.telescope.consumer;

import java.math.BigDecimal;

/** Prices a line by its SKU: the service the README's line rows call, a bean in an application. */
public class Catalog {

  public BigDecimal price(final LineDto line) {
    return BigDecimal.TEN;
  }

  public BigDecimal cost(final LineDto line) {
    return BigDecimal.ONE;
  }
}
