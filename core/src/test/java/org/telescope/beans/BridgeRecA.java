package org.telescope.beans;

import org.telescope.annotations.Bridge;

/** Codegen fixture: record&harr;record bridge (both sides via canonical constructor). */
@Bridge(BridgeRecB.class)
public record BridgeRecA(String id, int score) {}
