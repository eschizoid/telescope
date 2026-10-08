package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.annotations.Focus;

/** Bridge target for {@link FusionEntity}. */
@Focus
record FusionDto(String id, String email) {}
