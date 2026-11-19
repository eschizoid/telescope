package io.github.eschizoid.telescope.jackson.fromjson;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.eschizoid.telescope.jackson.FromJson;

/** A record with a component databind only writes out and never reads. */
@FromJson
public record Shown(@JsonProperty(access = JsonProperty.Access.READ_ONLY) String shown, String kept) {}
