package io.github.eschizoid.telescope.collections;

import java.util.List;

/** A tagged bean as a client sends it. */
public record ATaggedDto(String name, List<String> tags) {}
