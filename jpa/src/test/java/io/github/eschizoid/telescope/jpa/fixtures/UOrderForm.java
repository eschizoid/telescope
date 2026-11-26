package io.github.eschizoid.telescope.jpa.fixtures;

import java.util.List;

/** A unidirectional order as a client sends it. */
public record UOrderForm(Long id, String name, List<ItemForm> lines) {}
