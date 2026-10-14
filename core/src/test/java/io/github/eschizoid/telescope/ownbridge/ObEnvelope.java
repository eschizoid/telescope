package io.github.eschizoid.telescope.ownbridge;

import io.github.eschizoid.telescope.annotations.Bridge;

/** A nested pair with its own bridge, holding a pair whose target is a bean. */
@Bridge(ObEnvelopeDto.class)
public record ObEnvelope(ObNote note) {}
