package io.github.telescopeorganization.telescope.ownbridge;

import io.github.telescopeorganization.telescope.annotations.Bridge;

/** A nested pair with its own bridge, holding a pair whose target is a bean. */
@Bridge(ObEnvelopeDto.class)
public record ObEnvelope(ObNote note) {}
