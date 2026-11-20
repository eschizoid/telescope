package io.github.eschizoid.telescope.quarkus.tests.openapi;

/**
 * The response type of a shipment, which holds a record of its own that no resource signature
 * names.
 *
 * @param id the shipment's id
 * @param address where it goes
 */
public record ShipmentDto(String id, AddressDto address) {
  /**
   * The address as the client sees it.
   *
   * @param city the city
   */
  public record AddressDto(String city) {}
}
