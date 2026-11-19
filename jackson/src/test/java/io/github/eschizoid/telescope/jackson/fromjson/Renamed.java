package io.github.eschizoid.telescope.jackson.fromjson;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.eschizoid.telescope.jackson.FromJson;

/** A record whose key an accessor declared in its body renames. */
@FromJson
public record Renamed(String id) {
  @Override
  @JsonProperty("ident")
  public String id() {
    return id;
  }
}
