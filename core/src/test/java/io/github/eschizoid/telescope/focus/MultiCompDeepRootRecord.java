package io.github.eschizoid.telescope.focus;

import io.github.eschizoid.telescope.annotations.Focus;

/**
 * Extra root level above {@link MultiCompOuterRecord}, pushing the multi-component {@link
 * MultiCompLeafRecord} to hop 3 (reached through two null single-component records). Pins that the
 * record off-path null-guard is N-hop, not specific to a single nesting depth.
 */
@Focus
record MultiCompDeepRootRecord(MultiCompOuterRecord outer) {}
