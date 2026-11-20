package io.github.eschizoid.telescope.quarkus.tests.fixtures;

/** The type a resource method works with for a new order. */
public record PlaceOrder(String customer, int quantity) {}
