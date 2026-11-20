package io.github.eschizoid.telescope.quarkus.tests.unpaired;

/** A projection interface a resource returns, which the mapping reads by its getters. */
public interface CityView {
  /**
   * @return the city
   */
  String getCity();
}
