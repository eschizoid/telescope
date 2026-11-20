package io.github.eschizoid.telescope.quarkus.tests.openapi.cycle.x;

/**
 * A wire type that refers to a {@link Link}, which refers back to it.
 *
 * @param xv a value
 * @param other the link
 */
public record Node(String xv, Link other) {}
