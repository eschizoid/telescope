package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Focus;

/** Bridge-hop fixture: the navigator gains {@code asFusionDto()}. */
@Focus
@Bridge(FusionDto.class)
record FusionEntity(String id, String email) {}
