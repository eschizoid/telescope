package io.github.eschizoid.telescope.jackson.fromjson;

import io.github.eschizoid.telescope.jackson.FromJson;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Containers whose elements are containers or optional. */
@FromJson
public record Nesting(List<List<Integer>> grid, List<Optional<String>> maybe, Map<String, List<String>> groups) {}
