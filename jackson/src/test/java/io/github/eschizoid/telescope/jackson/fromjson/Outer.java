package io.github.eschizoid.telescope.jackson.fromjson;

import io.github.eschizoid.telescope.jackson.FromJson;

/** A record with an id at two levels, to show each level has its own keys. */
@FromJson
public record Outer(String id, Inner inner) {}
