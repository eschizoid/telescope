package io.github.eschizoid.telescope.codegen.ctorbox;

/**
 * The source a path reads {@link PrivateCity}'s city through.
 *
 * @param id carried across by name
 * @param c read through to its city
 */
public record CityHolder(String id, PrivateCity c) {}
