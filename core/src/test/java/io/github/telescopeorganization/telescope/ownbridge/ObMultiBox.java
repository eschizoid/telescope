package io.github.telescopeorganization.telescope.ownbridge;

import io.github.telescopeorganization.telescope.annotations.Bridge;

/** Holds a source declaring two targets, whose own bridge to one of them transforms its label. */
@Bridge(ObMultiBoxDto.class)
public record ObMultiBox(ObMulti multi) {}
