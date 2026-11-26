package io.github.eschizoid.telescope.jpa.fixtures;

import java.util.List;

/** A rack as a client sends it. */
public record RackForm(Long id, String name, List<ItemForm> jars) {}
