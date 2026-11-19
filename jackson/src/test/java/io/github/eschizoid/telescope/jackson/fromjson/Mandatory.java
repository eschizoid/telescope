package io.github.eschizoid.telescope.jackson.fromjson;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.eschizoid.telescope.jackson.FromJson;

/** A record whose renamed component a document must carry. */
@FromJson
public record Mandatory(@JsonProperty(value = "partner_id", required = true) String id, String name) {}
