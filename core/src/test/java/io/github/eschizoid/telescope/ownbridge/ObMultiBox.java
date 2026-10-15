package io.github.eschizoid.telescope.ownbridge;

import io.github.eschizoid.telescope.annotations.Bridge;

/** Holds a source declaring two targets, whose own bridge to one of them transforms its label. */
@Bridge(ObMultiBoxDto.class)
public record ObMultiBox(ObMulti multi) {}
