package io.github.eschizoid.telescope.spring.web.fixtures.request;

import jakarta.validation.constraints.NotBlank;

/**
 * The wire DTO: its names differ from {@link NewUser}'s, so only a mapper can turn one into the
 * other.
 */
public record CreateUserRequest(@NotBlank String displayName, String emailAddress) {}
