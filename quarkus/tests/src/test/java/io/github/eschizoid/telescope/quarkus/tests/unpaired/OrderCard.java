package io.github.eschizoid.telescope.quarkus.tests.unpaired;

/**
 * A view of an order that pairs {@code number} with it and leaves {@code nickname} unfilled.
 *
 * @param number the order's number
 * @param nickname a component no order carries
 */
public record OrderCard(String number, String nickname) {}
