package io.github.eschizoid.telescope.demo.spring.domain;

import io.github.eschizoid.telescope.annotations.Focus;

/**
 * A customer at the API / domain boundary — immutable record. The persistence-side equivalent is
 * {@code persistence.CustomerEntity}; the shared {@code OrderMappers} config handles the
 * conversion via {@code Telescope.mapper(...)}.
 *
 * <p>{@code id} is nullable on the way in (the API client doesn't know the database id when
 * creating a new customer) and populated on the way out after Hibernate assigns one. {@code @Focus}
 * triggers the path navigator + holder metadata; {@code @Bridge} would be ideal here too but is
 * blocked by the same cross-package path-hop visibility bug noted on {@code Address}.
 */
@Focus
public record Customer(Long id, String name, String email) {}
