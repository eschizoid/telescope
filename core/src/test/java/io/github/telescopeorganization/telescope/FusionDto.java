package io.github.telescopeorganization.telescope;

import io.github.telescopeorganization.telescope.annotations.Focus;

/** Bridge target for {@link FusionEntity}. */
@Focus
record FusionDto(String id, String email) {}
