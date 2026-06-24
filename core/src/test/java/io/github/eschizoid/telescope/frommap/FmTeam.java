package io.github.eschizoid.telescope.frommap;

import io.github.eschizoid.telescope.annotations.FromMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Kitchen-sink record: String, primitive-from-String, enum, nested, List<nested>, Set<String>,
 * Map<String,nested>.
 */
@FromMap
public record FmTeam(
  String name,
  int size,
  FmRole role,
  FmAddress hq,
  List<FmAddress> sites,
  Set<String> tags,
  Map<String, FmAddress> byCity
) {}
