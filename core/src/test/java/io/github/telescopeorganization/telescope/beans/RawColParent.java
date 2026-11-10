package io.github.telescopeorganization.telescope.beans;

import io.github.telescopeorganization.telescope.annotations.Bridge;

/** Source for the raw collection-subtype end-to-end test: a custom ArrayList wrapper field. */
@Bridge(RawColParentDst.class)
public record RawColParent(RawColSrcWrap items) {}
