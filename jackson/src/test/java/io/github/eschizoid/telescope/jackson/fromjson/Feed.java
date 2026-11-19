package io.github.eschizoid.telescope.jackson.fromjson;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.eschizoid.telescope.jackson.FromJson;
import java.util.List;

/** A feed holding annotated records, one under a renamed key and a list of them. */
@FromJson
public record Feed(
  @JsonProperty("feed_name") String name,
  @JsonProperty("partner_info") Partner partner,
  List<Partner> partners
) {}
