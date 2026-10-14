package io.github.eschizoid.telescope.ownbridge;

/** Holds {@link ObCb} directly and again through {@link ObCa}, which it refers to. */
public record ObOff(ObCb b, ObCa a) {}
