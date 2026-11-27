package io.github.eschizoid.telescope.jpa.fixtures;

import java.util.List;
import java.util.Set;

/** A basket as a PATCH sends it: a property left null is not changed. */
public record BasketPatch(Long id, String name, List<LinePatch> lines, Set<TagPatch> tags) {}
