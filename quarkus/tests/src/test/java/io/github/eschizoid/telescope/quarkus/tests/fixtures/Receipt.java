package io.github.eschizoid.telescope.quarkus.tests.fixtures;

/** A view of an order whose customer is named buyer, so only a mapper with a rename fills it. */
public record Receipt(String number, String buyer, long costCents) {}
