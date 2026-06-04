package com.github.eschizoid.telescope.focus;

import com.github.eschizoid.telescope.annotations.Focus;

/**
 * The bridge target for {@link FocusEntity} — also {@code @Focus}'d so the bridge hop returns a
 * Path.
 */
@Focus
record FocusDto(String id, String email) {}
