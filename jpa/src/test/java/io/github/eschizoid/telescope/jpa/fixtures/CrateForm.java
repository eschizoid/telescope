package io.github.eschizoid.telescope.jpa.fixtures;

import java.util.List;

/** A crate as a client sends it. */
public record CrateForm(Long id, String name, List<ItemForm> bottles) {}
