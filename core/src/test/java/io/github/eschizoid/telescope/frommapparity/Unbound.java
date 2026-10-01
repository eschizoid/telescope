package io.github.eschizoid.telescope.frommapparity;

/**
 * A top-level record with no {@code @FromMap}, so no binder is generated beside it. Top-level
 * matters: it is the shape a binder could exist for, which makes the lookup for one the thing that
 * decides it is refused.
 */
public record Unbound(String city) {}
