package io.github.eschizoid.telescope.quarkus.tests.fixtures;

/** The domain type a resource returns: it carries a cost the client never sees. */
public record Order(String number, String customer, long costCents) {}
