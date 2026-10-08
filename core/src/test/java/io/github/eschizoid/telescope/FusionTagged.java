package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.annotations.Focus;
import java.util.Set;

/** Fusion fixture whose container is a {@code Set}, so edits can collapse two elements into one. */
@Focus
record FusionTagged(String label, Set<FusionMember> tags, FusionMember owner) {}
