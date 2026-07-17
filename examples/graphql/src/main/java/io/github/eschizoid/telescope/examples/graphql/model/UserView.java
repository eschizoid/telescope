package io.github.eschizoid.telescope.examples.graphql.model;

/**
 * A read-model projection of {@link User} with the same component names and types, so a runtime
 * {@code Telescope.mapper(User.class, UserView.class)} maps the whole graph by same-name inference
 * — carrying the nested {@link Address} record through unchanged and the {@link Role} enum through
 * by identity. Used by {@link io.github.eschizoid.telescope.examples.graphql.server.NativeVerify}
 * to exercise the runtime deep record→record mapper (LMF readers + canonical-constructor rebuild)
 * under native-image.
 */
public record UserView(String name, String email, int age, Role role, Address address) {}
