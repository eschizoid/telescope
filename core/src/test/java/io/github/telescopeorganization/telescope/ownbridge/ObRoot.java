package io.github.telescopeorganization.telescope.ownbridge;

/** Reaches {@link ObP} twice: directly, and through {@link ObX}, which {@link ObP} holds. */
public record ObRoot(ObX x, ObP p) {}
