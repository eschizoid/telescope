package io.github.telescopeorganization.telescope;

import io.github.telescopeorganization.telescope.annotations.Focus;
import java.util.Set;

/** Fusion fixture whose container is a {@code Set}, so edits can collapse two elements into one. */
@Focus
record FusionTagged(String label, Set<FusionMember> tags, FusionMember owner) {}
