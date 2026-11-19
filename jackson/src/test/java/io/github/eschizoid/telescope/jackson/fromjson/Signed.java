package io.github.eschizoid.telescope.jackson.fromjson;

import io.github.eschizoid.telescope.annotations.FromMap;
import io.github.eschizoid.telescope.jackson.FromJson;

/**
 * A record whose {@code @FromMap} binder declares a key required, which JSON is held to as well.
 */
@FromJson
@FromMap(required = { "email" })
public record Signed(String email, String name) {}
