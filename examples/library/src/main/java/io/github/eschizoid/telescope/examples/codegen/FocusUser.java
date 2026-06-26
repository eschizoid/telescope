package io.github.eschizoid.telescope.examples.codegen;

import io.github.eschizoid.telescope.annotations.Focus;

/**
 * A {@code @Focus}-annotated record. The {@code telescope-codegen} processor emits a sibling {@code
 * FocusUserTelescope<R>} navigator with one method per component and the full Telescope op surface
 * forwarded — see {@code CodegenDemo}.
 */
@Focus
public record FocusUser(String name, String email) {}
