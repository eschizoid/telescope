package org.telescope.focus;

import org.telescope.annotations.Focus;

/**
 * The bridge target for {@link FocusEntity} — also {@code @Focus}'d so the bridge hop returns a
 * Path.
 */
@Focus
record FocusDto(String id, String email) {}
