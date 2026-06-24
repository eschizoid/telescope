package io.github.eschizoid.telescope.examples.graphql.model;

import io.github.eschizoid.telescope.annotations.FromMap;

/**
 * Target record for the GraphQL createUser input. {@code @FromMap} generates {@code UserFromMap}.
 */
@FromMap
public record User(String name, String email, int age, Role role, Address address) {}
