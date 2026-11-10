package io.github.telescopeorganization.telescope.focus;

import io.github.telescopeorganization.telescope.annotations.Focus;

/**
 * The bridge target for {@link FocusEntity} — also {@code @Focus}'d so the bridge hop returns a
 * Path.
 */
@Focus
record FocusDto(String id, String email) {}
