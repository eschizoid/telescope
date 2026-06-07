package io.github.eschizoid.telescope.examples.codegen;

import io.github.eschizoid.telescope.annotations.Focus;

/**
 * A {@code @Focus}-annotated record. The {@code telescope-codegen} processor emits a sibling {@code
 * FocusUserPath<R>} navigator with one method per component and the full Telescope op surface
 * forwarded — see {@link io.github.eschizoid.telescope.examples.CodegenDemo}.
 */
@Focus
public record FocusUser(String name, String email) {}
