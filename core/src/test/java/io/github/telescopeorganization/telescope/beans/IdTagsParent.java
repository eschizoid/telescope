package io.github.telescopeorganization.telescope.beans;

import io.github.telescopeorganization.telescope.annotations.Bridge;

/** Source for the identity-element raw-subtype e2e: same element type both sides (String). */
@Bridge(IdTagsParentDst.class)
public record IdTagsParent(IdTagsSrc tags) {}
