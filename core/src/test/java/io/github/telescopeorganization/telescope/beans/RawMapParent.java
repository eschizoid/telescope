package io.github.telescopeorganization.telescope.beans;

import io.github.telescopeorganization.telescope.annotations.Bridge;

/** Source for the raw Map-subtype end-to-end test: a custom HashMap wrapper field. */
@Bridge(RawMapParentDst.class)
public record RawMapParent(RawMapSrcWrap byKey) {}
