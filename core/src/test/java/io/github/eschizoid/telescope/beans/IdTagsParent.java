package io.github.eschizoid.telescope.beans;

import io.github.eschizoid.telescope.annotations.Bridge;

/** Source for the identity-element raw-subtype e2e: same element type both sides (String). */
@Bridge(IdTagsParentDst.class)
public record IdTagsParent(IdTagsSrc tags) {}
