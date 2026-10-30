package io.github.eschizoid.telescope.benchmarks;

/**
 * The flat target {@link NestedPathBenchmark} reads {@link NpOrder}'s nested leaves into.
 *
 * @param city the address's city
 * @param zip the address's zip
 * @param qty carried across by name
 */
public record NpOrderDto(String city, int zip, int qty) {}
