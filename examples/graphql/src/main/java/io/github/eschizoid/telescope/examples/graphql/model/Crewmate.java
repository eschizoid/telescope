package io.github.eschizoid.telescope.examples.graphql.model;

import io.github.eschizoid.telescope.annotations.Focus;

/** Element of {@link Shift}; navigator-only, with no reflection registration either. */
@Focus
public record Crewmate(String name, String handle) {}
