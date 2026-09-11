package io.github.eschizoid.telescope.internal;

/**
 * Fixture pair for a holder whose class initializer cannot complete. Stands in for the shape that
 * matters in practice: a type present when the holder was compiled and absent at run time, which
 * arrives as a {@code NoClassDefFoundError} from the same initializer.
 */
public record HolderFailingInit(String name) {}
