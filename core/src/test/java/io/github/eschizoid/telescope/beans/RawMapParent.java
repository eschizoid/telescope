package io.github.eschizoid.telescope.beans;

import io.github.eschizoid.telescope.annotations.Bridge;

/** Source for the raw Map-subtype end-to-end test: a custom HashMap wrapper field. */
@Bridge(RawMapParentDst.class)
public record RawMapParent(RawMapSrcWrap byKey) {}
