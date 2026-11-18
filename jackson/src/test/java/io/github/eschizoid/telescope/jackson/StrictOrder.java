package io.github.eschizoid.telescope.jackson;

import io.github.eschizoid.telescope.annotations.FromMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Containers of each kind, their elements converted by the rules their own types follow. */
@FromMap
public record StrictOrder(
  List<StrictLine> lines,
  Set<Integer> codes,
  Map<String, Integer> counts,
  List<Integer> numbers
) {}
