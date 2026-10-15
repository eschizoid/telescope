package io.github.eschizoid.telescope.ownbridge;

/**
 * A source whose bridge to the {@code twin} package's {@code ObCarriedDto} lives on a carrier,
 * while {@link ObCarriedBox} has the processor derive a long-form bridge to this package's class of
 * that name.
 */
public record ObCarried(String name) {}
