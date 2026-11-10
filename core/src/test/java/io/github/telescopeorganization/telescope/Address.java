package io.github.telescopeorganization.telescope;

import io.github.telescopeorganization.telescope.annotations.Focus;

/** Top-level @Focus fixture for {@code TelescopeMappingTest}'s codegen-1:1 verification. */
@Focus
public record Address(String city, String zip) {}
