package io.github.eschizoid.telescope.jackson;

import io.github.eschizoid.telescope.annotations.FromMap;

/** A record whose generated {@code @FromMap} binder declares a key required. */
@FromMap(required = { "email" })
public record Signup(String email, String name) {}
