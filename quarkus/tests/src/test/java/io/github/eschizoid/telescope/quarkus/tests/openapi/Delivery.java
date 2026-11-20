package io.github.eschizoid.telescope.quarkus.tests.openapi;

/**
 * A domain type a resource returns, mapped to {@link Parcel}.
 *
 * @param id the delivery's id
 * @param item what it carries
 */
public record Delivery(String id, Article item) {
  /**
   * An article of a delivery.
   *
   * @param sku the stock-keeping unit
   * @param qty the quantity
   */
  public record Article(String sku, int qty) {}
}
