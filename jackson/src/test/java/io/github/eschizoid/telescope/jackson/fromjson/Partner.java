package io.github.eschizoid.telescope.jackson.fromjson;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.eschizoid.telescope.jackson.FromJson;

/** A partner whose keys differ from its component names. */
@FromJson
public record Partner(
  @JsonProperty("partner_id") String id,
  @JsonAlias({ "partnerName", "name_v1" }) String name,
  @JsonIgnore String internal,
  Integer tier
) {}
