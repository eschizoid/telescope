package io.github.eschizoid.telescope.examples.graphql.model;

/**
 * A class only a row's type argument names: {@link
 * io.github.eschizoid.telescope.examples.graphql.server.NativeVerify} reads {@code
 * Tagged<Badge>.value} as {@code Badge}, which the mapper loads by name, so inside an image it has
 * to be registered for reflection, though nothing reads its members reflectively.
 *
 * @param text the badge's text
 */
public record Badge(String text) {}
