package io.github.eschizoid.telescope.quarkus.tests.openapi;

/**
 * The wire type of a delivery, built like another class of its simple name in all but the record it
 * holds.
 *
 * @param id the delivery's id
 * @param item what it carries
 */
public record Parcel(String id, Item item) {
  /**
   * An item as the client sees it.
   *
   * @param sku the stock-keeping unit
   */
  public record Item(String sku) {}
}
