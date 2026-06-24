package io.github.eschizoid.telescope.frommap;

import io.github.eschizoid.telescope.annotations.FromMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Deeply nested containers: Map-of-List-of-@FromMap, List-of-Map, and Optional-of-List. */
@FromMap
public record FmNested(
  Map<String, List<FmAddress>> byTeam,
  List<Map<String, String>> rows,
  Optional<List<FmAddress>> maybe
) {}
