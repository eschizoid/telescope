package io.github.eschizoid.telescope.quarkus.tests.openapi.cycle.x;

/**
 * The record a {@link Node} refers to, referring back to a {@link Node}.
 *
 * @param yv a value
 * @param back the node
 */
public record Link(int yv, Node back) {}
