package io.github.eschizoid.telescope.spring.web.fixtures;

/**
 * A response type that pairs {@code id} with a {@link User} and leaves {@code nickname} unfilled.
 */
public record UserCard(String id, String nickname) {}
