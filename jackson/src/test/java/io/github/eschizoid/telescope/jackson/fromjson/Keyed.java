package io.github.eschizoid.telescope.jackson.fromjson;

import io.github.eschizoid.telescope.jackson.FromJson;
import java.util.Map;
import java.util.UUID;

/** Maps keyed by each kind of key a JSON object's text can be read as. */
@FromJson
public record Keyed(
  Map<Status, String> byStatus,
  Map<UUID, Integer> byId,
  Map<Integer, String> byNumber,
  Map<Boolean, String> byFlag
) {}
