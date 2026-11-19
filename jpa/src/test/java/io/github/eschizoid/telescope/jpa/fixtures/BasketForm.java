package io.github.eschizoid.telescope.jpa.fixtures;

import java.util.List;
import java.util.Set;

/** A basket as a client sends it. The generated bridges convert to and from it. */
public record BasketForm(Long id, String name, List<LineForm> lines, Set<TagForm> tags) {}
