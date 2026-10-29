package io.github.eschizoid.telescope.ownbridge;

/**
 * A source whose bridge to the {@code twin} package's {@code ObCarriedDto} is declared by a
 * {@code @Bridge} field of a class in that package, while {@link ObCarriedBox} has the processor
 * derive a long-form bridge to this package's class of that name.
 */
public record ObCarried(String name) {}
