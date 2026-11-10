package io.github.telescopeorganization.telescope.beans;

import io.github.telescopeorganization.telescope.annotations.Bridge;

/** Codegen fixture: record&harr;record bridge (both sides via canonical constructor). */
@Bridge(BridgeRecB.class)
public record BridgeRecA(String id, int score) {}
