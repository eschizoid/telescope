package io.github.eschizoid.telescope.quarkus.tests.openapi;

/**
 * A domain type that holds another record.
 *
 * @param id the shipment's id
 * @param address where it goes
 */
public record Shipment(String id, Address address) {
  /**
   * The address a shipment goes to.
   *
   * @param city the city
   * @param courierNote a note the client never sees
   */
  public record Address(String city, String courierNote) {}
}
