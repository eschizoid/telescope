package io.github.telescopeorganization.telescope.examples.codegen;

import io.github.telescopeorganization.telescope.annotations.Focus;

/** A second {@code @Focus} record so we can demo cross-Path composition via .then(). */
@Focus
public record FocusAddress(String city, String zip) {}
