package io.github.eschizoid.telescope.jpa.fixtures;

import java.util.List;

/** The body of a PATCH of a cart; a property left null is not changed. */
public record CartChanges(String name, List<LineChanges> lines) {}
