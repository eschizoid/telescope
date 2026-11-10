package io.github.telescopeorganization.telescope.examples.graphql.model;

import io.github.telescopeorganization.telescope.annotations.Focus;
import io.github.telescopeorganization.telescope.annotations.FromMap;

/**
 * Target record for the GraphQL createUser input. {@code @FromMap} generates {@code UserFromMap};
 * {@code @Focus} generates the {@code UserTelescope} navigator the native verifier exercises.
 */
@Focus
@FromMap
public record User(String name, String email, int age, Role role, Address address) {}
