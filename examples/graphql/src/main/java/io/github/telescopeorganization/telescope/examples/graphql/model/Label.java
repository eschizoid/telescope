package io.github.telescopeorganization.telescope.examples.graphql.model;

/**
 * The target {@link Tagged} converts to in {@link
 * io.github.telescopeorganization.telescope.examples.graphql.server.NativeVerify}: one {@code
 * String} component.
 *
 * @param value the label's text
 */
public record Label(String value) {}
