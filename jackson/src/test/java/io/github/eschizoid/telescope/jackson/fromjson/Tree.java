package io.github.eschizoid.telescope.jackson.fromjson;

import io.github.eschizoid.telescope.jackson.FromJson;
import java.util.List;

/** A type that holds itself. */
@FromJson
public record Tree(String name, List<Tree> children) {}
