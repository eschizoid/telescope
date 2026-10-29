package io.github.eschizoid.telescope.ownbridge;

/**
 * A source whose short-form bridge, {@link ObSoloBridge}, converts to the {@code twin} package's
 * {@code ObSoloDto}, while {@link ObSoloBox} has the processor derive a long-form bridge to this
 * package's class of that name.
 */
public record ObSolo(String name) {}
