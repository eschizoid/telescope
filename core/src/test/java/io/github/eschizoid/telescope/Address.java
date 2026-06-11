package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.annotations.Focus;

/** Top-level @Focus fixture for {@code TelescopeMappingTest}'s codegen-1:1 verification. */
@Focus
public record Address(String city, String zip) {}
