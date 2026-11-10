package io.github.telescopeorganization.telescope;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.annotations.Focus;

/** Bridge-hop fixture: the navigator gains {@code asFusionDto()}. */
@Focus
@Bridge(FusionDto.class)
record FusionEntity(String id, String email) {}
