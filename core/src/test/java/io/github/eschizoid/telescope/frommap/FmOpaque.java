package io.github.eschizoid.telescope.frommap;

import io.github.eschizoid.telescope.annotations.FromMap;

/**
 * A component declared {@code Object}, which is what an untyped source hands back before anything
 * narrows it.
 *
 * <p>The binder reads every value as an {@code Object}, so this component needs no conversion at
 * all. A cast to the type the read already has narrows nothing, and javac reports one under {@code
 * -Xlint:cast}; this module compiles with {@code -Werror}, so a binder that emits it does not
 * build. The fixture is the guard: it is generated and compiled by this module's own test
 * compilation.
 */
@FromMap
public record FmOpaque(String name, Object payload) {}
