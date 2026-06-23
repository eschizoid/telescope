package io.github.eschizoid.telescope.beans;

import io.github.eschizoid.telescope.annotations.Bridge;

/** Source for the raw collection-subtype end-to-end test: a custom ArrayList wrapper field. */
@Bridge(RawColParentDst.class)
public record RawColParent(RawColSrcWrap items) {}
