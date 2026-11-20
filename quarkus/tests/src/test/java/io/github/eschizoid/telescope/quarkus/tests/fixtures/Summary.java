package io.github.eschizoid.telescope.quarkus.tests.fixtures;

/** A view of an order that a projection writes, with the customer as the projection leaves it. */
public record Summary(String number, String customer, long costCents) {}
