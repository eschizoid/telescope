package io.github.eschizoid.telescope.quarkus.tests.openapi;

/**
 * A domain type holding a line.
 *
 * @param id the consignment's id
 * @param line its line
 */
public record Consignment(String id, Line line) {
  /**
   * A line of a consignment.
   *
   * @param sku the stock-keeping unit
   * @param qty the quantity
   */
  public record Line(String sku, int qty) {}
}
