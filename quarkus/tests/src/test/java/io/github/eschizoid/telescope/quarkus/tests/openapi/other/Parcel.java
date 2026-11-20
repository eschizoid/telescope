package io.github.eschizoid.telescope.quarkus.tests.openapi.other;

/**
 * A type an unmapped endpoint returns, built like a wire type of its simple name in all but the
 * record it holds, which shares its simple name with that wire type's record too.
 *
 * @param id the parcel's id
 * @param item what it carries
 */
public record Parcel(String id, Item item) {
  /**
   * An item with a quantity.
   *
   * @param qty the quantity
   */
  public record Item(int qty) {}
}
