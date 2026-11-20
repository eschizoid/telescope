package io.github.eschizoid.telescope.quarkus.tests.unpaired;

/**
 * A response type that pairs {@code city} with a {@link CityView}.
 *
 * @param city the city
 */
public record CityRecord(String city) {}
