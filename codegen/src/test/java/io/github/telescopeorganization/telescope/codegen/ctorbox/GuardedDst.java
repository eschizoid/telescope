package io.github.telescopeorganization.telescope.codegen.ctorbox;

/**
 * A target holding the list whose constructor is protected.
 *
 * @param items the list
 */
public record GuardedDst(ProtectedBag<String> items) {}
